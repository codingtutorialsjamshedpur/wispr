/*
 * Wispr by Deepu Gupta
 * Copyright 2026 Deepu Gupta
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 * SPDX-License-Identifier: Apache-2.0
 */
package com.deepugupta.wispr

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Wispr AI: chat, "understand screen" and voice-to-text for the chat box, all with the user's own Groq key.
 * Chat history lives only in memory (gone when the phone restarts) and is trimmed to fit Groq's free plan
 * (8K tokens per minute), so a long chat never hits the limit by itself.
 */
object Assistant {
    private const val MAX_CHARS = 9000   // ~2.5K tokens of history per request
    private const val MAX_TURNS = 24
    private val lock = Any()
    private val turns = ArrayList<Pair<String, String>>() // role to text

    fun historyJson(): String = synchronized(lock) {
        val a = JSONArray()
        for ((r, t) in turns) a.put(JSONObject().put("role", r).put("text", t))
        a.toString()
    }

    fun reset() { synchronized(lock) { turns.clear() } }

    private fun key(st: Store): String {
        val k = st.apiKey
        if (k.isBlank()) throw GroqError("Add your Groq key in the Wispr app first.", false, auth = true)
        return k
    }

    /** Reply language: explicit "Explain in" pick, else the dictation language. */
    private fun style(st: Store): String = st.str("explainIn").ifBlank {
        when (st.str("lang")) { "hi" -> "hi"; "hinglish" -> "hinglish"; else -> "en" }
    }

    private fun run(ctx: Context, done: (Boolean, String) -> Unit, block: (Store, String) -> String) {
        val app = ctx.applicationContext
        Engine.io {
            val st = Store.get(app)
            val r = try {
                true to block(st, key(st))
            } catch (e: GroqError) {
                false to (e.message ?: "Groq error")
            } catch (e: Exception) {
                false to "Something went wrong (${e.javaClass.simpleName})."
            }
            Engine.post { done(r.first, r.second) }
        }
    }

    /** Tries the chosen model, then safe defaults if Groq removed it. */
    private fun completeAny(st: Store, key: String, pref: String, chain: List<String>, msgs: JSONArray, maxTokens: Int): String {
        var last: GroqError? = null
        for (m in chain) {
            try {
                val out = Groq.complete(key, m, msgs, 0.4, maxTokens)
                if (m != st.str(pref)) st.put(pref, m)
                return out
            } catch (e: GroqError) {
                if (!e.modelGone) throw e
                last = e
            }
        }
        throw last ?: GroqError("No model available on Groq.", false)
    }

    fun chat(ctx: Context, userText: String, onStatus: (String) -> Unit, done: (Boolean, String) -> Unit) {
        val q = userText.trim().take(6000)
        if (q.isEmpty()) { done(false, "Type or say something first."); return }
        val app = ctx.applicationContext
        run(app, done) { st, key ->
            val msgs = JSONArray().put(JSONObject().put("role", "system").put("content", Prompts.chatSystem(style(st))))
            val ctxTurns = synchronized(lock) { trimmed() }
            for ((r, t) in ctxTurns) msgs.put(JSONObject().put("role", r).put("content", t))
            msgs.put(JSONObject().put("role", "user").put("content", q))
            val chosen = st.str("chatModel")
            val answer = Engine.withRetry(app, onStatus) { completeAny(st, key, "chatModel", Models.textChain(chosen), msgs, 2048) }
            synchronized(lock) {
                turns += "user" to q
                turns += "assistant" to answer
                while (turns.size > MAX_TURNS) turns.removeAt(0)
            }
            answer
        }
    }

    /** Newest turns that fit in MAX_CHARS, oldest first. */
    private fun trimmed(): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        var total = 0
        for (i in turns.indices.reversed()) {
            val t = turns[i]
            total += t.second.length
            if (total > MAX_CHARS) break
            out.add(0, t)
        }
        return out
    }

    /** Text read from the screen -> language + meaning in the user's language. */
    fun understandText(ctx: Context, screen: String, onStatus: (String) -> Unit, done: (Boolean, String) -> Unit) {
        val app = ctx.applicationContext
        run(app, done) { st, key ->
            val msgs = JSONArray()
                .put(JSONObject().put("role", "system").put("content", Prompts.screenText(style(st))))
                .put(JSONObject().put("role", "user").put("content", "<screen>\n$screen\n</screen>"))
            Engine.withRetry(app, onStatus) { completeAny(st, key, "llm", Models.textChain(st.str("llm")), msgs, 1500) }
        }
    }

    /** Screenshot -> OCR + meaning with a vision model (Qwen 3.8 / 3.6, free plan). */
    fun understandImage(ctx: Context, jpegB64: String, onStatus: (String) -> Unit, done: (Boolean, String) -> Unit) {
        val app = ctx.applicationContext
        run(app, done) { st, key ->
            val msgs = JSONArray().put(Groq.imageMessage(Prompts.screenImage(style(st)), jpegB64))
            val chosen = st.str("visionModel")
            Engine.withRetry(app, onStatus) { completeAny(st, key, "visionModel", Models.visionChain(chosen), msgs, 2048) }
        }
    }

    /** Voice -> text for the chat box (same language rules as dictation, no rewriting). */
    fun transcribe(ctx: Context, f: File, onStatus: (String) -> Unit, done: (Boolean, String) -> Unit) {
        val app = ctx.applicationContext
        run(app, done) { st, key ->
            val audio = try { f.readBytes() } finally { f.delete() }
            val lang = st.str("lang")
            val raw = Engine.withRetry(app, onStatus) { Engine.transcribeAny(st, key, audio, lang, Engine.whisperPrompt(st, lang)) }
            if (raw.isBlank() || Engine.isWhisperNoise(raw)) throw GroqError("Didn't catch that. Try again.", false)
            val roman = lang == "hinglish" || ((lang == "auto" || lang == "en") && !st.bool("deva"))
            val t = if (roman) Engine.forceRoman(app, st, key, raw, onStatus) else raw
            st.applySpell(t)
        }
    }
}
