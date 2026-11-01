/*
 * Wispr by Deepu Gupta
 * Copyright 2026 Deepu Gupta
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 * SPDX-License-Identifier: Apache-2.0
 */
package com.deepugupta.wispr

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * @param retryable    network / rate limit / server busy: worth trying again
 * @param auth         the key itself was refused
 * @param modelGone    the model id no longer exists on Groq
 * @param badParam     Groq refused an optional request field; retry without it
 * @param retryAfterMs how long Groq asked us to wait (429), 0 if unknown
 */
class GroqError(
    message: String,
    val retryable: Boolean,
    val auth: Boolean = false,
    val modelGone: Boolean = false,
    val badParam: Boolean = false,
    val code: Int = 0,
    val retryAfterMs: Long = 0
) : Exception(message)

/** Talks to Groq over HTTPS only. The key is sent in a header and is never logged. */
object Groq {
    private const val BASE = "https://api.groq.com/openai/v1/"
    private val JSON_TYPE = "application/json".toMediaType()

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .callTimeout(180, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    /**
     * Cleans whatever the user pasted: spaces/new lines in the middle, zero-width characters,
     * quotes, a leading "Bearer ", or a markdown-escaped "gsk\_" (copied from a chat app).
     */
    fun cleanKey(input: String): String {
        var k = input.replace("\\_", "_")
        k = k.replace(Regex("[\\s\\u200B-\\u200F\\u2060\\uFEFF\\u00A0\"'`“”‘’<>]"), "")
        if (k.startsWith("Bearer", ignoreCase = true)) k = k.substring(6)
        val i = k.indexOf("gsk_")
        if (i > 0) k = k.substring(i)
        return k.filter { it.code in 33..126 }
    }

    /** "2", "1.5", "7m2.5s", "300ms" -> milliseconds (Groq uses all of these). */
    private fun parseWait(v: String?): Long {
        if (v.isNullOrBlank()) return 0
        v.trim().toDoubleOrNull()?.let { return (it * 1000).toLong() }
        var ms = 0.0
        Regex("([0-9.]+)(ms|h|m|s)").findAll(v).forEach { m ->
            val n = m.groupValues[1].toDoubleOrNull() ?: 0.0
            ms += when (m.groupValues[2]) { "ms" -> n; "s" -> n * 1000; "m" -> n * 60_000; else -> n * 3_600_000 }
        }
        return ms.toLong()
    }

    private fun exec(req: Request): String {
        var wait = 0L
        val (code, body) = try {
            http.newCall(req).execute().use { r ->
                wait = parseWait(r.header("retry-after"))
                r.code to r.body.string()
            }
        } catch (e: IOException) {
            throw GroqError("Couldn't reach Groq. Check your internet.", true)
        }
        if (code in 200..299) return body
        val err = runCatching { JSONObject(body).getJSONObject("error") }.getOrNull()
        val apiMsg = err?.optString("message")?.takeIf { it.isNotBlank() }
        val apiCode = err?.optString("code").orEmpty()
        val low = (apiMsg ?: "").lowercase()
        val modelIssue = apiCode.contains("model") || (low.contains("model") &&
            (low.contains("decommission") || low.contains("does not exist") || low.contains("not found") ||
                low.contains("no longer") || low.contains("not have access") || low.contains("not supported")))
        throw when {
            code == 401 -> GroqError("Groq didn't accept this key (401). ${apiMsg ?: "It may be deleted or mistyped."} Create a new one at console.groq.com/keys.", false, auth = true, code = code)
            code == 403 -> GroqError("Groq blocked the request (403). ${apiMsg ?: "Your account or network may be restricted."} Turn off VPN / try mobile data.", false, auth = true, code = code)
            code == 429 && (low.contains("per day") || low.contains("tpd") || low.contains("rpd")) ->
                GroqError("Today's free Groq limit for this model is used up. Pick another model (AI models) or try tomorrow.", false, code = code, retryAfterMs = wait)
            code == 429 -> GroqError("Groq free-plan limit hit, waiting a moment…", true, code = code, retryAfterMs = wait)
            code == 413 -> GroqError("Too big for Groq. Try a shorter recording / smaller text.", false, code = code)
            code == 408 || code in 500..599 -> GroqError("Groq is busy right now.", true, code = code)
            (code == 404 || code == 400) && modelIssue -> GroqError(apiMsg ?: "Model not available on Groq.", false, modelGone = true, code = code)
            code == 400 && (low.contains("reasoning") || low.contains("property") || low.contains("unsupported") || low.contains("unknown")) ->
                GroqError(apiMsg ?: "Groq rejected an option.", false, badParam = true, code = code)
            else -> GroqError(apiMsg ?: "Groq error $code", false, code = code)
        }
    }

    private fun get(key: String, path: String): String =
        exec(Request.Builder().url(BASE + path).header("Authorization", "Bearer $key").get().build())

    private fun postJson(key: String, path: String, body: JSONObject): String =
        exec(Request.Builder().url(BASE + path).header("Authorization", "Bearer $key").post(body.toString().toRequestBody(JSON_TYPE)).build())

    fun verify(key: String) { get(key, "models") }

    /** Ids of every model this key can use right now. */
    fun listModels(key: String): Set<String> {
        val data = runCatching { JSONObject(get(key, "models")).getJSONArray("data") }.getOrNull()
            ?: throw GroqError("Groq sent a bad model list.", true)
        val out = HashSet<String>()
        for (i in 0 until data.length()) {
            val o = data.optJSONObject(i) ?: continue
            if (o.optBoolean("active", true)) o.optString("id").takeIf { it.isNotBlank() }?.let { out += it }
        }
        return out
    }

    fun transcribe(key: String, audio: ByteArray, model: String, lang: String, prompt: String?): String {
        val b = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", "speech.m4a", audio.toRequestBody("audio/mp4".toMediaType()))
            .addFormDataPart("model", model)
            .addFormDataPart("response_format", "json")
            .addFormDataPart("temperature", "0")
        // Whisper has no Odia code; "hinglish" is sent as Hindi (best recognition) and romanised afterwards.
        val wl = when (lang) { "auto", "or" -> null; "hinglish" -> "hi"; else -> lang }
        if (wl != null) b.addFormDataPart("language", wl)
        if (!prompt.isNullOrBlank()) b.addFormDataPart("prompt", prompt)
        val req = Request.Builder().url(BASE + "audio/transcriptions")
            .header("Authorization", "Bearer $key").post(b.build()).build()
        val body = exec(req)
        return try { JSONObject(body).optString("text").trim() } catch (e: Exception) { throw GroqError("Groq sent a bad reply.", true) }
    }

    /** One transcription segment as the workspace stores it. */
    class Seg(val startMs: Long, val text: String, val logProb: Double)

    /** Rich transcription result: text + auto-detected language + optional per-segment detail. */
    class Trans(val text: String, val lang: String, val segments: List<Seg>)

    /**
     * Transcription with verbose_json: adds auto-detected language and timed segments
     * (avg_logprob when Groq sends it) for the transcript workspace's confidence view.
     * Falls back to plain [transcribe] when Groq refuses response_format=verbose_json.
     */
    fun transcribeRich(key: String, audio: ByteArray, model: String, lang: String, prompt: String?): Trans {
        val plain = { transcribe(key, audio, model, lang, prompt) }
        return try {
            val b = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("file", "speech.wav", audio.toRequestBody("audio/wav".toMediaType()))
                .addFormDataPart("model", model)
                .addFormDataPart("response_format", "verbose_json")
                .addFormDataPart("temperature", "0")
            val wl = when (lang) { "auto", "or" -> null; "hinglish" -> "hi"; else -> lang }
            if (wl != null) b.addFormDataPart("language", wl)
            if (!prompt.isNullOrBlank()) b.addFormDataPart("prompt", prompt)
            val req = Request.Builder().url(BASE + "audio/transcriptions")
                .header("Authorization", "Bearer $key").post(b.build()).build()
            val o = JSONObject(exec(req))
            val text = o.optString("text").trim()
            val segs = ArrayList<Seg>()
            val arr = o.optJSONArray("segments")
            if (arr != null) for (i in 0 until arr.length()) {
                val s = arr.optJSONObject(i) ?: continue
                val stv = s.optDouble("start", Double.NaN)
                val st = if (stv.isNaN()) -1L else (stv * 1000).toLong() // float seconds -> ms
                segs.add(Seg(st, s.optString("text").trim(), s.optDouble("avg_logprob", 0.0)))
            }
            Trans(text, o.optString("language").ifBlank { lang }, segs)
        } catch (e: GroqError) {
            if (e.badParam) Trans(plain(), lang, emptyList()) else throw e
        }
    }

    /** Dictation clean-up / translation: one system prompt + the dictated text. */
    fun chat(key: String, model: String, system: String, text: String): String {
        val msgs = JSONArray()
            .put(JSONObject().put("role", "system").put("content", system))
            .put(JSONObject().put("role", "user").put("content", "<dictation>\n$text\n</dictation>"))
        // Free plan = 8K tokens/min: never reserve more output than this text can need.
        val maxOut = (text.length * 2 + 512).coerceIn(512, 4096)
        var out = complete(key, model, msgs, 0.2, maxOut)
        out = out.replace(Regex("</?dictation>"), "").trim()
        val quoted = out.length > 1 && (out.first() == '"' || out.first() == '“') && (out.last() == '"' || out.last() == '”')
        if (quoted && !(text.startsWith("\"") || text.startsWith("“"))) out = out.substring(1, out.length - 1).trim()
        if (out.isEmpty()) throw GroqError("Groq sent an empty reply.", true)
        return out
    }

    /**
     * Any chat/completions call (AI chat, screen explain, vision).
     * Sends the "think briefly" options first and retries without them if a model refuses them.
     */
    fun complete(key: String, model: String, messages: JSONArray, temperature: Double, maxTokens: Int): String = try {
        completeOnce(key, model, messages, temperature, maxTokens, extras = true)
    } catch (e: GroqError) {
        if (e.badParam) completeOnce(key, model, messages, temperature, maxTokens, extras = false) else throw e
    }

    private fun completeOnce(key: String, model: String, messages: JSONArray, temperature: Double, maxTokens: Int, extras: Boolean): String {
        val p = JSONObject().put("model", model).put("messages", messages)
            .put("temperature", temperature).put("max_completion_tokens", maxTokens)
        if (extras) {
            // gpt-oss "thinks" first: keep it short so replies feel instant. Qwen: hide its <think> block.
            if (model.startsWith("openai/gpt-oss")) p.put("reasoning_effort", "low")
            if (model.startsWith("qwen/")) p.put("reasoning_format", "hidden")
        }
        val body = postJson(key, "chat/completions", p)
        val out = try {
            JSONObject(body).getJSONArray("choices").getJSONObject(0).getJSONObject("message").optString("content")
        } catch (e: Exception) { throw GroqError("Groq sent a bad reply.", true) }
        val clean = out.replace(Regex("<think>[\\s\\S]*?</think>"), "").trim()
        if (clean.isEmpty()) throw GroqError("Groq sent an empty reply.", true)
        return clean
    }

    /** Message with one image (JPEG, base64) for the vision models. */
    fun imageMessage(prompt: String, jpegBase64: String): JSONObject = JSONObject().put("role", "user").put(
        "content", JSONArray()
            .put(JSONObject().put("type", "text").put("text", prompt))
            .put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", "data:image/jpeg;base64,$jpegBase64")))
    )
}
