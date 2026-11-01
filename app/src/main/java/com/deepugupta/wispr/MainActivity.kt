/*
 * Wispr by Deepu Gupta
 * Copyright 2026 Deepu Gupta
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 * SPDX-License-Identifier: Apache-2.0
 */
package com.deepugupta.wispr

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.window.OnBackInvokedDispatcher
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.webkit.WebViewAssetLoader
import org.json.JSONObject
import org.json.JSONTokener

class MainActivity : Activity() {
    private lateinit var web: WebView
    private lateinit var store: Store
    private val recorder by lazy { Recorder(applicationContext) }
    private val onStoreChange: () -> Unit = { js("window.WisprHistoryChanged&&WisprHistoryChanged()") }
    private val onUpdate: (String) -> Unit = { s -> js("window.WisprUpdate&&WisprUpdate(${JSONObject.quote(s)})") }
    private var fromBubble = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store.get(this)
        forgetOldUpdateInfo()
        WebView.setWebContentsDebuggingEnabled(false)
        web = WebView(this)
        setContentView(web)
        applyBars()

        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()
        with(web.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            setGeolocationEnabled(false)
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        }
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                loader.shouldInterceptRequest(request.url)

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val u = request.url
                if (u.host == HOST) return false
                if (u.scheme == "https") runCatching { startActivity(Intent(Intent.ACTION_VIEW, u)) }
                return true
            }
        }
        web.addJavascriptInterface(Bridge(), "WisprNative")
        // Android 15+ draws apps edge-to-edge: keep the page clear of the status bar, nav bar and keyboard.
        if (Build.VERSION.SDK_INT >= 30) {
            web.setOnApplyWindowInsetsListener { v, insets ->
                val b = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime() or WindowInsets.Type.displayCutout())
                v.setPadding(b.left, b.top, b.right, b.bottom)
                WindowInsets.CONSUMED
            }
        }
        // Android 16+ (targetSdk 36/37) no longer calls onBackPressed: use the new back callback.
        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) { handleBack() }
        }
        web.loadUrl("https://$HOST/assets/web/index.html")
        store.addListener(onStoreChange)
        firstRunAsk()
        handleIntent(intent)
    }

    /**
     * First open after a fresh install or an update: forget everything the OLD version cached about updates
     * (release info, downloaded APK, "already notified" flags) and remove its old "Update available" notification.
     * The next check then starts clean, so a just-installed latest version never shows an Update banner.
     */
    private fun forgetOldUpdateInfo() {
        val flags = getSharedPreferences("perm_flags", MODE_PRIVATE)
        val installed = runCatching { packageManager.getPackageInfo(packageName, 0).lastUpdateTime }.getOrDefault(0L)
        val build = "${BuildConfig.VERSION_NAME}#$installed"
        if (flags.getString("seenBuild", null) == build) return
        flags.edit().putString("seenBuild", build).apply()
        getSharedPreferences("wispr_update", MODE_PRIVATE).edit()
            .remove("rel").remove("etag").remove("readyVer").remove("readyPath")
            .remove("notified").remove("last").remove("retryAt")
            .apply()
        runCatching { Notif.cancelUpdate(this) }
    }

    /** True only when a newer version really is waiting (used before showing the update card). */
    private fun realUpdateWaiting(): Boolean = runCatching {
        val s = JSONObject(Updater.stateJson(this))
        val st = s.optString("state")
        (st == "available" || st == "ready") && Updater.isNewer(s.optString("latest"))
    }.getOrDefault(false)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(i: Intent?) {
        if (i != null && i.getStringExtra("ask") == "mic") {
            fromBubble = true
            i.removeExtra("ask")
            if (!Perms.mic(this)) askRuntime(Manifest.permission.RECORD_AUDIO)
        }
        when (i?.getStringExtra("update")) {
            "install" -> { i.removeExtra("update"); Notif.cancelUpdate(this); startUpdate() }
            "show" -> {
                i.removeExtra("update")
                Notif.cancelUpdate(this)
                // An old notification can outlive the update: only open the card if there really is one.
                if (realUpdateWaiting()) js("window.WisprShowUpdate&&WisprShowUpdate()")
            }
        }
    }

    /** Update now: needs "Install unknown apps" allowed for Wispr once (Android 8+ rule). */
    private fun startUpdate() {
        if (!Updater.canInstall(this)) {
            Updater.pendingInstall = true
            Toast.makeText(this, "Allow \"Install unknown apps\" for Wispr, then come back", Toast.LENGTH_LONG).show()
            if (!safeStart(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))) openAppInfo()
            return
        }
        Updater.pendingInstall = false
        Updater.start(this)
    }

    override fun onResume() {
        super.onResume()
        visible = true
        Updater.listener = onUpdate
        applyBars()
        js("window.WisprOnResume&&WisprOnResume()")
        if (Updater.pendingInstall && Updater.canInstall(this)) startUpdate() else Updater.maybeCheck(this, background = false)
    }

    override fun onPause() {
        visible = false
        super.onPause()
    }

    override fun onDestroy() {
        store.removeListener(onStoreChange)
        if (Updater.listener === onUpdate) Updater.listener = null
        recorder.cancel()
        web.destroy()
        super.onDestroy()
    }

    private fun handleBack() {
        web.evaluateJavascript("window.WisprBack?WisprBack():false") { r -> if (r != "true") finish() }
    }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (Build.VERSION.SDK_INT >= 33) super.onBackPressed() else handleBack()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        js("window.WisprOnResume&&WisprOnResume()")
        if (fromBubble && Perms.mic(this)) {
            fromBubble = false
            Toast.makeText(this, "Mic allowed. Go back and tap the Wispr bubble.", Toast.LENGTH_LONG).show()
            moveTaskToBack(true)
        }
    }

    // ---------- permissions ----------
    private fun firstRunAsk() {
        val sp = getSharedPreferences("perm_flags", MODE_PRIVATE)
        if (sp.getBoolean("first", false)) return
        sp.edit().putBoolean("first", true).apply()
        val need = mutableListOf<String>()
        if (!Perms.mic(this)) need += Manifest.permission.RECORD_AUDIO
        if (Build.VERSION.SDK_INT >= 33 && !Perms.notif(this)) need += Manifest.permission.POST_NOTIFICATIONS
        for (p in need) sp.edit().putBoolean(p, true).apply()
        if (need.isNotEmpty()) requestPermissions(need.toTypedArray(), REQ)
    }

    private fun askRuntime(perm: String) {
        val sp = getSharedPreferences("perm_flags", MODE_PRIVATE)
        if (sp.getBoolean(perm, false) && !shouldShowRequestPermissionRationale(perm)) {
            // Android stops showing the popup after 2 denials: send the user to App info instead.
            Toast.makeText(this, "Tap Permissions and allow it for Wispr", Toast.LENGTH_LONG).show()
            openAppInfo()
        } else {
            sp.edit().putBoolean(perm, true).apply()
            requestPermissions(arrayOf(perm), REQ)
        }
    }

    private fun safeStart(i: Intent): Boolean = try { startActivity(i); true } catch (e: Exception) { false }

    private fun openAppInfo() {
        safeStart(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
    }

    @SuppressLint("BatteryLife")
    private fun openPerm(name: String) {
        when (name) {
            "mic" -> askRuntime(Manifest.permission.RECORD_AUDIO)
            "notif" -> if (Build.VERSION.SDK_INT >= 33) askRuntime(Manifest.permission.POST_NOTIFICATIONS) else openAppInfo()
            "a11y" -> {
                Toast.makeText(this, "Open Wispr bubble (maybe under Installed / Downloaded apps) and turn it on", Toast.LENGTH_LONG).show()
                safeStart(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            "battery" -> {
                val ok = safeStart(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
                if (!ok) safeStart(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
            "appinfo" -> openAppInfo()
        }
    }

    // ---------- look ----------
    @Suppress("DEPRECATION")
    private fun applyBars() {
        val dark = store.isDark()
        val c = if (dark) DARK_BG else LIGHT_BG
        window.decorView.setBackgroundColor(c)
        if (Build.VERSION.SDK_INT < 35) {
            window.statusBarColor = c
            window.navigationBarColor = c
        }
        if (Build.VERSION.SDK_INT >= 30) {
            val mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            window.insetsController?.setSystemBarsAppearance(if (dark) 0 else mask, mask)
        } else {
            window.decorView.systemUiVisibility =
                if (dark) 0 else (View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR)
        }
        if (::web.isInitialized) web.setBackgroundColor(c)
    }

    // ---------- JS helpers ----------
    private fun js(code: String) {
        runOnUiThread { if (!isFinishing && !isDestroyed && ::web.isInitialized) web.evaluateJavascript(code, null) }
    }

    private fun cb(id: String, ok: Boolean, data: String) =
        js("window.WisprCb&&WisprCb(${JSONObject.quote(id)},$ok,${JSONObject.quote(data)})")

    private fun status(id: String, msg: String) =
        js("window.WisprStatus&&WisprStatus(${JSONObject.quote(id)},${JSONObject.quote(msg)})")

    /** The only door between the screen and the native side. The API key can go in, but never comes out. */
    inner class Bridge {
        @JavascriptInterface
        fun getSettings(): String = store.publicJson()

        @JavascriptInterface
        fun setSetting(k: String, v: String): Boolean {
            val value = try { JSONTokener(v).nextValue() } catch (e: Exception) { return false }
            val ok = store.putFromUi(k, value)
            if (ok && k == "dark") runOnUiThread { applyBars() }
            if (ok && k == "bubble") BubbleService.instance?.refresh()
            return ok
        }

        @JavascriptInterface
        fun verifyKey(k: String, id: String) {
            val key = Groq.cleanKey(k)
            if (!key.startsWith("gsk_")) {
                cb(id, false, "That doesn't look like a Groq key. It should start with gsk_")
                return
            }
            if (!KEY_RE.matches(key)) {
                cb(id, false, "This key looks cut off or has extra characters (${key.length} chars). Copy it again from console.groq.com/keys.")
                return
            }
            Engine.io {
                try {
                    Groq.verify(key)
                    store.apiKey = key
                    cb(id, true, "ok")
                } catch (e: GroqError) {
                    if (e.retryable) {
                        // No internet / Groq busy: don't block the user. Save it; the first dictation re-checks it.
                        store.apiKey = key
                        cb(id, true, "saved-unverified")
                    } else {
                        cb(id, false, e.message ?: "Couldn't verify the key")
                    }
                }
            }
        }

        @JavascriptInterface
        fun removeKey() { store.apiKey = "" }

        @JavascriptInterface
        fun startRec(): Boolean {
            if (!Perms.mic(this@MainActivity)) {
                runOnUiThread { askRuntime(Manifest.permission.RECORD_AUDIO) }
                return false
            }
            if (recorder.isRecording) return true
            val ok = recorder.start()
            if (ok) Beep.play(this@MainActivity, true)
            return ok
        }

        @JavascriptInterface
        fun level(): Float = recorder.level()

        @JavascriptInterface
        fun stopRec(id: String) {
            val f = recorder.stop()
            Beep.play(this@MainActivity, false)
            if (f == null) {
                cb(id, false, "Too short. Hold on a moment longer.")
                return
            }
            Engine.submit(this@MainActivity, f, { m -> status(id, m) }) { ok, text, _ -> cb(id, ok, text) }
        }

        @JavascriptInterface
        fun cancelRec() { recorder.cancel() }

        @JavascriptInterface
        fun getHistory(): String = store.historyJson()

        @JavascriptInterface
        fun retry(itemId: String, id: String) {
            Engine.retry(this@MainActivity, itemId, { m -> status(id, m) }) { ok, text, _ -> cb(id, ok, text) }
        }

        @JavascriptInterface
        fun clearHistory() { store.clearHistory() }

        @JavascriptInterface
        fun copy(text: String): Boolean = Clip.copy(this@MainActivity, text)

        @JavascriptInterface
        fun share(text: String) {
            runOnUiThread {
                runCatching {
                    startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Share"))
                }
            }
        }

        @JavascriptInterface
        fun perms(): String = Perms.json(this@MainActivity)

        @JavascriptInterface
        fun requestPerm(name: String) { runOnUiThread { openPerm(name) } }

        @JavascriptInterface
        fun notice(): String = Owner.NOTICE

        /** Model catalog (all Groq free-plan models) for the Settings pickers. */
        @JavascriptInterface
        fun models(): String = Models.json(null)

        // ---------- in-app updates (GitHub Releases) ----------
        @JavascriptInterface
        fun updateState(): String = Updater.stateJson(this@MainActivity)

        @JavascriptInterface
        fun checkUpdate() { Updater.checkNow(this@MainActivity) }

        @JavascriptInterface
        fun startUpdate() { runOnUiThread { this@MainActivity.startUpdate() } }

        @JavascriptInterface
        fun cancelUpdate() { Updater.cancelDownload() }

        @JavascriptInterface
        fun skipUpdate(version: String) { Updater.skip(this@MainActivity, version.take(40)) }

        @JavascriptInterface
        fun openReleasePage() {
            val page = runCatching { JSONObject(Updater.stateJson(this@MainActivity)).optString("page") }.getOrDefault("")
            if (page.startsWith("https://github.com/")) runOnUiThread { safeStart(Intent(Intent.ACTION_VIEW, Uri.parse(page))) }
        }
    }

    companion object {
        /** True while the app screen is on top (the updater then shows a banner instead of a notification). */
        @Volatile var visible = false
        private const val HOST = "appassets.androidplatform.net"
        private const val REQ = 7
        private val KEY_RE = Regex("^gsk_[A-Za-z0-9_-]{20,200}$")
        private val LIGHT_BG = 0xFFFBF7F2.toInt()
        private val DARK_BG = 0xFF120D09.toInt()
    }
}
