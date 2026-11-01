/*
 * Wispr by Deepu Gupta
 * Copyright 2026 Deepu Gupta
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 * SPDX-License-Identifier: Apache-2.0
 */
package com.deepugupta.wispr

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle

/**
 * The ONLY place Android's MediaProjection consent screen is opened from.
 *
 * Background services must never conjure consent by themselves: this transparent
 * activity shows the real `createScreenCaptureIntent()` dialog, waits for the user,
 * and hands the approved result to [PlaybackCaptureService.start]. Denial and
 * cancellation are reported honestly; nothing is started without RESULT_OK.
 */
class CaptureConsentActivity : Activity() {
    private companion object {
        const val REQ = 1
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (PlaybackCaptureService.isRecording) {
            PlaybackCaptureService.mark("A capture session is already running.")
            finish()
            return
        }
        val mpm = getSystemService(MediaProjectionManager::class.java)
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(mpm.createScreenCaptureIntent(), REQ)
        } catch (e: Exception) {
            PlaybackCaptureService.mark("Couldn't open the consent screen (${e.javaClass.simpleName}).")
            finish()
        }
    }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ) return
        if (resultCode == RESULT_OK && data != null) {
            PlaybackCaptureService.start(this, resultCode, data)
        } else {
            PlaybackCaptureService.denied(this)
        }
        finish()
    }
}
