/*
 * Wispr by Deepu Gupta
 * Copyright 2026 Deepu Gupta
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 * SPDX-License-Identifier: Apache-2.0
 */
package com.deepugupta.wispr

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import org.json.JSONObject

object Perms {
    fun mic(ctx: Context): Boolean =
        ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    fun notif(ctx: Context): Boolean = Build.VERSION.SDK_INT < 33 ||
        ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun a11y(ctx: Context): Boolean {
        val s = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        val me = ComponentName(ctx, BubbleService::class.java)
        return s.split(':').any { ComponentName.unflattenFromString(it) == me }
    }

    fun battery(ctx: Context): Boolean =
        ctx.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(ctx.packageName) ?: true

    fun json(ctx: Context): String = JSONObject()
        .put("mic", mic(ctx)).put("notif", notif(ctx)).put("a11y", a11y(ctx)).put("battery", battery(ctx))
        .toString()
}
