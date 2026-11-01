/*
 * Wispr by Deepu Gupta
 * Copyright 2026 Deepu Gupta
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 * SPDX-License-Identifier: Apache-2.0
 */
package com.deepugupta.wispr

import android.content.Context
import android.content.res.Configuration
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/** Settings + history, always encrypted at rest. Shared by the app screen and the keyboard bubble. */
class Store private constructor(private val app: Context) {
    private val sp = app.getSharedPreferences("wispr_secure_v1", Context.MODE_PRIVATE)
    val audioDir: File = File(app.filesDir, "audio").apply { mkdirs() }
    private val lock = Any()
    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val settings: JSONObject = readEnc("s")?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject()
    private var history: JSONArray = readEnc("h")?.let { runCatching { JSONArray(it) }.getOrNull() } ?: JSONArray()

    init { migrate() }

    /** One-time fixes for settings saved by older versions. */
    private fun migrate() {
        synchronized(lock) {
            var changed = false
            // Groq shut down Llama 3.x on 16 Aug 2026: every Polish/Hinglish call was failing silently.
            for (k in listOf("llm", "chatModel")) {
                if (settings.optString(k) in Models.DEAD) { settings.put(k, Models.DEFAULT_LLM); changed = true }
            }
            if (settings.optString("stt") in Models.DEAD) { settings.put("stt", Models.DEFAULT_STT); changed = true }
            if (settings.has("visionModel") && !Models.isVision(settings.optString("visionModel"))) {
                settings.put("visionModel", Models.DEFAULT_VISION); changed = true
            }
            if (settings.optInt("schema", 0) < 3) {
                // v2 "Hindi + roman" meant Hinglish; v2 "Hindi + as spoken" meant Devanagari.
                if (settings.optString("lang") == "hi") {
                    if (settings.optString("script", "roman") == "native") settings.put("deva", true)
                    else settings.put("lang", "hinglish")
                }
                settings.remove("script")
                settings.put("schema", 3)
                changed = true
            }
            if (settings.optInt("schema", 0) < 4) {
                // v4: Whisper Large v3 by default (v3 used Turbo, which mishears fast speech more often).
                if (settings.optString("stt", Models.FAST_STT) == Models.FAST_STT) settings.put("stt", Models.DEFAULT_STT)
                settings.put("schema", 4)
                changed = true
            }
            if (changed) writeEnc("s", settings.toString())
        }
    }

    private fun readEnc(k: String): String? = sp.getString(k, null)?.let { runCatching { Crypto.decStr(it) }.getOrNull() }
    private fun writeEnc(k: String, v: String) { sp.edit().putString(k, Crypto.encStr(v)).apply() }

    fun str(k: String): String = synchronized(lock) {
        if (settings.has(k)) settings.optString(k) else (DEF[k] as? String ?: "")
    }

    fun bool(k: String): Boolean = synchronized(lock) {
        if (settings.has(k)) settings.optBoolean(k) else (DEF[k] as? Boolean ?: false)
    }

    fun put(k: String, v: Any) {
        synchronized(lock) {
            settings.put(k, v)
            writeEnc("s", settings.toString())
        }
        fire()
    }

    var apiKey: String
        get() = str("key")
        set(v) = put("key", v)

    fun isDark(): Boolean = synchronized(lock) {
        if (settings.has("dark")) settings.optBoolean("dark")
        else (app.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    }

    /** What the app screen may see. The API key itself is never included. */
    fun publicJson(): String {
        val o = JSONObject()
        synchronized(lock) {
            for ((k, v) in DEF) if (k != "key") o.put(k, if (settings.has(k)) settings.get(k) else v)
        }
        o.put("dark", isDark())
        val key = apiKey
        o.put("hasKey", key.isNotBlank())
        o.put("keyHint", if (key.length > 8) "gsk_…" + key.takeLast(4) else "")
        o.put("version", BuildConfig.VERSION_NAME)
        o.put("owner", Owner.NAME)
        return o.toString()
    }

    /** Whitelisted and type-checked. The UI can never write the key through this. */
    fun putFromUi(k: String, v: Any?): Boolean {
        if (k == "key") return false
        val def = DEF[k] ?: return false
        return when {
            def is Boolean && v is Boolean -> { put(k, v); true }
            def is String && v is String && v.length <= 20000 -> { put(k, v); true }
            else -> false
        }
    }

    /** Correct spellings from the Spellings list ("heard = correct" -> "correct"), used as Whisper vocabulary. */
    fun vocabulary(): List<String> = str("spell").split("\n").mapNotNull { line ->
        val i = line.indexOf('=')
        val w = (if (i >= 0) line.substring(i + 1) else line).trim()
        w.takeIf { it.isNotEmpty() && it.length <= 40 }
    }.distinct()

    fun applySpell(text: String): String {
        var t = text
        for (line in str("spell").split("\n")) {
            val i = line.indexOf('=')
            if (i <= 0) continue
            val a = line.substring(0, i).trim()
            val b = line.substring(i + 1).trim()
            if (a.isEmpty()) continue
            t = t.replace(Regex(Regex.escape(a), RegexOption.IGNORE_CASE), Regex.escapeReplacement(b))
        }
        return t
    }

    // ---------- history ----------
    fun historyJson(): String = synchronized(lock) {
        val out = JSONArray()
        for (i in 0 until history.length()) {
            val o = history.getJSONObject(i)
            out.put(
                JSONObject().put("id", o.optString("id")).put("t", o.optLong("t"))
                    .put("text", o.optString("text")).put("status", o.optString("status"))
                    .put("err", o.optString("err")).put("mode", o.optString("mode"))
            )
        }
        out.toString()
    }

    private fun find(id: String): JSONObject? {
        for (i in 0 until history.length()) {
            val o = history.getJSONObject(i)
            if (o.optString("id") == id) return o
        }
        return null
    }

    fun item(id: String): JSONObject? = synchronized(lock) { find(id)?.let { JSONObject(it.toString()) } }

    fun addItem(o: JSONObject) {
        synchronized(lock) {
            val a = JSONArray().put(o)
            for (i in 0 until history.length()) a.put(history.get(i))
            while (a.length() > MAX) {
                val last = a.getJSONObject(a.length() - 1)
                File(audioDir, last.optString("id") + ".enc").delete()
                a.remove(a.length() - 1)
            }
            history = a
            writeEnc("h", history.toString())
        }
        fire()
    }

    fun patchItem(id: String, fn: (JSONObject) -> Unit) {
        synchronized(lock) {
            val o = find(id) ?: return
            fn(o)
            writeEnc("h", history.toString())
        }
        fire()
    }

    fun removeItem(id: String) {
        synchronized(lock) {
            val a = JSONArray()
            for (i in 0 until history.length()) {
                val o = history.getJSONObject(i)
                if (o.optString("id") != id) a.put(o)
            }
            history = a
            writeEnc("h", history.toString())
        }
        File(audioDir, "$id.enc").delete()
        fire()
    }

    fun clearHistory() {
        synchronized(lock) {
            history = JSONArray()
            writeEnc("h", "[]")
        }
        audioDir.listFiles()?.forEach { it.delete() }
        fire()
    }

    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }
    private fun fire() { main.post { for (l in listeners) l() } }

    companion object {
        const val MAX = 40
        val DEF: Map<String, Any> = linkedMapOf<String, Any>(
            "key" to "", "mode" to "polish", "lang" to "hinglish", "deva" to false, "target" to "en",
            "stt" to Models.DEFAULT_STT, "llm" to Models.DEFAULT_LLM,
            "chatModel" to Models.DEFAULT_LLM, "visionModel" to Models.DEFAULT_VISION,
            "hold" to "panel", "explainIn" to "hinglish", "fixWrite" to true,
            "autoCopy" to true, "keepHist" to true, "sounds" to false, "spell" to "",
            "dark" to false, "bubble" to true,
            "updates" to "auto"
        )

        @Volatile private var inst: Store? = null
        fun get(ctx: Context): Store = inst ?: synchronized(this) {
            inst ?: Store(ctx.applicationContext).also { inst = it }
        }
    }
}
