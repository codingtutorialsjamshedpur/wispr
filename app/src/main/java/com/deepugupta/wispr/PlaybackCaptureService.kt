/*
 * Wispr by Deepu Gupta
 * Copyright 2026 Deepu Gupta
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 * SPDX-License-Identifier: Apache-2.0
 */
package com.deepugupta.wispr

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The whole live internal-audio capture lifecycle, owned by one foreground service.
 *
 * Flow: floating panel "Capture Audio" -> [CaptureConsentActivity] (the USER passes the
 * Android consent screen; a background service never conjures consent by itself) ->
 * here, carrying the approved RESULT_OK data. Every session uses a FRESH consent:
 * the result data goes into the start intent, is consumed exactly once by
 * `getMediaProjection`, and a previous session's authorization is never replayed.
 *
 * On Android 14+ the service first runs startForeground with the mediaProjection
 * type (with FOREGROUND_SERVICE_MEDIA_PROJECTION declared in the manifest), because
 * `getMediaProjection` may only be called while such a foreground service is running.
 * No VirtualDisplay is created - this is audio-only capture.
 *
 * The service owns the MediaProjection: its callback is registered BEFORE recording,
 * so a stop from Android's system UI (or permission revocation) cleanly ends the
 * session. The [PlaybackCapture] engine does the actual AudioRecord reading and feeds
 * the shared [Chunker]; every finished 16 kHz mono WAV chunk goes straight to
 * [TranscriptionManager], in order, and zero-audio sessions are reported honestly.
 */
class PlaybackCaptureService : Service() {

    enum class S { IDLE, REQUESTING_PERMISSION, STARTING, RECORDING, STOPPING, COMPLETED, FAILED }

    companion object {
        const val ACTION_START = "com.deepugupta.wispr.cap.START"
        const val ACTION_STOP = "com.deepugupta.wispr.cap.STOP"
        private const val EXTRA_RC = "rc"
        private const val EXTRA_DATA = "data"
        private const val EXTRA_LYRICS = "lyrics"

        @Volatile var state: S = S.IDLE
            private set

        /** Seconds captured so far (notification timer and the panel read this). */
        @Volatile var secs: Long = 0
            private set

        /** Last human-readable status / informational / error message. */
        @Volatile var msg: String = ""
            private set

        val isRecording: Boolean get() = state == S.STARTING || state == S.RECORDING

        fun stateJson(): String = JSONObject()
            .put("state", state.name.lowercase())
            .put("secs", secs)
            .put("sdk", Build.VERSION.SDK_INT)
            .put("msg", msg)
            .toString()

        /** Opens the consent screen. Only ever from a visible user action. */
        fun request(from: Context) {
            state = S.REQUESTING_PERMISSION
            msg = ""
            runCatching {
                from.startActivity(Intent(from, CaptureConsentActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.onFailure {
                state = S.FAILED
                msg = "Couldn't open the consent screen. Open Wispr and try again."
            }
            BubbleService.instance?.refresh()
        }

        /** Called by [CaptureConsentActivity] with the user-approved result data. */
        fun start(from: Context, resultCode: Int, resultData: Intent) {
            state = S.STARTING
            msg = ""
            try {
                from.startService(
                    Intent(from, PlaybackCaptureService::class.java)
                        .setAction(ACTION_START)
                        .putExtra(EXTRA_RC, resultCode)
                        .putExtra(EXTRA_DATA, resultData)
                )
            } catch (e: Exception) {
                state = S.FAILED
                msg = "Couldn't start the capture service (${e.javaClass.simpleName})."
                BubbleService.instance?.refresh()
            }
        }

        /** The user declined consent (or backed out of the dialog). */
        fun denied(from: Context) {
            state = S.FAILED
            msg = "Capture permission was declined. Nothing was recorded."
            BubbleService.instance?.refresh()
        }

        /** Status message without starting anything (from the consent activity). */
        fun mark(m: String) {
            msg = m
        }

        fun stop(from: Context) {
            runCatching {
                from.startService(Intent(from, PlaybackCaptureService::class.java).setAction(ACTION_STOP))
            }
        }
    }

    private val h = Handler(Looper.getMainLooper())
    private var cap: PlaybackCapture? = null
    private var proj: MediaProjection? = null
    private var sid = ""
    private var stopping = false
    private val tick = object : Runnable {
        override fun run() {
            if (state != S.RECORDING) return
            secs++
            runCatching {
                getSystemService(NotificationManager::class.java)
                    .notify(Notif.ID_CAP, Notif.capture(applicationContext, secs))
            }
            // Only the forever-second of a long large notification matters; ignore failures.
            h.postDelayed(this, 1000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> finishCapture("Stopped. The transcript is in the Wispr history.")
            ACTION_START -> begin(intent)
            else -> {
                if (state != S.RECORDING) state = S.IDLE
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun begin(i: Intent) {
        if (isRecording) { msg = "A capture session is already running."; return }
        if (Build.VERSION.SDK_INT < 29) { fail("Internal audio capture needs Android 10 or newer."); return }
        val data: Intent = i.parcelExtra(EXTRA_DATA) ?: run {
            fail("The consent result was lost. Tap Capture Audio again.")
            return
        }
        val rc = i.getIntExtra(EXTRA_RC, 0)

        Notif.ensure(this)
        state = S.STARTING
        // Android 14+ rule: run the mediaProjection foreground service BEFORE getMediaProjection.
        try {
            startForeground(Notif.ID_CAP, Notif.capture(this, 0), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } catch (e: Throwable) {
            fail("Android refused the recording service (${e.javaClass.simpleName}).")
            return
        }

        val mpm = getSystemService(MediaProjectionManager::class.java)
        val projection = try { mpm.getMediaProjection(rc, data) } catch (e: Exception) { null }
        if (projection == null) {
            endForegroundOnly()
            fail("Couldn't create the capture session (consent may have expired). Try again.")
            return
        }
        proj = projection

        // Register Android's projection callback BEFORE recording, so a stop from the
        // system UI (or revocation) ends the session cleanly through the same path.
        projection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                h.post { finishCapture("Capture was stopped from the system UI.") }
            }
        }, Handler(Looper.getMainLooper()))

        val lyrics = i.getBooleanExtra(EXTRA_LYRICS, false)
        sid = TranscriptionManager.newSession(applicationContext, "Capture ${fmtClock()}", "capture", lyrics)

        val engine = PlaybackCapture(applicationContext)
        val ok = engine.start(
            projection,
            TranscriptionManager.sessionDir(applicationContext, sid),
            "cap$sid", sid,
            onChunk = { c ->
                // Chunks arrive strictly in capture order (Chunker guarantees it);
                // TranscriptionManager keeps them ordered and retries failures on demand.
                TranscriptionManager.enqueueChunk(applicationContext, sid, c.file, c.startSec, lyrics)
            },
            onWarn = { m ->
                // Source app likely blocks capture: surface it, don't hide it.
                msg = m
                h.post { BubbleService.instance?.refresh() }
            },
            cancelled = { false }
        )
        if (!ok) {
            runCatching { projection.stop() }
            TranscriptionManager.captureFinished(applicationContext, sid, false)
            fail("This phone wouldn't grant the audio capture engine. Try importing the file instead.")
            return
        }
        cap = engine
        secs = 0
        state = S.RECORDING
        msg = ""
        h.post(tick)
    }

    private fun finishCapture(reason: String) {
        if (stopping) return
        stopping = true
        state = S.STOPPING
        h.removeCallbacks(tick)
        val engine = cap
        cap = null
        // What ACTUALLY arrived: PCM frames may exist without being "audible" — RMS is a
        // diagnostic, never the sole criterion for discarding audio or declaring failure.
        val level = engine?.received ?: PlaybackCapture.Level.NONE
        val ok = level == PlaybackCapture.Level.LOW_LEVEL || level == PlaybackCapture.Level.AUDIBLE
        runCatching { engine?.stop() }
        val p = proj
        proj = null
        if (p != null) runCatching { p.stop() } // also unregisters via projection teardown

        // Honest report: NO-PCM and pure-digital-silence sessions are NEVER shown as
        // successful; low-volume PCM is real audio and still goes to transcription.
        // Separate statuses on the record: capture = did real PCM arrive; audioQuality = level;
        // transcription is tracked independently by TranscriptionManager (never auto-implied).
        TranscriptionManager.captureFinished(
            applicationContext, sid, ok,
            capture = if (ok) "success" else "failed",
            quality = when (level) {
                PlaybackCapture.Level.AUDIBLE -> "normal"
                PlaybackCapture.Level.LOW_LEVEL -> "low"
                PlaybackCapture.Level.DIGITAL_SILENCE -> "digital_silence"
                PlaybackCapture.Level.NONE -> "none"
            },
            note = if (level == PlaybackCapture.Level.LOW_LEVEL)
                "Transcribing… (captured PCM was very quiet — kept in full, transcription may be rough)"
            else null,
            failMsg = when (level) {
                PlaybackCapture.Level.DIGITAL_SILENCE ->
                    "The capture received PCM data, but it was entirely digital silence. " +
                        "The source app may block capture (DRM/privacy) — try importing the media file, or use the microphone."
                else -> null // NONE keeps the default message
            }
        )
        endForegroundOnly()
        msg = when (level) {
            PlaybackCapture.Level.AUDIBLE -> reason
            PlaybackCapture.Level.LOW_LEVEL ->
                reason + " Low-volume audio was captured (below the loudness threshold but real PCM); transcribing it anyway."
            PlaybackCapture.Level.DIGITAL_SILENCE ->
                "Captured PCM data that was entirely digital silence — the source app may block capture (DRM/privacy)."
            PlaybackCapture.Level.NONE ->
                "No PCM samples were received from the capture engine — nothing was recorded."
        }
        state = if (ok) S.COMPLETED else S.FAILED
        stopping = false
        BubbleService.instance?.refresh()
        stopSelf()
    }

    private fun fail(reason: String) {
        msg = reason
        state = S.FAILED
        endForegroundOnly()
        BubbleService.instance?.refresh()
        stopSelf()
    }

    private fun endForegroundOnly() {
        runCatching { stopForeground(Service.STOP_FOREGROUND_REMOVE) }
    }

    override fun onDestroy() {
        if (isRecording) finishCapture("Capture ended.")
        super.onDestroy()
    }

    private fun Intent.parcelExtra(k: String): Intent? =
        if (Build.VERSION.SDK_INT >= 33) getParcelableExtra(k, Intent::class.java)
        else @Suppress("DEPRECATION") getParcelableExtra(k) as? Intent

    private fun fmtClock(): String = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date())
}
