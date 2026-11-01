/*
 * Wispr by Deepu Gupta
 * Copyright 2026 Deepu Gupta
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 * SPDX-License-Identifier: Apache-2.0
 */
package com.deepugupta.wispr

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import java.io.File

/**
 * Mono AAC tuned for speech recognition (v4 accuracy fix):
 *  - VOICE_RECOGNITION source: the phone's ASR-tuned mic path (no heavy call-style processing that smears fast words);
 *  - 32 kHz / 64 kbps: clearer consonants than the old 16 kHz / 32 kbps (~480 KB per minute, 15 min = ~7 MB, far under Groq's 25 MB).
 * Falls back to the plain MIC / 16 kHz setup on phones that refuse it.
 */
class Recorder(private val ctx: Context) {
    private var mr: MediaRecorder? = null
    private var file: File? = null
    private var t0 = 0L

    val isRecording: Boolean get() = mr != null

    @Suppress("DEPRECATION")
    private fun newRecorder(): MediaRecorder = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(ctx) else MediaRecorder()

    private class Cfg(val source: Int, val rate: Int, val bits: Int)

    private val configs = listOf(
        Cfg(MediaRecorder.AudioSource.VOICE_RECOGNITION, 32000, 64000),
        Cfg(MediaRecorder.AudioSource.MIC, 32000, 64000),
        Cfg(MediaRecorder.AudioSource.MIC, 16000, 32000)
    )

    @Synchronized
    fun start(): Boolean {
        if (mr != null) return true
        for (c in configs) if (tryStart(c)) return true
        return false
    }

    private fun tryStart(c: Cfg): Boolean {
        val f = File(ctx.cacheDir, "rec_${System.currentTimeMillis()}.m4a")
        val r = newRecorder()
        return try {
            r.setAudioSource(c.source)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioChannels(1)
            r.setAudioSamplingRate(c.rate)
            r.setAudioEncodingBitRate(c.bits)
            r.setOutputFile(f.absolutePath)
            r.prepare()
            r.start()
            mr = r
            file = f
            t0 = SystemClock.elapsedRealtime()
            true
        } catch (e: Exception) {
            runCatching { r.release() }
            f.delete()
            false
        }
    }

    fun level(): Float {
        val r = mr ?: return 0f
        return try { r.maxAmplitude / 32767f } catch (e: Exception) { 0f }
    }

    @Synchronized
    fun stop(): File? {
        val r = mr ?: return null
        mr = null
        val dur = SystemClock.elapsedRealtime() - t0
        val ok = try { r.stop(); true } catch (e: Exception) { false }
        runCatching { r.release() }
        val f = file
        file = null
        if (!ok || dur < 700 || f == null || f.length() < 1200) {
            f?.delete()
            return null
        }
        return f
    }

    @Synchronized
    fun cancel() {
        val r = mr ?: return
        mr = null
        runCatching { r.stop() }
        runCatching { r.release() }
        file?.delete()
        file = null
    }
}
