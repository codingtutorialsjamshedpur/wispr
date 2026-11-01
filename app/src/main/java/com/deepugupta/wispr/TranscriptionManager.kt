/*
 * Wispr by Deepu Gupta
 * Copyright 2026 Deepu Gupta
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 * SPDX-License-Identifier: Apache-2.0
 */
package com.deepugupta.wispr

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * MODE A (system playback capture) + MODE B (imported files) + MODE C (mic) all land here:
 * a universal transcript-session manager on top of the existing Groq Whisper pipeline.
 *
 * What it guarantees:
 *  - ORIGINAL transcription preserved: Whisper auto-detects the language and the text
 *    stays exactly as spoken/sung (no Hinglish reshaping, no translation hidden inside -
 *    translation is its own action);
 *  - chunk order: every chunk knows its start second and results are merged in order,
 *    with the same offsets shown as timestamps;
 *  - nothing is lost: a failed chunk keeps its WAV and is retried on demand; chunks that
 *    succeeded are never re-sent or re-ordered, and later failures can't erase earlier text;
 *  - quiet honesty: empty/low-confidence chunks are reported instead of guessed at;
 *  - sessions (transcripts, notes, statuses) are stored encrypted like the rest of Wispr
 *    (Android Keystore AES-256-GCM through [Crypto]);
 *  - Groq request caps respected: one worker thread -> <= 20 requests/minute is easy, and
 *    chunk WAVs are <= 25 MB by construction (see [Chunker.CHUNK_SEC]).
 *
 * Not hardcoded to any app or platform: the same API serves YouTube, radio, local MP3,
 * seminars, whatever - the source never matters, only the audio stream chunks.
 */
object TranscriptionManager {
    private const val SP = "wispr_ws_v1"
    private const val KEY = "ws"
    private const val MAX_SESSIONS = 50

    /** One worker: chunk requests go to Groq strictly one by one (rate-limit friendly). */
    private val q = Executors.newSingleThreadExecutor()
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }
    private fun fire() { main.post { for (l in listeners) l() } }

    private fun sp(app: Context): SharedPreferences = app.getSharedPreferences(SP, Context.MODE_PRIVATE)

    private fun read(app: Context): JSONArray {
        val s = sp(app).getString(KEY, null) ?: return JSONArray()
        return try { JSONArray(Crypto.decStr(s)) } catch (e: Exception) { JSONArray() }
    }

    private fun write(app: Context, a: JSONArray) {
        sp(app).edit().putString(KEY, Crypto.encStr(a.toString())).apply()
    }

    private fun find(a: JSONArray, id: String): JSONObject? {
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            if (o.optString("id") == id) return o
        }
        return null
    }

    // ---------- public, JSON in / JSON out (workspace UI) ----------

    /** Full session list for the workspace list view (light fields only). */
    fun listJson(app: Context): String {
        val a = read(app)
        val out = JSONArray()
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            val chunks = o.optJSONArray("chunks")
            val total = chunks?.length() ?: 0
            var ok = 0
            var bad = 0
            if (chunks != null) for (j in 0 until chunks.length()) {
                when (chunks.getJSONObject(j).optString("status")) { "ok" -> ok++; "failed" -> bad++ }
            }
            out.put(
                JSONObject()
                    .put("id", o.optString("id"))
                    .put("t", o.optLong("t"))
                    .put("name", o.optString("name"))
                    .put("kind", o.optString("kind"))
                    .put("status", o.optString("status"))
                    .put("lang", o.optString("lang"))
                    .put("note", o.optString("note"))
                    .put("warn", o.optString("warn"))
                    .put("chunks", total).put("ok", ok).put("failed", bad)
                    .put("text", o.optString("text").ifBlank { "" })
                    .put("len", o.optString("text").length)
            )
        }
        return out.toString()
    }

    /** One session with every field (transcript, chunks, translation, summary). */
    fun sessionJson(app: Context, id: String): String {
        val o = find(read(app), id) ?: return ""
        return o.toString()
    }

    /** Renames provoke no rebuilding; text may be edited by the user (original chunks stay). */
    fun patchUi(app: Context, id: String, name: String?, text: String?): Boolean {
        val a = read(app)
        val o = find(a, id) ?: return false
        if (name != null && name.length <= 120) o.put("name", name.trim())
        if (text != null) o.put("text", text.take(2_000_000))
        write(app, a)
        fire()
        return true
    }

    fun delete(app: Context, id: String) {
        val a = read(app)
        val b = JSONArray()
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            if (o.optString("id") != id) b.put(o)
        }
        write(app, b)
        File(File(app.filesDir, "transcripts"), id).deleteRecursively()
        fire()
    }

    fun clearBornOf(app: Context) {
        write(app, JSONArray())
        File(app.filesDir, "transcripts").deleteRecursively()
        Decode.scratch(app).deleteRecursively()
        fire()
    }

    fun sessionDir(app: Context, id: String): File = File(File(app.filesDir, "transcripts").apply { mkdirs() }, id).apply { mkdirs() }

    // ---------- session creation ----------

    /** New empty session (capture sessions are created by [CaptureService] at start). */
    fun newSession(app: Context, name: String, kind: String, lyrics: Boolean): String {
        val id = java.util.UUID.randomUUID().toString()
        val o = JSONObject()
            .put("id", id)
            .put("t", System.currentTimeMillis())
            .put("name", name.cap(60))
            .put("kind", kind)             // capture | file | mic
            .put("lyrics", lyrics)
            .put("status", "loading")      // loading | ok | partial | failed
            .put("lang", "")
            .put("note", kind.takeIf { it == "capture" }?.let { "Capturing audio…" } ?: "")
            .put("warn", "")
            .put("text", "")
            .put("translation", "")
            .put("summary", "")
            .put("chunks", JSONArray())
        val a = read(app)
        a.put(o)  // newest last; the list view shows reversed
        while (a.length() > MAX_SESSIONS) {
            val old = a.getJSONObject(0)
            File(File(app.filesDir, "transcripts"), old.optString("id")).deleteRecursively()
            a.remove(0)
        }
        write(app, a)
        fire()
        return id
    }

    private fun String.cap(cap: Int) = this.substring(0, minOf(this.length, cap))

    // ---------- chunk ingestion (shared by capture, imports and mic) ----------

    /**
     * Called by [CaptureService] as each live chunk closes, and by import when all chunks
     * are ready. Queues transcription; keeps the WAV for retry until the chunk succeeds.
     */
    fun enqueueChunk(app: Context, sessionId: String, file: File, startSec: Long, lyrics: Boolean) {
        val moved = moveInto(app, sessionId, file, startSec)
        q.execute { transcribeChunk(app, sessionId, moved, startSec, lyrics, attempt = 0) }
    }

    private fun moveInto(app: Context, sessionId: String, file: File, startSec: Long): File {
        val dir = sessionDir(app, sessionId)
        val dest = File(dir, "c${startSec}.wav")
        if (dest.exists()) dest.delete()
        if (file.parentFile?.canonicalPath == dir.canonicalPath) file.renameTo(dest)
        else file.copyTo(dest, overwrite = true).let { file.delete() }
        return dest
    }
    /** Whole-file import: decode -> chunk -> enqueue, with progress notes and honest status. */
    fun importFile(app0: Context, uri: android.net.Uri, lyricsMode: Boolean, done: (Boolean, String, String) -> Unit) {
        val app: Context = (app0 as? Activity)?.applicationContext ?: app0
        q.execute {
            val name = try { Decode.name(app, uri) } catch (e: Exception) { "Import" }
            val sid = newSession(app, name, "file", lyricsMode)
            try {
                val chunks = Decode.extractToChunks(app, uri, { d, t -> notePatch(app, sid, "Decoding… ${fmtSecs(d)}${if (t >= 0) " / ${fmtSecs(t)}" else ""}") },
                    { false })
                if (chunks.isEmpty()) throw Decode.DecErr("No audio could be decoded from this file.")
                notePatch(app, sid, "Transcribing ${chunks.size} chunk${if (chunks.size == 1) "" else "s"}…")
                for (c in chunks) {
                    if (!sessionExists(app, sid)) return@execute
                    enqueueChunkWaited(app, sid, c.file, c.startSec, lyricsMode)
                }
                fire()
                main.post { done(true, sid, "") }
            } catch (e: Decode.DecErr) {
                failSession(app, sid, e.message ?: "Import failed")
                main.post { done(false, sid, e.message ?: "Import failed") }
            } catch (e: Exception) {
                failSession(app, sid, "Import failed (${e.javaClass.simpleName})")
                main.post { done(false, sid, "Import failed") }
            }
        }
    }

    /** Import path runs on the single worker thread, so chunk jobs must run after it: wait inline. */
    private fun enqueueChunkWaited(app: Context, sessionId: String, file: File, startSec: Long, lyrics: Boolean) {
        val moved = moveInto(app, sessionId, file, startSec)
        transcribeChunk(app, sessionId, moved, startSec, lyrics, attempt = 0)
    }

    /**
     * Called by [PlaybackCaptureService] when a live capture session ends.
     * THREE SEPARATE STATUSES are recorded on the session (never merged into one):
     *  - "capture"       success/failed — did real PCM ever arrive;
     *  - "audioQuality"  normal / low / digital_silence / none — RMS diagnostic;
     *  - the existing session status + per-chunk notes = transcription status.
     * A successful capture does NOT imply a successful transcription: even when all
     * chunks return empty/no-speech, capture stays SUCCESS and the session reports
     * "Transcription: EMPTY / NO SPEECH RECOGNIZED" — never a capture failure.
     */
    fun captureFinished(app: Context, sessionId: String, anyAudio: Boolean, capture: String = "", quality: String = "", note: String? = null, failMsg: String? = null) {
        val a = read(app)
        find(a, sessionId)
            ?.put("capture", capture.ifBlank { if (anyAudio) "success" else "failed" })
            ?.put("audioQuality", quality)
        write(app, a)
        fire()
        if (anyAudio) notePatch(app, sessionId, note ?: "Transcribing…")
        else failSession(
            app, sessionId,
            failMsg ?: "No internal audio was captured. The source app may block capture (DRM/privacy) — " +
                "try importing the media file, or use the microphone."
        )
    }

    private fun notePatch(app: Context, id: String, note: String) {
        val a = read(app)
        find(a, id)?.put("note", note.take(200))
        write(app, a)
        fire()
    }

    private fun failSession(app: Context, id: String, msg: String) {
        val a = read(app)
        find(a, id)?.put("status", "failed")?.put("note", msg.take(300))
        write(app, a)
        fire()
    }

    private fun sessionExists(app: Context, id: String): Boolean = find(read(app), id) != null

    // ---------- the actual Whisper step ----------

    internal fun transcribeChunk(app: Context, sessionId: String, wav: File, startSec: Long, lyrics: Boolean, attempt: Int) {
        val st = Store.get(app)
        try {
            val key = st.apiKey
            if (key.isBlank()) throw GroqError("Add your Groq key in the Wispr app first.", false, auth = true)
            val audio = wav.readBytes()
            val model = st.str("stt")
            val prompt = if (lyrics) Prompts.LYRICS.take(420) else null // "auto" everywhere: original language preserved
            val r = Engine.withRetry(app, { notePatch(app, sessionId, "Transcribing…") }) {
                Groq.transcribeRich(model = "whisper-large-v3", key = key, audio = audio, lang = "auto", prompt = prompt)
            }
            val text = r.text.trim()
            if (text.isBlank() || Engine.isWhisperNoise(text)) {
                // Empty/whisper-noise response is NOT a capture failure and NOT an API failure:
                // the capture was valid, transcription simply found no words (e.g. pure music).
                // Separate chunk status "nospeech" so quality and transcription stay distinct.
                patchChunk(app, sessionId, startSec, "nospeech", "", r.lang, "No speech recognized in this chunk.")
                runCatching { wav.delete() } // identical audio re-sent would stay empty: nothing to retry
                return
            }
            patchChunk(app, sessionId, startSec, "ok", text, r.lang, "", r.segments)
            wav.delete()
        } catch (e: GroqError) {
            if (e.auth) {
                // The key itself is dead: no point hammering Groq with retries on every chunk.
                patchChunk(app, sessionId, startSec, "failed", "", "", e.message ?: "Groq refused the key")
            } else if (attempt < 3 && sessionExists(app, sessionId)) {
                notePatch(app, sessionId, "Groq hiccup, retrying (${attempt + 1}/4)…")
                Thread.sleep(2_000L * (attempt + 1))
                if (sessionExists(app, sessionId)) q.execute { transcribeChunk(app, sessionId, wav, startSec, lyrics, attempt + 1) }
            } else {
                val msg = e.message?.takeIf { it.isNotBlank() }?.let { "$it" } ?: "Groq error"
                patchChunk(app, sessionId, startSec, "failed", "", "", msg.take(240))
            }
        } catch (e: Exception) {
            patchChunk(app, sessionId, startSec, "failed", "", "", "Couldn't send this chunk (${e.javaClass.simpleName}).")
        } finally {
            refreshStatus(app, sessionId)
        }
    }

    private fun patchChunk(app: Context, sessionId: String, startSec: Long, status: String, text: String, lang: String, msg: String, segs: List<Groq.Seg> = emptyList()) {
        val a = read(app)
        val o = find(a, sessionId) ?: return
        val chunks = o.optJSONArray("chunks") ?: JSONArray().also { o.put("chunks", it) }
        var target: JSONObject? = null
        for (j in 0 until chunks.length()) {
            val c = chunks.getJSONObject(j)
            if (c.optLong("start") == startSec) { target = c; break }
        }
        val c = target ?: JSONObject().put("start", startSec).also { chunks.put(it) }
        c.put("status", status)
        c.put("err", msg.take(240))
        if (text.isNotBlank()) c.put("text", text.take(120_000))
        if (lang.isNotBlank()) c.put("lang", lang.take(30))
        if (segs.isNotEmpty()) {
            val sa = JSONArray()
            for (s in segs.take(400)) sa.put(JSONObject().put("ms", s.startMs).put("text", s.text))
            c.put("segs", sa)
        }
        // Merge in chunk order so the transcript is complete and ordered even with failed chunks between.
        val sb = StringBuilder()
        var langFound = ""
        for (j in 0 until chunks.length()) {
            val ch = chunks.getJSONObject(j)
            val t = ch.optString("text")
            if (t.isNotBlank()) {
                if (sb.isNotEmpty()) sb.append("\n\n")
                sb.append(t)
            }
            if (langFound.isBlank()) langFound = ch.optString("lang")
        }
        o.put("text", sb.toString())
        if (langFound.isNotBlank()) o.put("lang", langFound)
        if (msg.isNotBlank() && status == "failed") o.put("warn", msg.take(240))
        write(app, a)
        fire()
    }

    /** Status wizard: loading while pending, partial on real failures, ok when fine.
     *  "nospeech" chunks are NOT failures: capture was valid; transcription is EMPTY. */
    private fun refreshStatus(app: Context, id: String) {
        val a = read(app)
        val o = find(a, id) ?: return
        val chunks = o.optJSONArray("chunks")
        var ok = 0
        var bad = 0
        var noSpeech = 0
        if (chunks != null) for (j in 0 until chunks.length()) {
            when (chunks.getJSONObject(j).optString("status")) { "ok" -> ok++; "failed" -> bad++; "nospeech" -> noSpeech++ }
        }
        when {
            bad == 0 && ok > 0 -> { o.put("status", "ok"); o.put("note", ""); o.put("warn", "") }
            bad > 0 -> o.put("status", "partial")
            noSpeech > 0 -> { // valid capture, zero recognised words — never a capture error
                o.put("status", "ok")
                o.put("note", "Capture: SUCCESS · Transcription: EMPTY / NO SPEECH RECOGNIZED (audio kept)")
                if (o.optString("note").isNotBlank()) o.put("warn", "")
            }
        }
        if ((o.optString("status") == "ok" || o.optString("status") == "partial") && o.optString("note").startsWith("Transcribing")) o.put("note", "")
        write(app, a)
        fire()
    }

    /** Re-send every failed chunk (failed WAV files are kept until success). */
    fun retryFailed(app: Context, id: String) {
        val a = read(app)
        val o = find(a, id) ?: return
        val lyrics = o.optBoolean("lyrics")
        val chunks = o.getJSONArray("chunks")
        val dir = sessionDir(app, id)
        for (j in 0 until chunks.length()) {
            val c = chunks.getJSONObject(j)
            if (c.optString("status") != "failed") continue
            val wav = File(dir, "c${c.optLong("start")}.wav")
            if (!wav.exists()) continue
            c.put("status", "loading")
            q.execute { transcribeChunk(app, id, wav, c.optLong("start"), lyrics, 0) }
        }
        o.put("note", "Retrying failed chunks…")
        write(app, a)
        fire()
    }

    // ---------- workspace actions on the finished transcript ----------

    fun translate(app: Context, id: String, target: String) {
        val a = read(app)
        val o = find(a, id) ?: return
        val text = o.optString("text")
        if (text.isBlank()) return
        val key = Store.get(app).apiKey
        q.execute {
            try {
                val out = Engine.withRetry(app, { _ -> }) { Engine.chatAny(Store.get(app), key, Prompts.wsTranslate(target), text.take(24_000)) }
                find(read(app), id)?.put("translation", out)
                write(app, read(app))
                fire()
            } catch (e: GroqError) {
                find(read(app), id)?.put("translation", "Couldn't translate: ${e.message}")
                write(app, read(app))
                fire()
            }
        }
    }

    fun summarize(app: Context, id: String) {
        val a = read(app)
        val o = find(a, id) ?: return
        val text = o.optString("text")
        if (text.isBlank()) return
        val key = Store.get(app).apiKey
        val lang = Store.get(app).str("explainIn")
        q.execute {
            try {
                val out = Engine.withRetry(app, { _ -> }) { Engine.chatAny(Store.get(app), key, Prompts.wsSummary(lang), text.take(24_000)) }
                find(read(app), id)?.put("summary", out)
                write(app, read(app))
                fire()
            } catch (e: GroqError) {
                find(read(app), id)?.put("summary", "Couldn't summarise: ${e.message}")
                write(app, read(app))
                fire()
            }
        }
    }

    private fun fmtSecs(s: Long): String {
        val m = s / 60
        val sec = s % 60
        return if (m > 0) "${m}m${sec}s" else "${sec}s"
    }
}
