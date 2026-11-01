/*
 * Wispr by Deepu Gupta
 * Copyright 2026 Deepu Gupta
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 * SPDX-License-Identifier: Apache-2.0
 */
package com.deepugupta.wispr

import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

/**
 * Types text into another app's text box (Wispr Flow style) and NEVER deletes what is already there.
 *
 * The v3 bug ("Notes / Docs / Word lose old text after save"): when a paste could not be verified, v3 fell back
 * to ACTION_SET_TEXT, which REPLACES the whole field. Rich editors (Google Docs, Word, Samsung Notes, Keep after
 * saving) report only part of their text, or their placeholder, or a select-all selection, so "old + new" became "new".
 *
 * v4 rules:
 *  1. Insert with ACTION_PASTE at the cursor. An insert cannot remove anything.
 *  2. A selection is collapsed to its end first, so a paste never replaces selected text.
 *  3. If the box was not focused (e.g. after Save), it is focused and the cursor goes to the END.
 *  4. SET_TEXT is used only for plain Android EditText fields outside notes/docs apps, whose full text was read
 *     twice identically; the new value always contains every old character.
 *  5. If a plain field ever ends up shorter than before, the old text is put back.
 *  6. Anything else -> text stays on the clipboard and the bubble shows a Paste badge.
 */
class Inserter(private val svc: AccessibilityService) {
    private val h = Handler(Looper.getMainLooper())
    private var target: AccessibilityNodeInfo? = null
    private var targetPkg: String? = null

    /** Remember the box the user is in (call when recording starts or the panel opens). */
    fun remember() {
        val n = focusedEditable()
        if (n != null && n.packageName?.toString() != svc.packageName) {
            target = n
            targetPkg = n.packageName?.toString()
        }
    }

    fun forget() { target = null; targetPkg = null }

    /** True if there is a box we can type into right now. */
    fun hasTarget(): Boolean = findTarget() != null

    /** @param done true = typed into the box; false = couldn't, the text is on the clipboard. */
    fun insert(text: String, done: (Boolean) -> Unit) = attempt(text, 0, done)

    private fun attempt(text: String, n: Int, done: (Boolean) -> Unit) {
        val node = findTarget()
        if (node == null) { retryLater(text, n, done); return }
        val wasFocused = node.isFocused
        if (!wasFocused) node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        h.postDelayed({ pasteInto(node, text, n, !wasFocused, done) }, if (wasFocused) 0L else 200L)
    }

    private fun retryLater(text: String, n: Int, done: (Boolean) -> Unit) {
        if (n < 3) h.postDelayed({ attempt(text, n + 1, done) }, 250L * (n + 1)) else done(false)
    }

    private fun pasteInto(node: AccessibilityNodeInfo, text: String, n: Int, toEnd: Boolean, done: (Boolean) -> Unit) {
        runCatching { node.refresh() }
        val before = readText(node)
        val sel = selection(node, before)
        var s = sel.first
        val e = sel.second
        if (before != null && (toEnd || s != e)) {
            s = if (toEnd) before.length else e
            setCursor(node, s)
        }
        val pad = if (before != null && s > 0 && s <= before.length && !before[s - 1].isWhitespace()) " " else ""
        val piece = pad + text
        Clip.copy(svc, piece)
        val pasted = node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        verify(node, before, s, piece, text, pasted, n, 0, done)
    }

    private fun verify(
        node: AccessibilityNodeInfo, before: String?, s: Int, piece: String, text: String,
        pasted: Boolean, n: Int, check: Int, done: (Boolean) -> Unit
    ) {
        h.postDelayed({
            runCatching { node.refresh() }
            val after = readText(node)
            val probe = text.trim().take(24)
            val landed = after != null && after.contains(probe) &&
                (before == null || !before.contains(probe) || after.length > before.length)
            val shrank = before != null && after != null && after.length < before.length
            when {
                landed -> done(true)
                shrank -> { restore(node, before!!); done(false) }
                pasted && check < CHECKS.size - 1 -> verify(node, before, s, piece, text, pasted, n, check + 1, done)
                // App hides its text (Docs, Word, many WebViews): trust the paste. Never paste twice (no duplicates).
                pasted && (after == null || before == null) -> done(true)
                safeSetText(node, before, s, piece) -> done(true)
                !pasted && n < 3 -> retryLater(text, n, done)
                else -> done(false)
            }
        }, CHECKS[check])
    }

    /** Last resort for plain fields only. The result always keeps every old character. */
    private fun safeSetText(node: AccessibilityNodeInfo, before: String?, s: Int, piece: String): Boolean {
        if (before == null || !isPlainField(node)) return false
        runCatching { node.refresh() }
        if (readText(node) != before) return false // text moving under us: don't touch it
        val cut = s.coerceIn(0, before.length)
        val nt = before.substring(0, cut) + piece + before.substring(cut)
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, nt) }
        if (!node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return false
        setCursor(node, cut + piece.length)
        runCatching { node.refresh() }
        val after = readText(node)
        if (after != null && after.length < before.length) { restore(node, before); return false }
        return true
    }

    private fun restore(node: AccessibilityNodeInfo, old: String) {
        if (!isPlainField(node) || old.isEmpty()) return
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, old) }
        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun setCursor(node: AccessibilityNodeInfo, p: Int) {
        node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, p)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, p)
        })
    }

    /** A classic single Android text field whose text we can trust. Notes/docs/office editors never qualify. */
    private fun isPlainField(n: AccessibilityNodeInfo): Boolean {
        val cls = n.className?.toString() ?: return false
        val pkg = n.packageName?.toString() ?: return false
        return cls in PLAIN_CLASSES && n.childCount == 0 && !RICH_APPS.containsMatchIn(pkg)
    }

    /** Text in the box; "" if it only shows its placeholder; null if the app hides it. */
    private fun readText(n: AccessibilityNodeInfo): String? {
        if (Build.VERSION.SDK_INT >= 26 && n.isShowingHintText) return ""
        val t = n.text?.toString() ?: return null
        val hint = if (Build.VERSION.SDK_INT >= 26) n.hintText?.toString() else null
        return if (hint != null && t == hint) "" else t
    }

    private fun selection(n: AccessibilityNodeInfo, cur: String?): Pair<Int, Int> {
        val len = cur?.length ?: 0
        var s = n.textSelectionStart
        if (s < 0 || s > len) s = len
        var e = n.textSelectionEnd
        if (e < s || e > len) e = s
        return s to e
    }

    private fun editable(n: AccessibilityNodeInfo?): AccessibilityNodeInfo? =
        n?.takeIf { it.isEditable && it.isEnabled && !it.isPassword }

    /** Focused text box, searched across every window (keyboard overlays can hide it from rootInActiveWindow). */
    fun focusedEditable(): AccessibilityNodeInfo? {
        editable(runCatching { svc.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }.getOrNull())?.let { return it }
        val ws = runCatching { svc.windows }.getOrNull() ?: return null
        for (w in ws) {
            if (w.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD || w.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) continue
            val root = runCatching { w.root }.getOrNull() ?: continue
            editable(runCatching { root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }.getOrNull())?.let { return it }
        }
        return null
    }

    /** The box to type into now: the focused one (not ours), else the one remembered earlier, if it still exists. */
    private fun findTarget(): AccessibilityNodeInfo? {
        val f = focusedEditable()
        if (f != null && f.packageName?.toString() != svc.packageName) return f
        val t = target ?: return null
        val alive = runCatching { t.refresh() }.getOrDefault(false)
        return if (alive && editable(t) != null && t.packageName?.toString() == targetPkg) t else null
    }

    companion object {
        private val CHECKS = longArrayOf(160, 320, 650)
        private val PLAIN_CLASSES = setOf(
            "android.widget.EditText", "android.widget.AutoCompleteTextView", "android.widget.MultiAutoCompleteTextView"
        )
        /** Notes, docs and office apps: paste only, never SET_TEXT. */
        private val RICH_APPS = Regex("note|docs|office|word|keep|evernote|notion|onenote|writer|editor|memo|journal", RegexOption.IGNORE_CASE)
    }
}
