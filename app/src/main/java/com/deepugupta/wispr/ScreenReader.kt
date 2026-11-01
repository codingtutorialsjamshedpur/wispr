/*
 * Wispr by Deepu Gupta
 * Copyright 2026 Deepu Gupta
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 * SPDX-License-Identifier: Apache-2.0
 */
package com.deepugupta.wispr

import android.accessibilityservice.AccessibilityService
import android.annotation.TargetApi
import android.graphics.Bitmap
import android.os.Build
import android.util.Base64
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.min

/**
 * "Understand screen":
 *  - [text]: reads the words apps expose to accessibility (instant, exact, works on Android 8+);
 *  - [screenshot]: for pictures, videos, games and PDFs, a screenshot for OCR by a Groq vision model (Android 11+).
 * Password fields, the keyboard and Wispr's own windows are always skipped. Nothing is saved.
 */
object ScreenReader {
    private const val MAX_NODES = 4000

    fun text(svc: AccessibilityService, maxChars: Int = 6000): String {
        val seen = LinkedHashSet<String>()
        val ws = runCatching { svc.windows }.getOrNull().orEmpty()
        for (w in ws) {
            if (w.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
            val root = runCatching { w.root }.getOrNull() ?: continue
            if (root.packageName?.toString() == svc.packageName) continue
            walk(root, seen)
        }
        val sb = StringBuilder()
        for (s in seen) {
            if (sb.length + s.length + 1 > maxChars) break
            sb.append(s).append('\n')
        }
        return sb.toString().trim()
    }

    private fun walk(root: AccessibilityNodeInfo, out: MutableSet<String>) {
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        var count = 0
        while (stack.isNotEmpty() && count < MAX_NODES) {
            val n = stack.removeLast()
            count++
            if (n.isPassword || !n.isVisibleToUser) continue
            val t = (n.text ?: n.contentDescription)?.toString()?.trim()
            if (!t.isNullOrEmpty() && t.length > 1) out += t
            for (i in n.childCount - 1 downTo 0) runCatching { n.getChild(i) }.getOrNull()?.let { stack.addLast(it) }
        }
    }

    fun canScreenshot(): Boolean = Build.VERSION.SDK_INT >= 30

    /** JPEG (base64) of the screen, or null. [cb] runs on the main thread. */
    fun screenshot(svc: AccessibilityService, cb: (String?) -> Unit) {
        if (Build.VERSION.SDK_INT < 30) { cb(null); return }
        try {
            svc.takeScreenshot(Display.DEFAULT_DISPLAY, svc.mainExecutor, object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                    Engine.io {
                        val b64 = runCatching { encode(result) }.getOrNull()
                        Engine.post { cb(b64) }
                    }
                }

                override fun onFailure(errorCode: Int) { cb(null) }
            })
        } catch (e: Throwable) {
            cb(null)
        }
    }

    /** Longest side 1600 px, JPEG 82: sharp enough for small text, ~300 KB (Groq's base64 image limit is 4 MB). */
    @TargetApi(30)
    private fun encode(r: AccessibilityService.ScreenshotResult): String? {
        val hb = r.hardwareBuffer
        try {
            val hw = Bitmap.wrapHardwareBuffer(hb, r.colorSpace) ?: return null
            val soft = hw.copy(Bitmap.Config.ARGB_8888, false) ?: return null
            hw.recycle()
            val scale = min(1f, 1600f / max(soft.width, soft.height))
            val bmp = if (scale < 1f) Bitmap.createScaledBitmap(soft, (soft.width * scale).toInt(), (soft.height * scale).toInt(), true) else soft
            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, 82, out)
            if (bmp !== soft) bmp.recycle()
            soft.recycle()
            return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        } finally {
            hb.close()
        }
    }
}
