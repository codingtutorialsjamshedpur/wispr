/*
 * Wispr by Deepu Gupta
 * Copyright 2026 Deepu Gupta
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 * SPDX-License-Identifier: Apache-2.0
 */
package com.deepugupta.wispr
import android.content.Context
import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.KeyEvent
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import org.json.JSONObject
import org.json.JSONTokener

/**
 * The mini Wispr window, floating over any app:
 *  - hold the keyboard bubble, or tap the Android Accessibility button / shortcut from any screen;
 *  - Write / Polish / Translate, target language, Hindi-Hinglish toggle, AI models, AI chat, Understand screen.
 * It takes keyboard focus only while you type in the chat box, so the app underneath keeps its cursor.
 */
@SuppressLint("SetJavaScriptEnabled")
class Panel(private val svc: BubbleService) {
    private val h = Handler(Looper.getMainLooper())
    private val wm: WindowManager = svc.getSystemService(WindowManager::class.java)
    private val store = Store.get(svc)
    private val recorder = Recorder(svc.applicationContext)
    private var web: WebView? = null
    private val lp = WindowManager.LayoutParams()
    private var shown = false
    @Volatile private var live: Set<String>? = null
    @Volatile private var voiceOn = false

    val isOpen: Boolean get() = web != null

    private fun dp(v: Float): Int = (v * svc.resources.displayMetrics.density + 0.5f).toInt()

    fun open(tab: String, runScreen: Boolean) {
        val existing = web
        if (existing != null) {
            js("window.WisprPanelTab&&WisprPanelTab(${JSONObject.quote(tab)},$runScreen)")
            return
        }
        val w = WebView(ContextThemeWrapper(svc, R.style.Theme_Wispr))
        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(svc))
            .build()
        with(w.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            setGeolocationEnabled(false)
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        }
        w.setBackgroundColor(Color.TRANSPARENT)
        w.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                loader.shouldInterceptRequest(request.url)

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                request.url.host != HOST // the panel never navigates away
        }
        w.addJavascriptInterface(Bridge(), "WisprPanel")
        w.setOnKeyListener { _, code, e ->
            if (code == KeyEvent.KEYCODE_BACK && e.action == KeyEvent.ACTION_UP) { back(); true } else false
        }
        web = w

        lp.type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        lp.format = PixelFormat.TRANSLUCENT
        lp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        lp.flags = BASE_FLAGS or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        lp.windowAnimations = android.R.style.Animation_Toast
        lp.alpha = 1f
        fit()
        try {
            wm.addView(w, lp)
            shown = true
        } catch (e: Exception) {
            web = null
            w.destroy()
            svc.toast("Couldn't open the Wispr window")
            return
        }
        w.loadUrl("https://$HOST/assets/web/panel.html#tab=$tab&auto=${if (runScreen) 1 else 0}")
    }

    /** Sits below the status bar and above the keyboard (if one is open). */
    private fun panelPrefs() = svc.getSharedPreferences("panel_window", Context.MODE_PRIVATE)

    private fun fit() {
        val sw = svc.screenW()
        val sh = svc.screenH()
        val top = dp(34f)
        val ime = svc.imeTopNow()
        val bottom = if (ime > top + dp(200f)) ime - dp(8f) else sh - dp(48f)

        val maxW = minOf(sw - dp(16f), dp(460f))
        val maxH = minOf(maxOf(dp(260f), bottom - top), dp(660f))

        val pref = panelPrefs()
        val savedW = pref.getInt("w", 0)
        val savedH = pref.getInt("h", 0)

        lp.width = if (savedW > 0) savedW.coerceIn(dp(300f), maxW) else maxW
        lp.height = if (savedH > 0) savedH.coerceIn(dp(260f), maxH) else maxH
        lp.y = top
    }

    private fun resizeWindow(widthPx: Int, heightPx: Int) {
        if (web == null) return
        val sw = svc.screenW()
        val sh = svc.screenH()
        val top = dp(34f)
        val ime = svc.imeTopNow()
        val bottom = if (ime > top + dp(200f)) ime - dp(8f) else sh - dp(48f)

        val maxW = minOf(sw - dp(16f), dp(460f))
        val maxH = minOf(maxOf(dp(260f), bottom - top), dp(660f))

        lp.width = widthPx.coerceIn(dp(300f), maxW)
        lp.height = heightPx.coerceIn(dp(260f), maxH)
        lp.y = top

        panelPrefs().edit()
            .putInt("w", lp.width)
            .putInt("h", lp.height)
            .apply()

        relayout()
    }

    private fun relayout() {
        val w = web ?: return
        if (shown) runCatching { wm.updateViewLayout(w, lp) }
    }

    /** Focusable only while typing in the panel, so the app below keeps its text cursor otherwise. */
    private fun setFocusable(on: Boolean) {
        val w = web ?: return
        lp.flags = if (on) BASE_FLAGS else BASE_FLAGS or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        relayout()
        if (on) w.requestFocus()
        h.postDelayed({ if (web != null) { fit(); relayout() } }, if (on) 450L else 300L)
    }

    private fun back() {
        val w = web ?: return
        w.evaluateJavascript("window.WisprBack?WisprBack():false") { r -> if (r != "true") close() }
    }

    fun close() {
        if (voiceOn) { voiceOn = false; recorder.cancel(); svc.micForeground(false) }
        val w = web ?: return
        web = null
        if (shown) runCatching { wm.removeView(w) }
        shown = false
        w.destroy()
        svc.onPanelClosed()
    }

    // ---------- screen ----------
    private fun understand(mode: String, id: String) {
        if (mode != "ocr") {
            status(id, "Reading the screen…")
            val txt = ScreenReader.text(svc)
            if (txt.length >= 40) {
                Assistant.understandText(svc, txt, { m -> status(id, m) }) { ok, t -> cb(id, ok, t) }
                return
            }
        }
        if (!ScreenReader.canScreenshot()) {
            cb(id, false, "Hardly any readable text here. Scanning pictures (OCR) needs Android 11 or newer.")
            return
        }
        status(id, "Scanning the screen…")
        lp.alpha = 0f // keep the panel itself out of the screenshot
        relayout()
        h.postDelayed({
            ScreenReader.screenshot(svc) { b64 ->
                lp.alpha = 1f
                relayout()
                if (b64 == null) {
                    cb(id, false, "Couldn't capture this screen (some apps like banking block screenshots).")
                } else {
                    status(id, "Reading the text in the picture…")
                    Assistant.understandImage(svc, b64, { m -> status(id, m) }) { ok, t -> cb(id, ok, t) }
                }
            }
        }, 280)
    }

    // ---------- JS helpers ----------
    private fun js(code: String) { h.post { web?.evaluateJavascript(code, null) } }

    private fun cb(id: String, ok: Boolean, data: String) =
        js("window.WisprCb&&WisprCb(${JSONObject.quote(id)},$ok,${JSONObject.quote(data)})")

    private fun status(id: String, msg: String) =
        js("window.WisprStatus&&WisprStatus(${JSONObject.quote(id)},${JSONObject.quote(msg)})")

    /** Everything the panel page may do. The API key never reaches the page. */
    inner class Bridge {
        @JavascriptInterface fun getSettings(): String = store.publicJson()

        @JavascriptInterface
        fun setSetting(k: String, v: String): Boolean {
            val value = try { JSONTokener(v).nextValue() } catch (e: Exception) { return false }
            return store.putFromUi(k, value)
        }

        @JavascriptInterface fun models(): String = Models.json(live)

        @JavascriptInterface
        fun refreshModels(id: String) {
            Engine.io {
                try {
                    val key = store.apiKey
                    if (key.isBlank()) throw GroqError("Add your Groq key in the Wispr app first.", false, auth = true)
                    val ids = Groq.listModels(key)
                    live = ids
                    cb(id, true, Models.json(ids))
                } catch (e: GroqError) {
                    cb(id, false, e.message ?: "Couldn't load models")
                }
            }
        }

        @JavascriptInterface fun sdk(): Int = Build.VERSION.SDK_INT
        @JavascriptInterface fun canScan(): Boolean = ScreenReader.canScreenshot()
        @JavascriptInterface fun hasTarget(): Boolean = svc.hasInsertTarget()
        @JavascriptInterface fun close() { h.post { this@Panel.close() } }
        @JavascriptInterface fun keyboard(on: Boolean) { h.post { setFocusable(on) } }

        @JavascriptInterface
        fun resizeWindow(widthPx: Int, heightPx: Int) {
            h.post { this@Panel.resizeWindow(widthPx, heightPx) }
        }

        /** Big mic in the panel: close it and dictate into the box below, like tapping the bubble. */
        @JavascriptInterface
        fun dictate() { h.post { this@Panel.close(); h.postDelayed({ svc.startDictation() }, 250) } }

        @JavascriptInterface
        fun insert(text: String) { h.post { this@Panel.close(); h.postDelayed({ svc.insertText(text) }, 350) } }

        @JavascriptInterface fun copy(text: String): Boolean = Clip.copy(svc, text)

        @JavascriptInterface
        fun chat(text: String, id: String) {
            Assistant.chat(svc, text, { m -> status(id, m) }) { ok, t -> cb(id, ok, t) }
        }

        @JavascriptInterface fun chatHistory(): String = Assistant.historyJson()
        @JavascriptInterface fun chatReset() { Assistant.reset() }

        @JavascriptInterface
        fun voiceStart(): Boolean {
            if (!Perms.mic(svc)) { svc.toast("Allow the microphone for Wispr first"); return false }
            if (voiceOn) return true
            h.post { svc.micForeground(true) }
            val ok = recorder.start()
            voiceOn = ok
            if (!ok) h.post { svc.micForeground(false) }
            return ok
        }

        @JavascriptInterface fun voiceLevel(): Float = recorder.level()

        @JavascriptInterface
        fun voiceStop(id: String) {
            voiceOn = false
            val f = recorder.stop()
            h.post { svc.micForeground(false) }
            if (f == null) { cb(id, false, "Too short. Speak a little longer."); return }
            Assistant.transcribe(svc, f, { m -> status(id, m) }) { ok, t -> cb(id, ok, t) }
        }

        @JavascriptInterface
        fun voiceCancel() {
            voiceOn = false
            recorder.cancel()
            h.post { svc.micForeground(false) }
        }

        @JavascriptInterface fun understand(mode: String, id: String) { h.post { understand(mode, id) } }

        @JavascriptInterface fun openApp() { h.post { this@Panel.close(); svc.openApp(null) } }

        // ---------- internal-audio capture (Mode A) ----------
        @JavascriptInterface
        fun captureStart() {
            h.post {
                if (Build.VERSION.SDK_INT < 29) { svc.toast("Internal audio capture needs Android 10 or newer"); return@post }
                if (PlaybackCaptureService.isRecording) { svc.toast("Already capturing — tap Stop to end"); return@post }
                if (store.apiKey.isBlank()) { svc.toast("Open Wispr and add your Groq key first"); return@post }
                // Opens the real Android consent screen through a visible activity.
                PlaybackCaptureService.request(svc)
            }
        }

        @JavascriptInterface fun captureStop() = PlaybackCaptureService.stop(svc)
        @JavascriptInterface fun captureState(): String = PlaybackCaptureService.stateJson()

        @JavascriptInterface
        fun historyOpen() { h.post { this@Panel.close(); svc.openApp(null) } }
    }

    companion object {
        private const val HOST = "appassets.androidplatform.net"
        private const val BASE_FLAGS = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
    }
}
