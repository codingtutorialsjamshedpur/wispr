/*
 * Wispr by Deepu Gupta
 * Copyright 2026 Deepu Gupta
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 * SPDX-License-Identifier: Apache-2.0
 */
package com.deepugupta.wispr

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class RetryReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val id = intent.getStringExtra("id") ?: return
        Notif.cancelFail(ctx)
        val svc = BubbleService.instance
        if (svc != null) {
            svc.retryItem(id)
            return
        }
        val app = ctx.applicationContext
        Engine.retry(app, id, {}) { ok, text, _ ->
            if (ok) {
                Clip.copy(app, text)
                Notif.done(app)
            } else {
                Notif.failed(app, id, text)
            }
        }
    }
}
