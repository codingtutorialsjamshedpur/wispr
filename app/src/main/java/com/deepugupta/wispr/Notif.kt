/*
 * Wispr by Deepu Gupta
 * Copyright 2026 Deepu Gupta
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 * SPDX-License-Identifier: Apache-2.0
 */
package com.deepugupta.wispr

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon

object Notif {
    private const val CH = "wispr_status"
    const val ID_REC = 41
    private const val ID_FAIL = 42
    private const val ID_DONE = 43
    private const val CH_UPD = "wispr_updates"
    private const val ID_UPD = 44
    /** Ongoing "capture audio" notification (owned by PlaybackCaptureService). */
    const val ID_CAP = 46

    private fun nm(ctx: Context): NotificationManager = ctx.getSystemService(NotificationManager::class.java)

    fun ensure(ctx: Context) {
        val nm = nm(ctx)
        if (nm.getNotificationChannel(CH) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CH, "Wispr status", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Listening and retry alerts"
                }
            )
        }
        if (nm.getNotificationChannel(CH_UPD) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CH_UPD, "App updates", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Tells you when a new Wispr version is out on GitHub"
                }
            )
        }
    }

    private fun openApp(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx, 0,
        Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    fun recording(ctx: Context): Notification {
        ensure(ctx)
        return Notification.Builder(ctx, CH)
            .setSmallIcon(R.drawable.ic_stat_mic)
            .setContentTitle("Wispr is listening")
            .setContentText("Tap the bubble again to type it in")
            .setOngoing(true)
            .setContentIntent(openApp(ctx))
            .build()
    }

    private fun post(ctx: Context, id: Int, n: Notification) {
        try { nm(ctx).notify(id, n) } catch (e: SecurityException) { /* notifications not allowed */ }
    }

    fun failed(ctx: Context, itemId: String, msg: String) {
        ensure(ctx)
        val retry = PendingIntent.getBroadcast(
            ctx, itemId.hashCode(),
            Intent(ctx, RetryReceiver::class.java).putExtra("id", itemId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = Notification.Builder(ctx, CH)
            .setSmallIcon(R.drawable.ic_stat_mic)
            .setContentTitle("Dictation didn't finish. Audio is saved.")
            .setContentText(msg)
            .setStyle(Notification.BigTextStyle().bigText(msg))
            .setAutoCancel(true)
            .setContentIntent(openApp(ctx))
            .addAction(Notification.Action.Builder(Icon.createWithResource(ctx, R.drawable.ic_stat_retry), "Retry", retry).build())
            .build()
        post(ctx, ID_FAIL, n)
    }

    fun done(ctx: Context) {
        ensure(ctx)
        val n = Notification.Builder(ctx, CH)
            .setSmallIcon(R.drawable.ic_stat_mic)
            .setContentTitle("Recovered")
            .setContentText("Your text is copied. Long-press any text box and Paste.")
            .setAutoCancel(true)
            .setContentIntent(openApp(ctx))
            .build()
        post(ctx, ID_DONE, n)
    }

    fun cancelFail(ctx: Context) { runCatching { nm(ctx).cancel(ID_FAIL) } }

    /** Persistent internal-audio-capture notification with a working Stop action. */
    fun capture(ctx: Context, secs: Long): Notification {
        ensure(ctx)
        val stop = PendingIntent.getService(
            ctx, 2,
            Intent(ctx, PlaybackCaptureService::class.java).setAction(PlaybackCaptureService.ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val m = secs / 60
        val s = secs % 60
        return Notification.Builder(ctx, CH)
            .setSmallIcon(R.drawable.ic_stat_mic)
            .setContentTitle("Wispr is capturing audio")
            .setContentText(String.format("Capturing this phone's playback · %d:%02d", m, s))
            .setOngoing(true)
            .setContentIntent(openApp(ctx))
            .addAction(Notification.Action.Builder(Icon.createWithResource(ctx, R.drawable.ic_stat_mic), "Stop", stop).build())
            .build()
    }

    // ---------- updates ----------

    private fun openUpdate(ctx: Context, action: String): PendingIntent = PendingIntent.getActivity(
        ctx, action.hashCode(),
        Intent(ctx, MainActivity::class.java).putExtra("update", action)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    /** "Wispr 4.2.0 is out". ready = already downloaded and checked, one tap installs it. */
    fun update(ctx: Context, version: String, title: String, ready: Boolean) {
        ensure(ctx)
        val text = if (ready) "Downloaded and checked. Tap Install, it takes a few seconds." else "Tap to see what's new and update inside the app."
        val n = Notification.Builder(ctx, CH_UPD)
            .setSmallIcon(R.drawable.ic_stat_update)
            .setContentTitle("Wispr $version is available")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(if (title.isNotBlank() && !title.contains(version)) "$title\n$text" else text))
            .setAutoCancel(true)
            .setContentIntent(openUpdate(ctx, "show"))
            .addAction(Notification.Action.Builder(Icon.createWithResource(ctx, R.drawable.ic_stat_update), if (ready) "Install" else "Update", openUpdate(ctx, "install")).build())
            .build()
        post(ctx, ID_UPD, n)
    }

    /** Android wants the user to confirm the install (older Android, or app in background). */
    fun updateConfirm(ctx: Context, confirm: Intent) {
        ensure(ctx)
        val pi = PendingIntent.getActivity(ctx, 45, confirm, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = Notification.Builder(ctx, CH_UPD)
            .setSmallIcon(R.drawable.ic_stat_update)
            .setContentTitle("Wispr update is ready")
            .setContentText("Tap to install it")
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        post(ctx, ID_UPD, n)
    }

    fun updateFailed(ctx: Context, msg: String) {
        ensure(ctx)
        val n = Notification.Builder(ctx, CH_UPD)
            .setSmallIcon(R.drawable.ic_stat_update)
            .setContentTitle("Wispr update didn't install")
            .setContentText(msg)
            .setStyle(Notification.BigTextStyle().bigText(msg))
            .setAutoCancel(true)
            .setContentIntent(openUpdate(ctx, "show"))
            .build()
        post(ctx, ID_UPD, n)
    }

    fun updated(ctx: Context, version: String) {
        ensure(ctx)
        val n = Notification.Builder(ctx, CH_UPD)
            .setSmallIcon(R.drawable.ic_stat_update)
            .setContentTitle("Wispr updated to $version")
            .setContentText("All set. Your key and settings are kept.")
            .setAutoCancel(true)
            .setContentIntent(openApp(ctx))
            .build()
        post(ctx, ID_UPD, n)
    }

    fun cancelUpdate(ctx: Context) { runCatching { nm(ctx).cancel(ID_UPD) } }
}
