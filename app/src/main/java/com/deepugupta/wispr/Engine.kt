/*
 * Wispr by Deepu Gupta
 * Copyright 2026 Deepu Gupta
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 * SPDX-License-Identifier: Apache-2.0
 */
package com.deepugupta.wispr

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Record -> encrypt audio on disk -> transcribe -> polish/translate.
 * Every network step retries on its own (5 times, backing off, waiting for internet).
 * If everything still fails, the encrypted audio stays saved so the user can tap Retry later.
 */
object Engine {
    private val pool = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())
    private val DELAYS = longArrayOf(1500, 3000, 6000, 10000, 15000)
    private val running: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Devanagari or Urdu/Arabic letters = Hindi written in a non-Roman script. */
    private val HINDI_SCRIPT = Regex("[\\u0900-\\u097F]")

    private class NoSpeech : Exception()

    fun io(block: () -> Unit) { pool.execute { block() } }
    internal fun post(r: () -> Unit) { main.post { r() } }

    fun submit(ctx: Context, raw: File, onStatus: (String) -> Unit, onDone: (Boolean, String, String) -> Unit) {
        val app = ctx.applicationContext
        pool.execute {
            val st = Store.get(app)
            val id = UUID.randomUUID().toString()
            val saved = try {
                File(st.audioDir, "$id.enc").writeBytes(Crypto.encrypt(raw.readBytes()))
                true
            } catch (e: Exception) {
                false
            } finally {
                raw.delete()
            }
            if (!saved) {
                post { onDone(false, "Couldn't save the recording. Try again.", "") }
            } else {
                st.addItem(JSONObject().put("id", id).put("t", System.currentTimeMillis()).put("status", "pending").put("mode", st.str("mode")))
                work(app, id, onStatus, onDone)
            }
        }
    }

    fun retry(ctx: Context, id: String, onStatus: (String) -> Unit, onDone: (Boolean, String, String) -> Unit) {
        val app = ctx.applicationContext
        pool.execute { work(app, id, onStatus, onDone) }
    }

    private fun work(app: Context, id: String, onStatus: (String) -> Unit, onDone: (Boolean, String, String) -> Unit) {
        if (!running.add(id)) {
            post { onDone(false, "Already working on this one…", id) }
            return
        }
        val st = Store.get(app)
        try {
            val key = st.apiKey
            if (key.isBlank()) throw GroqError("Add your Groq key in the Wispr app first.", false, auth = true)
            val item = st.item(id) ?: throw GroqError("That recording is gone.", false)
            st.patchItem(id) { it.put("status", "pending"); it.remove("err") }
            var raw = item.optString("raw", "")
            if (raw.isEmpty()) {
                val f = File(st.audioDir, "$id.enc")
                if (!f.exists()) throw GroqError("Audio for this one is missing.", false)
                val audio = Crypto.decrypt(f.readBytes())
                val lang = st.str("lang")
                val prompt = whisperPrompt(st, lang)
                raw = withRetry(app, onStatus) { transcribeAny(st, key, audio, lang, prompt) }
                if (raw.isBlank() || isWhisperNoise(raw)) throw NoSpeech()
                val r = raw
                st.patchItem(id) { it.put("raw", r) }
            }
            val text = st.applySpell(shape(app, st, key, raw, onStatus))
            st.patchItem(id) { it.put("status", "ok"); it.put("text", text); it.remove("raw") }
            File(st.audioDir, "$id.enc").delete()
            if (!st.bool("keepHist")) st.removeItem(id)
            post { onDone(true, text, id) }
        } catch (e: NoSpeech) {
            st.removeItem(id)
            post { onDone(false, "Didn't catch that. Try again a bit closer to the mic.", "") }
        } catch (e: Exception) {
            val base = (e as? GroqError)?.message ?: "Something went wrong (${e.javaClass.simpleName})."
            val msg = if (e is GroqError && e.auth) base else "$base Audio saved, tap Retry."
            st.patchItem(id) { it.put("status", "failed"); it.put("err", msg) }
            post { onDone(false, msg, id) }
        } finally {
            running.remove(id)
        }
    }

    /** Whisper's classic hallucinations on silence. */
    internal fun isWhisperNoise(t: String): Boolean {
        val x = t.trim().lowercase().trimEnd('.', '!', ' ')
        return x in setOf("thanks for watching", "thank you for watching", "you", "subtitles by the amara.org community", "please subscribe")
    }

    /** Speech model with automatic fallback if Groq removes the chosen one. */
    internal fun transcribeAny(st: Store, key: String, audio: ByteArray, lang: String, prompt: String?): String {
        val chosen = st.str("stt")
        var last: GroqError? = null
        for (m in Models.sttChain(chosen)) {
            try {
                val t = Groq.transcribe(key, audio, m, lang, prompt)
                if (m != chosen) st.put("stt", m)
                return t
            } catch (e: GroqError) {
                if (!e.modelGone) throw e
                last = e
            }
        }
        throw last ?: GroqError("No speech model available on Groq.", false)
    }

    /**
     * Whisper "prompt": a style example in the chosen language + the user's own words from Spellings
     * (names, brands, jargon). This is the biggest accuracy win for fast speech. Whisper reads max ~224 tokens.
     * Auto-detect gets a mixed English + Hinglish example, so Whisper keeps each part as spoken
     * instead of turning the whole recording into English.
     */
    internal fun whisperPrompt(st: Store, lang: String): String? {
        val style = when (lang) {
            "hinglish" -> Prompts.HINGLISH_HINT
            "hi" -> Prompts.HI_HINT
            "auto" -> Prompts.AUTO_HINT
            else -> null
        }
        val vocab = st.vocabulary().take(40).joinToString(", ")
        val p = listOfNotNull(style, vocab.takeIf { it.isNotBlank() }?.let { "Words: $it." }).joinToString(" ")
        return p.take(600).ifBlank { null }
    }

    /** Text model with automatic fallback (Groq shut down Llama 3.x on 16 Aug 2026). */
    internal fun chatAny(st: Store, key: String, system: String, text: String): String {
        val chosen = st.str("llm")
        var last: GroqError? = null
        for (m in Models.textChain(chosen)) {
            try {
                val t = Groq.chat(key, m, system, text)
                if (m != chosen) st.put("llm", m)
                return t
            } catch (e: GroqError) {
                if (!e.modelGone) throw e
                last = e
            }
        }
        throw last ?: GroqError("No text model available on Groq.", false)
    }

    private fun hasHindiScript(t: String): Boolean = HINDI_SCRIPT.containsMatchIn(t) || Translit.hasArabic(t)

    /**
     * Output script rules:
     * - "hinglish" (default, Hindi toggle OFF): output is ALWAYS Roman letters. Groq model first; if the result still
     *   has Hindi/Urdu script, a second pass; if Groq fails completely, the offline [Translit] converter. 100% no Devanagari.
     * - "hi" (Hindi toggle ON): Devanagari as spoken (Urdu script is fixed to Devanagari).
     * - "auto": every sentence stays in the language it was spoken in (English stays English, Hinglish stays Hinglish,
     *   other languages keep their script). Only Hindi/Urdu script is romanised, unless the Hindi toggle is ON.
     * - "en": English, polished.
     * - other languages: their own script.
     * - Translate mode: target language's script ("Hinglish" target = Roman).
     * Polish = Wispr Flow style: self-corrections applied, lists as points, all in ONE text call (no extra delay).
     */
    private fun shape(app: Context, st: Store, key: String, raw: String, onStatus: (String) -> Unit): String {
        val mode = st.str("mode")
        val lang = st.str("lang")
        val deva = st.bool("deva")
        val fix = st.bool("fixWrite") // Write mode: repair misheard words only (fast speech), never reword
        if (mode == "translate") {
            val target = st.str("target")
            val t = try { withRetry(app, onStatus) { chatAny(st, key, Prompts.translate(target), raw) } } catch (e: GroqError) { raw }
            return if (target == "hinglish") forceRoman(app, st, key, t, onStatus) else t
        }
        val roman = lang == "hinglish" || ((lang == "auto" || lang == "en") && !deva)
        if (roman) {
            val auto = lang == "auto"
            val needs = if (auto) hasHindiScript(raw) else Translit.hasNonLatinIndic(raw)
            val polish = when (lang) {
                "hinglish" -> Prompts.POLISH_HINGLISH
                "en" -> Prompts.POLISH
                else -> Prompts.POLISH_AUTO
            }
            val first = try {
                when {
                    mode == "polish" -> withRetry(app, onStatus) { chatAny(st, key, polish, raw) }
                    needs -> withRetry(app, onStatus) { chatAny(st, key, Prompts.ROMAN, raw) }
                    fix -> withRetry(app, onStatus) { chatAny(st, key, Prompts.FIX_ONLY, raw) }
                    else -> raw
                }
            } catch (e: GroqError) { raw }
            // Auto: only Hindi/Urdu script gets romanised; Tamil, Bengali, etc. keep their own script.
            if (auto && !hasHindiScript(first)) return first
            return forceRoman(app, st, key, first, onStatus)
        }
        var t = try {
            when {
                mode != "polish" && fix -> withRetry(app, onStatus) { chatAny(st, key, Prompts.FIX_ONLY, raw) }
                mode != "polish" -> raw
                lang == "hi" -> withRetry(app, onStatus) { chatAny(st, key, Prompts.POLISH_NATIVE, raw) }
                else -> withRetry(app, onStatus) { chatAny(st, key, Prompts.POLISH, raw) }
            }
        } catch (e: GroqError) { raw }
        if (lang == "hi" && Translit.hasArabic(t)) {
            val src = t
            t = try { withRetry(app, onStatus) { chatAny(st, key, Prompts.TO_DEVANAGARI, src) } } catch (e: GroqError) { src }
        }
        return t
    }

    /** Guarantees Latin-only output: second Groq pass, then the offline converter. */
    internal fun forceRoman(app: Context, st: Store, key: String, text: String, onStatus: (String) -> Unit): String {
        if (!Translit.hasNonLatinIndic(text)) return text
        val second = try { withRetry(app, onStatus) { chatAny(st, key, Prompts.ROMAN, text) } } catch (e: GroqError) { text }
        return if (Translit.hasNonLatinIndic(second)) Translit.toRoman(second) else second
    }

    /** Retries network hiccups and free-plan rate limits (waits as long as Groq asks, max 20 s per try). */
    internal fun <T> withRetry(app: Context, onStatus: (String) -> Unit, block: () -> T): T {
        for (i in DELAYS.indices) {
            try {
                return block()
            } catch (e: GroqError) {
                if (!e.retryable) throw e
                val n = i + 1
                val wait = maxOf(DELAYS[i], minOf(e.retryAfterMs, 20_000L))
                post { onStatus(if (e.code == 429) "Free-plan limit, waiting ${wait / 1000}s ($n/${DELAYS.size})…" else "Network hiccup, retrying $n/${DELAYS.size}…") }
                Thread.sleep(wait)
                waitForNet(app, 30_000L)
            }
        }
        return block()
    }

    private fun online(app: Context): Boolean {
        val cm = app.getSystemService(ConnectivityManager::class.java) ?: return true
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun waitForNet(app: Context, maxMs: Long) {
        val end = SystemClock.elapsedRealtime() + maxMs
        while (!online(app) && SystemClock.elapsedRealtime() < end) Thread.sleep(1000)
    }
}
