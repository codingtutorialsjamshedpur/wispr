/*
 * Wispr by Deepu Gupta
 * Copyright 2026 Deepu Gupta
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 * SPDX-License-Identifier: Apache-2.0
 */
package com.deepugupta.wispr

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper

object Beep {
    fun play(ctx: Context, start: Boolean) {
        if (!Store.get(ctx).bool("sounds")) return
        try {
            val tg = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 70)
            tg.startTone(if (start) ToneGenerator.TONE_PROP_BEEP else ToneGenerator.TONE_PROP_ACK, 150)
            Handler(Looper.getMainLooper()).postDelayed({ tg.release() }, 400)
        } catch (e: Exception) {
            // no sound, no problem
        }
    }
}
