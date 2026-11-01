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
import android.content.pm.PackageInstaller
import android.os.Build

/** Install results from PackageInstaller, and "the new version is now installed". Not exported. */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_MY_PACKAGE_REPLACED -> Updater.onUpdated(ctx.applicationContext)
            Updater.ACTION_STATUS -> {
                val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
                if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                    // Android 8 to 11 (or when Android wants a confirmation): show its "Update this app?" screen.
                    val confirm = confirmIntent(intent) ?: return
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    if (MainActivity.visible) {
                        try { ctx.startActivity(confirm); return } catch (e: Exception) { /* fall through */ }
                    }
                    Notif.updateConfirm(ctx, confirm)
                } else if (status != PackageInstaller.STATUS_SUCCESS) {
                    Updater.onInstallResult(ctx.applicationContext, status, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))
                }
                // STATUS_SUCCESS: this process is replaced; MY_PACKAGE_REPLACED fires in the new version.
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun confirmIntent(i: Intent): Intent? =
        if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        else i.getParcelableExtra(Intent.EXTRA_INTENT)
}
