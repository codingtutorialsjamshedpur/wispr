/*
 * Wispr by Deepu Gupta
 * Copyright 2026 Deepu Gupta
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 * SPDX-License-Identifier: Apache-2.0
 */
package com.deepugupta.wispr

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import java.io.File

/**
 * MODE A engine: live capture of ELIGIBLE internal playback from OTHER apps
 * (Android 10+ AudioPlaybackCapture API). App-agnostic by construction - the filter
 * matches by audio USAGE (MEDIA / GAME / UNKNOWN), never by package name.
 *
 * Honest limits of the platform (surfaced to the user, never hidden):
 *  - a source app can mark its stream non-capturable (ALLOW_CAPTURE_BY_NONE) - DRM-protected
 *    or privacy streams then show up silent;
 *  - capture only sees MEDIA/GAME/UNKNOWN usages - phone calls, alarms and ringtone do not;
 *  - it only hears playback on the same user profile.
 *
 * The engine owns one read thread; each finished 16 kHz WAV chunk flows into the shared
 * [Chunker] and is handed to the service through [onChunk]. Nothing about a "keyboard"
 * is involved: capture starts/stops from the notification and the floating assistant
 * regardless of what app or text field is on screen.
 */
class PlaybackCapture(private val app: Context) {
    private var rec: AudioRecord? = null
    private var thread: Thread? = null
    private lateinit var chunker: Chunker

    @Volatile private var stopping = false
    @Volatile private var started = false

    /** ms of consecutive DIGITAL silence so far (0 = real samples are flowing). */
    @Volatile var silentMs = 0L
        private set
    /** ms worth of LOW-AMPLITUDE (non-silent, below [SILENCE_RMS]) frames so far. */
    @Volatile var lowLevelMs = 0L
        private set
    /** true once at least one clearly-audible frame (RMS > [SILENCE_RMS]) was captured. */
    @Volatile var anyAudio = false
        private set
    /** PCM frames actually read from AudioRecord — every read counts, whatever its level. */
    @Volatile var framesRead = 0L
        private set

    /**
     * What the engine actually received over the whole session. RMS is a DIAGNOSTIC signal:
     * it never decides file contents (all PCM is kept verbatim in [Chunker], whatever its
     * level) and never alone makes a session "failed" — only NONE and DIGITAL_SILENCE are
     * honest failures; LOW_LEVEL audio is just quiet, and still real audio.
     */
    enum class Level { NONE, DIGITAL_SILENCE, LOW_LEVEL, AUDIBLE }
    val received: Level
        get() = when {
            !started || framesRead == 0L -> Level.NONE
            anyAudio -> Level.AUDIBLE
            lowLevelMs > 0L -> Level.LOW_LEVEL
            else -> Level.DIGITAL_SILENCE
        }

    val isCapturing: Boolean get() = started && !stopping

    val done: List<AudioChunk> get() = if (started) chunker.done else emptyList()
    val files: List<File> get() = if (started) chunker.files else emptyList()

    /** startSec continuity across chunk restarts handled inside [Chunker]. */
    fun start(
        projection: MediaProjection,
        dir: File,
        prefix: String,
        forSession: String,
        onChunk: (AudioChunk) -> Unit,
        onWarn: (String) -> Unit,
        cancelled: () -> Boolean,
    ): Boolean {
        if (started || stopping) return false
        if (android.os.Build.VERSION.SDK_INT < 29) return false
        // The projection is ONLY read here to build the capture configuration; the caller
        // (PlaybackCaptureService) keeps ownership of its lifecycle and stop()/release().
        val minBuf = AudioRecord.getMinBufferSize(Chunker.SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) return false
        val cfg = try {
            AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
        } catch (e: Exception) {
            return false
        }
        val fmt = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(Chunker.SR)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()
        val r = try {
            AudioRecord.Builder()
                .setAudioFormat(fmt)
                .setAudioPlaybackCaptureConfig(cfg)
                .setBufferSizeInBytes(minBuf * 4)
                .build()
        } catch (e: Exception) {
            return false
        }
        if (r.state != AudioRecord.STATE_INITIALIZED) { r.release(); return false }
        rec = r
        chunker = Chunker(dir, prefix)
        stopping = false
        started = true
        val buf = ShortArray(Chunker.SR / 20) // 50 ms reads
        var checkedSil = false
        val winMs = 50L
        var startStamp = System.currentTimeMillis()
        thread = Thread {
            r.startRecording()
            try {
                while (!stopping && !cancelled()) {
                    val n = r.read(buf, 0, buf.size)
                    if (n <= 0) continue // no data yet: neither success nor failure, just wait
                    framesRead += n
                    // ALL samples are preserved verbatim regardless of their level (Chunker).
                    chunker.feed(Chunker.SR, buf.copyOf(n))
                    // Level diagnostics: classify, but never use RMS to discard anything.
                    var acc = 0L
                    for (i in 0 until n) acc += buf[i].toLong() * buf[i]
                    val rms = Math.sqrt(acc.toDouble() / n).toInt()
                    when {
                        rms > SILENCE_RMS -> { anyAudio = true; silentMs = 0 }
                        rms > DIGITAL_SILENCE_RMS -> { lowLevelMs += winMs; silentMs = 0 }
                        else -> {
                            silentMs += winMs
                            if (!checkedSil && silentMs >= WARN_AFTER_MS) {
                                checkedSil = true
                                // Warn ONLY when genuinely nothing (or pure digital silence) so far:
                                // quiet speech / low-volume music ARE audio, and must not be
                                // mislabelled "nothing is being captured".
                                if (!anyAudio && lowLevelMs == 0L) onWarn(
                                    "Captured audio so far is digital silence: the playing app may block " +
                                        "capture (DRM/privacy), play through a different profile, or use an " +
                                        "unsupported audio type. Eligible sources are media/game playback on the same profile."
                                )
                            }
                        }
                    }
                    // Hand closed chunks to the caller as soon as they appear.
                    for (c in chunker.pollNew()) onChunk(c)
                }
            } catch (e: Exception) {
                // Recording stops are fine here; the service finalises the session.
            } finally {
                try { if (r.recordingState == AudioRecord.RECORDSTATE_RECORDING) r.stop() } catch (e: Exception) {}
                runCatching { r.release() }
                runCatching { chunker.finishStream() }
            }
            startStamp = 0
        }.apply { isDaemon = true; start() }
        return true
    }

    /** Ends the capture thread and flushes the final partial chunk. Idempotent. */
    fun stop() {
        if (!started) return
        stopping = true
        val t = thread
        thread = null
        try { t?.interrupt() } catch (e: Exception) {}
        try { t?.join(2000) } catch (e: Exception) {}
        runCatching { rec?.stop() }
        runCatching { rec?.release() }
        rec = null
        started = false
    }

    companion object {
        /** Below this RMS audio is "quiet" — diagnostic only, never a discard criterion. */
        const val SILENCE_RMS = 120 // on a 16-bit scale, far under audibility
        /** At/below this RMS in 16-bit the samples are pure digital silence (all-zeros / noise floor). */
        const val DIGITAL_SILENCE_RMS = 2
        /** How long pure digital silence before we warn the user (12 s). */
        const val WARN_AFTER_MS = 12_000L
    }
}