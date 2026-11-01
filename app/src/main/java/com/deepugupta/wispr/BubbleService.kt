/*
 * Wispr by Deepu Gupta
 * Copyright 2026 Deepu Gupta
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 * SPDX-License-Identifier: Apache-2.0
 */
package com.deepugupta.wispr

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Toast

/**
 * The Wispr bubble, modelled on how Wispr Flow works on Android:
 *  - an orange orb floats above ANY keyboard whenever a text box is focused;
 *  - tap it -> it opens into  [X]  [waveform]  [✓];  ✓ stops, Groq writes it, and it is typed where you were;
 *  - HOLD it -> the mini Wispr window (modes, Hindi toggle, AI models, AI chat, Understand screen)
 *    (Settings can switch hold back to push-to-talk);
 *  - Android's Accessibility button / shortcut opens "Understand screen" from ANY app, even without a keyboard;
 *  - text is only ever inserted, never replaces what is in the box (see [Inserter]);
 *  - if an app refuses insertion, the text is on the clipboard and the orb shows a Paste badge.
 */
class BubbleService : AccessibilityService() {

    companion object {
        @Volatile var instance: BubbleService? = null
    }

    private enum class St { IDLE, REC, WORK, RETRY, PASTE }

    private val h = Handler(Looper.getMainLooper())
    private lateinit var wm: WindowManager
    private lateinit var store: Store
    private lateinit var recorder: Recorder
    private var view: BubbleView? = null
    private val lp = WindowManager.LayoutParams()
    private var shown = false
    private var st = St.IDLE
    private lateinit var inserter: Inserter
    private lateinit var panel: Panel
    private var failedId: String? = null
    private var pasteText: String? = null
    private var imeTop = 0
    private var yOff = Int.MIN_VALUE
    private var rightSide = true
    private var hold = false
    private var holdOpened = false
    private var dragging = false
    private var downZone = BubbleView.Zone.ORB
    private var downX = 0f
    private var downY = 0f
    private var capLight = false // bubble is currently showing the capture state
    private var startX = 0
    private var startY = 0
    private val storeListener: () -> Unit = { refresh() }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        store = Store.get(this)
        recorder = Recorder(applicationContext)
        inserter = Inserter(this)
        panel = Panel(this)
        val sp = getSharedPreferences("bubble_pos", MODE_PRIVATE)
        rightSide = sp.getBoolean("right", true)
        yOff = sp.getInt("yoff", Int.MIN_VALUE)
        Notif.ensure(this)
        buildViews()
        store.addListener(storeListener)
        // First look as soon as the service is enabled, without waiting for an event:
        // the Floating Assistant shows immediately if switched on (keyboard or not).
        h.postDelayed(evalRun, 400)
        Updater.maybeCheck(this, background = true)
    }

    private fun dp(v: Float): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    private fun buildViews() {
        view = BubbleView(this).apply { setOnTouchListener(touch) }
        lp.width = WindowManager.LayoutParams.WRAP_CONTENT
        lp.height = WindowManager.LayoutParams.WRAP_CONTENT
        lp.type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        // Never take focus: the app's text box keeps its cursor, so we can type into it.
        lp.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        lp.format = PixelFormat.TRANSLUCENT
        placeSide()
        lp.y = 0
    }

    private var centred = false

    private fun placeSide() {
        centred = false
        lp.gravity = Gravity.TOP or (if (rightSide) Gravity.END else Gravity.START)
        lp.x = dp(6f)
    }

    private fun placeCentre() {
        centred = true
        lp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        lp.x = 0
    }

    private fun relayout() { view?.let { v -> if (shown) runCatching { wm.updateViewLayout(v, lp) } } }

    // ---------- gestures ----------
    private val holdRun = Runnable {
        if (dragging) return@Runnable
        when (st) {
            St.IDLE -> if (store.str("hold") == "talk") { hold = true; startRec() } else { holdOpened = true; openPanel("home", false) }
            St.RETRY, St.PASTE -> { failedId = null; pasteText = null; setState(St.IDLE); evaluate() }
            else -> {}
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private val touch = View.OnTouchListener { v, e ->
        val bv = v as BubbleView
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.rawX; downY = e.rawY; startX = lp.x; startY = lp.y
                dragging = false; hold = false; holdOpened = false
                downZone = bv.zoneAt(e.x)
                if (st != St.REC && st != St.WORK) h.postDelayed(holdRun, 420)
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.rawX - downX
                val dy = e.rawY - downY
                if (!dragging && !hold && st != St.REC && Math.hypot(dx.toDouble(), dy.toDouble()) > dp(10f)) {
                    dragging = true
                    h.removeCallbacks(holdRun)
                }
                if (dragging) {
                    lp.x = if (rightSide) startX - dx.toInt() else startX + dx.toInt()
                    lp.y = startY + dy.toInt()
                    relayout()
                }
            }
            MotionEvent.ACTION_UP -> {
                h.removeCallbacks(holdRun)
                when {
                    // push-to-talk: release = type it; release over X = cancel
                    hold -> { hold = false; if (bv.zoneAt(e.x) == BubbleView.Zone.CANCEL) cancelRec() else stopRec() }
                    holdOpened -> holdOpened = false // the panel is open; lifting the finger must not start recording
                    dragging -> { dragging = false; snap() }
                    else -> tap(downZone)
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                h.removeCallbacks(holdRun)
                dragging = false
                if (hold) { hold = false; stopRec() }
            }
        }
        true
    }

    private fun tap(zone: BubbleView.Zone) {
        when (st) {
            St.IDLE -> startRec()
            St.REC -> when (zone) {
                BubbleView.Zone.CANCEL -> cancelRec()
                BubbleView.Zone.DONE -> stopRec()
                else -> {} // like Flow: the waveform itself does nothing
            }
            St.WORK -> toast("Writing it down…")
            St.RETRY -> failedId?.let { retryItem(it) }
            St.PASTE -> pasteAgain()
        }
    }

    private fun snap() {
        val sw = screenW()
        val w = view?.width ?: 0
        val left = if (rightSide) sw - lp.x - w else lp.x
        rightSide = left + w / 2 > sw / 2
        placeSide()
        yOff = lp.y - imeTop
        getSharedPreferences("bubble_pos", MODE_PRIVATE).edit().putBoolean("right", rightSide).putInt("yoff", yOff).apply()
        relayout()
    }

    // ---------- when to show ----------
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        h.removeCallbacks(evalRun)
        h.postDelayed(evalRun, 90)
    }

    override fun onInterrupt() {}

    private val evalRun = Runnable { evaluate() }

    fun refresh() { h.post(evalRun) }

    private fun imeWindow(): AccessibilityWindowInfo? =
        try { windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD } } catch (e: Exception) { null }

    /**
     * Explicit states: capture active > dictation active > paste badge > Floating Assistant
     * setting > keyboard-ONLY dictation refinements. The Floating Assistant is INDEPENDENT of
     * the keyboard: when enabled it shows over ANY app (YouTube, music player, anything),
     * with the keyboard closed; the IME only affects optional dictation refinements (a
     * password/numeric-focused box is a poor dictation target) and the resting position.
     */
    private fun evaluate() {
        if (view == null) return
        // While internal-audio capture runs, the bubble must stay visible over ANY app,
        // keyboard or not: it is the capture's state light (spinning arc = recording).
        val capturing = try { PlaybackCaptureService.isRecording } catch (e: Exception) { false }
        if (capturing && st == St.IDLE) {
            capLight = true
            view?.setMode(BubbleView.Mode.WORK)
            show()
            return
        }
        if (capLight && !capturing) {
            capLight = false
            if (st == St.IDLE) view?.setMode(BubbleView.Mode.IDLE)
        }
        val ime = imeWindow()
        if (ime != null) {
            val r = Rect()
            ime.getBoundsInScreen(r)
            if (r.top > 0) imeTop = r.top
        } else {
            imeTop = 0 // keyboard is gone: never park the orb against a dead keyboard edge
        }
        // Actively-delivery states override everything (they end on their own).
        if (st == St.REC || st == St.WORK || st == St.RETRY || st == St.PASTE) { show(); return }
        // The full panel replaces the orb for its lifetime.
        if (::panel.isInitialized && panel.isOpen) { hide(); return }
        // Floating Assistant disabled: gone from every app, keyboard or not.
        if (!store.bool("bubble")) { hide(); return }
        // Floating Assistant enabled: visible over ANY app, with or without a keyboard.
        val focus = inserter.focusedEditable()
        // Own app: no orb needed over Wispr itself (its own UI has the same actions).
        if (focus != null && focus.packageName?.toString() == packageName) { hide(); return }
        if (focus == null && rootInActiveWindow?.packageName?.toString() == packageName) { hide(); return }
        // Dictation-specific refinement, keyboard context only: a password or numeric-only
        // box is what the orb is FOR with a keyboard. Never let it shrink global visibility.
        if (ime != null && focus != null && (focus.isPassword || numeric(focus))) { hide(); return }
        show()
    }

    private fun numeric(n: AccessibilityNodeInfo): Boolean {
        val c = n.inputType and InputType.TYPE_MASK_CLASS
        return c == InputType.TYPE_CLASS_NUMBER || c == InputType.TYPE_CLASS_PHONE
    }

    fun screenW(): Int = if (Build.VERSION.SDK_INT >= 30) wm.currentWindowMetrics.bounds.width() else resources.displayMetrics.widthPixels
    fun screenH(): Int = if (Build.VERSION.SDK_INT >= 30) wm.currentWindowMetrics.bounds.height() else resources.displayMetrics.heightPixels

    private fun show() {
        val v = view ?: return
        if (!shown) Updater.maybeCheck(this, background = true) // cheap: network at most every 12 h
        if (!dragging) {
            val size = dp(68f)
            val base = if (imeTop > 0) imeTop else screenH() - dp(320f)
            val off = if (yOff == Int.MIN_VALUE || st == St.REC) -size - dp(6f) else yOff
            lp.y = (base + off).coerceIn(dp(40f), maxOf(dp(40f), screenH() - size))
        }
        if (!shown) {
            try { wm.addView(v, lp); shown = true } catch (e: Exception) { /* overlay not ready yet */ }
        } else if (!dragging) {
            relayout()
        }
    }

    private fun hide() {
        val v = view ?: return
        if (shown) {
            runCatching { wm.removeView(v) }
            shown = false
        }
    }

    // ---------- recording ----------
    private val levelRun = object : Runnable {
        override fun run() {
            if (st != St.REC) return
            view?.pushLevel(recorder.level())
            h.postDelayed(this, 50)
        }
    }
    private val maxRun = Runnable { stopRec() }

    private fun startRec() {
        if (!Perms.mic(this)) { hold = false; toast("Allow the microphone for Wispr first"); openApp("mic"); return }
        if (store.apiKey.isBlank()) { hold = false; toast("Open Wispr and add your Groq key first"); openApp(null); return }
        inserter.remember()
        goForeground()
        if (!recorder.start()) {
            stopFg()
            hold = false
            toast("Mic is busy in another app")
            return
        }
        Beep.play(this, true)
        setState(St.REC)
        h.post(levelRun)
        h.postDelayed(maxRun, 15 * 60 * 1000L)
    }

    private fun stopTimers() {
        h.removeCallbacks(levelRun)
        h.removeCallbacks(maxRun)
    }

    private fun stopRec() {
        if (st != St.REC) return
        stopTimers()
        val f = recorder.stop()
        stopFg()
        Beep.play(this, false)
        if (f == null) {
            toast("Too short. Speak a little longer.")
            setState(St.IDLE)
            evaluate()
            return
        }
        setState(St.WORK)
        Engine.submit(this, f, { m -> toast(m) }) { ok, text, id -> onResult(ok, text, id) }
    }

    private fun cancelRec() {
        if (st != St.REC) return
        stopTimers()
        recorder.cancel()
        stopFg()
        setState(St.IDLE)
        toast("Discarded")
        evaluate()
    }

    fun retryItem(id: String) {
        h.post {
            if (st == St.REC || st == St.WORK) return@post
            failedId = id
            setState(St.WORK)
            show()
            Engine.retry(this, id, { m -> toast(m) }) { ok, text, rid -> onResult(ok, text, rid) }
        }
    }

    private fun onResult(ok: Boolean, text: String, id: String) {
        if (ok) {
            failedId = null
            Notif.cancelFail(this)
            deliver(text)
        } else if (id.isEmpty()) {
            toast(text)
            setState(St.IDLE)
            evaluate()
        } else {
            failedId = id
            setState(St.RETRY)
            show()
            toast(text)
            Notif.failed(this, id, text)
        }
    }

    // ---------- typing it in (never overwrites: see Inserter) ----------
    private fun deliver(text: String) {
        Clip.copy(this, text) // always on the clipboard too, so nothing is ever lost
        inserter.insert(text) { ok -> if (ok) done() else fallbackPaste(text) }
    }

    private fun done() {
        pasteText = null
        setState(St.IDLE)
        evaluate()
    }

    private fun fallbackPaste(text: String) {
        pasteText = text
        setState(St.PASTE)
        show()
        toast("Copied. Tap the text box, then tap the bubble to paste (or long-press → Paste)")
        h.postDelayed({ if (st == St.PASTE && pasteText == text) { pasteText = null; setState(St.IDLE); evaluate() } }, 15000)
    }

    private fun pasteAgain() {
        val t = pasteText ?: return
        if (!inserter.hasTarget()) { toast("Tap the text box first, then tap the bubble"); return }
        inserter.insert(t) { ok -> if (ok) done() else toast("This app blocks typing. Long-press the box → Paste") }
    }

    // ---------- used by the mini window (Panel) ----------
    fun openPanel(tab: String, runScreen: Boolean) {
        if (st == St.REC || st == St.WORK) { toast("Finish the current dictation first"); return }
        inserter.remember()
        failedId = null
        pasteText = null
        if (st != St.IDLE) setState(St.IDLE)
        hide()
        panel.open(tab, runScreen)
    }

    fun onPanelClosed() { h.postDelayed(evalRun, 200) }

    fun hasInsertTarget(): Boolean = inserter.hasTarget()

    fun startDictation() { if (st == St.IDLE) startRec() }

    /** "Insert" from the AI chat / screen answer into the box the user was in. */
    fun insertText(text: String) {
        Clip.copy(this, text)
        if (!inserter.hasTarget()) { toast("Copied. Long-press any text box → Paste"); return }
        inserter.insert(text) { ok -> if (!ok) fallbackPaste(text) else evaluate() }
    }

    /** Current top of the on-screen keyboard, 0 if none. */
    fun imeTopNow(): Int {
        val ime = imeWindow() ?: return 0
        val r = Rect()
        ime.getBoundsInScreen(r)
        return if (r.top > 0) r.top else 0
    }

    fun micForeground(on: Boolean) { if (on) goForeground() else if (st != St.REC) stopFg() }

    // ---------- look ----------
    private fun setState(s: St) {
        st = s
        val v = view ?: return
        val before = lp.gravity
        if (s == St.REC) placeCentre() else if (centred) placeSide()
        v.setMode(
            when (s) {
                St.IDLE -> BubbleView.Mode.IDLE
                St.REC -> BubbleView.Mode.REC
                St.WORK -> BubbleView.Mode.WORK
                St.RETRY -> BubbleView.Mode.RETRY
                St.PASTE -> BubbleView.Mode.PASTE
            }
        )
        if (before != lp.gravity) relayout()
    }

    // ---------- helpers ----------
    private fun goForeground() {
        try {
            val n = Notif.recording(this)
            if (Build.VERSION.SDK_INT >= 29) startForeground(Notif.ID_REC, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            else startForeground(Notif.ID_REC, n)
        } catch (e: Throwable) {
            // Recording still works from the accessibility service on most phones.
        }
    }

    private fun stopFg() {
        try { stopForeground(Service.STOP_FOREGROUND_REMOVE) } catch (e: Throwable) { /* not in foreground */ }
    }

    fun openApp(ask: String?) {
        try {
            val i = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (ask != null) i.putExtra("ask", ask)
            startActivity(i)
        } catch (e: Exception) { /* ignore */ }
    }

    fun toast(m: String) { h.post { Toast.makeText(applicationContext, m, Toast.LENGTH_SHORT).show() } }

    private fun cleanup() {
        if (instance === this) instance = null
        if (::store.isInitialized) store.removeListener(storeListener)
        if (::recorder.isInitialized) recorder.cancel()
        if (::panel.isInitialized) panel.close()
        hide()
        h.removeCallbacksAndMessages(null)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        cleanup()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        cleanup()
        super.onDestroy()
    }
}
