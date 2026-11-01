/*
 * Wispr by Deepu Gupta
 * Copyright 2026 Deepu Gupta
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 * SPDX-License-Identifier: Apache-2.0
 */
package com.deepugupta.wispr

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * The whole floating bubble, drawn by hand (no popping / bouncing):
 *  - Idle: a glowing orange orb with a slowly flowing particle wave inside.
 *  - Recording: smoothly opens into a pill:  [ X cancel ]  [ live orange waveform ]  [ ✓ done ]
 *  - Writing: the orb's wave speeds up and an orange arc circles the rim.
 *  - Retry / Paste: orb + a small badge.
 */
class BubbleView(ctx: Context) : View(ctx) {

    enum class Mode { IDLE, REC, WORK, RETRY, PASTE }
    enum class Zone { ORB, CANCEL, WAVE, DONE }

    private val dp = resources.displayMetrics.density
    private fun d(v: Float) = v * dp

    var mode: Mode = Mode.IDLE
        private set
    private var expanded = false
    private var reveal = 1f
    private var revealAnim: ValueAnimator? = null
    private var phase = 0f
    private var lastT = 0L
    private var level = 0f
    private val bars = FloatArray(26)

    private val orbBox get() = d(68f)
    private val pillW get() = d(248f)
    private val pillH get() = d(60f)

    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val body = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val rect = RectF()
    private var shaderKey = ""

    private val retryIcon = ctx.getDrawable(R.drawable.ic_b_retry)?.mutate()?.apply { setTint(Color.WHITE) }
    private val pasteIcon = ctx.getDrawable(R.drawable.ic_b_paste)?.mutate()?.apply { setTint(Color.WHITE) }

    init {
        contentDescription = "Start Wispr dictation"
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        lastT = 0L
        invalidate()
    }

    fun setMode(m: Mode) {
        if (m == mode) return
        val wasExpanded = expanded
        mode = m
        expanded = m == Mode.REC
        if (m == Mode.REC) bars.fill(0f)
        contentDescription = when (m) {
            Mode.IDLE -> "Start Wispr dictation"
            Mode.REC -> "Listening. Tap the tick to type it, or the cross to cancel"
            Mode.WORK -> "Writing"
            Mode.RETRY -> "Retry dictation"
            Mode.PASTE -> "Paste text"
        }
        if (wasExpanded != expanded) {
            requestLayout()
            revealAnim?.cancel()
            reveal = 0f
            revealAnim = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 240
                interpolator = DecelerateInterpolator(1.6f)
                addUpdateListener { reveal = it.animatedValue as Float; invalidate() }
                start()
            }
        }
        invalidate()
    }

    /** Raw mic amplitude 0..1, fed ~every 50 ms while recording. */
    fun pushLevel(raw: Float) {
        val target = (raw * 3.2f).coerceIn(0f, 1f)
        level += (target - level) * (if (target > level) 0.55f else 0.25f)
        System.arraycopy(bars, 1, bars, 0, bars.size - 1)
        bars[bars.size - 1] = level
    }

    fun zoneAt(x: Float): Zone {
        if (!expanded) return Zone.ORB
        return when {
            x < pillH -> Zone.CANCEL
            x > width - pillH -> Zone.DONE
            else -> Zone.WAVE
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (expanded) setMeasuredDimension(pillW.toInt(), pillH.toInt())
        else setMeasuredDimension(orbBox.toInt(), orbBox.toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val now = SystemClock.uptimeMillis()
        val dt = if (lastT == 0L) 0f else min(0.1f, (now - lastT) / 1000f)
        lastT = now
        val speed = when (mode) { Mode.REC -> 2.6f + level * 5f; Mode.WORK -> 5.5f; else -> 1.1f }
        phase = ((phase + dt * speed) % (2f * PI.toFloat() * 100f))

        if (expanded) drawPill(canvas) else drawOrbBox(canvas)

        // Smooth 60 fps only while something is happening; idle orb breathes at ~24 fps to save battery.
        if (isAttachedToWindow && visibility == VISIBLE) {
            if (mode == Mode.IDLE || mode == Mode.RETRY || mode == Mode.PASTE) postInvalidateDelayed(42) else postInvalidateOnAnimation()
        }
    }

    private fun drawOrbBox(c: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val a = if (reveal < 1f) reveal else 1f
        val r = d(25f) * (0.92f + 0.08f * a)
        val amp = when (mode) { Mode.WORK -> 1.25f; else -> 1f }
        drawOrb(c, cx, cy, r, amp, a)
        if (mode == Mode.WORK) {
            arc.strokeWidth = d(2.6f)
            arc.color = 0xFFFFB25C.toInt()
            arc.alpha = (230 * a).toInt()
            val start = (phase * 57.3f * 1.6f) % 360f
            rect.set(cx - r - d(3f), cy - r - d(3f), cx + r + d(3f), cy + r + d(3f))
            c.drawArc(rect, start, 80f, false, arc)
        }
        val icon = when (mode) { Mode.RETRY -> retryIcon; Mode.PASTE -> pasteIcon; else -> null }
        if (icon != null) {
            val br = d(10f)
            val bx = cx + r * 0.72f
            val by = cy + r * 0.72f
            fill.shader = null
            fill.color = if (mode == Mode.RETRY) 0xFFE8590C.toInt() else 0xFFFF8A1F.toInt()
            c.drawCircle(bx, by, br, fill)
            stroke.shader = null; stroke.color = 0xFF1A0A02.toInt(); stroke.strokeWidth = d(1.5f)
            c.drawCircle(bx, by, br, stroke)
            val s = d(7f)
            icon.setBounds((bx - s).toInt(), (by - s).toInt(), (bx + s).toInt(), (by + s).toInt())
            icon.draw(c)
        }
    }

    /** Dark glass sphere, orange rim glow, a flowing 3-D ribbon of dots and a bright wave line (like the reference). */
    private fun drawOrb(c: Canvas, cx: Float, cy: Float, r: Float, amp: Float, alpha: Float) {
        val key = "$cx|$cy|$r"
        if (key != shaderKey) {
            shaderKey = key
            glow.shader = RadialGradient(cx, cy, r * 1.32f,
                intArrayOf(0x00FF7A1A, 0x00FF7A1A, 0x99FF7A1A.toInt(), 0x33FF7A1A, 0x00FF7A1A),
                floatArrayOf(0f, 0.66f, 0.76f, 0.86f, 1f), Shader.TileMode.CLAMP)
            body.shader = RadialGradient(cx, cy + r * 0.25f, r,
                intArrayOf(0xFF3A1804.toInt(), 0xFF1C0A02.toInt(), 0xFF0C0501.toInt()),
                floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
        }
        val a255 = (255 * alpha).toInt()
        glow.alpha = a255
        c.drawCircle(cx, cy, r * 1.32f, glow)
        body.alpha = a255
        c.drawCircle(cx, cy, r, body)

        // particle ribbon
        val cols = 15
        val rows = 7
        val waveA = r * 0.30f * amp * (if (mode == Mode.REC) 0.7f + level else 1f)
        for (i in 0 until cols) {
            val u = i / (cols - 1f) * 2f - 1f
            val x = cx + u * r * 0.86f
            val base = cy + waveA * sin(u * PI.toFloat() * 1.2f + phase)
            val twist = cos(u * PI.toFloat() * 1.05f + phase * 0.7f)
            val edge = 1f - abs(u) * 0.55f
            for (j in 0 until rows) {
                val v = j / (rows - 1f) * 2f - 1f
                val y = base + v * r * 0.34f * twist
                if (hypot(x - cx, y - cy) > r * 0.9f) continue
                val depth = (v * twist + 1f) / 2f
                dot.color = if (depth > 0.6f) 0xFFFFC27A.toInt() else 0xFFFF8A2A.toInt()
                dot.alpha = ((50 + 190 * depth * edge) * alpha).toInt().coerceIn(0, 255)
                c.drawCircle(x, y, d(0.7f + 0.9f * depth), dot)
            }
        }
        // glowing wave line
        path.reset()
        val steps = 28
        for (k in 0..steps) {
            val u = k / steps.toFloat() * 2f - 1f
            val x = cx + u * r * 0.96f
            val y = cy + waveA * sin(u * PI.toFloat() * 1.2f + phase)
            if (k == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        line.strokeWidth = d(4.5f); line.color = 0xFFFF7A1A.toInt(); line.alpha = (70 * alpha).toInt()
        c.drawPath(path, line)
        line.strokeWidth = d(1.5f); line.color = 0xFFFFD9A0.toInt(); line.alpha = (240 * alpha).toInt()
        c.drawPath(path, line)

        rim.strokeWidth = d(1.4f)
        rim.color = 0xFFFFA24A.toInt()
        rim.alpha = (210 * alpha).toInt()
        c.drawCircle(cx, cy, r, rim)
    }

    private fun drawPill(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val t = reveal
        val cw = h + (w - h) * t           // pill opens from the centre, smoothly
        val left = (w - cw) / 2f
        val rad = h / 2f
        rect.set(left + d(1f), d(1f), left + cw - d(1f), h - d(1f))
        fill.shader = null
        fill.color = 0xF2140A04.toInt()
        c.drawRoundRect(rect, rad, rad, fill)
        stroke.shader = null
        stroke.color = 0xFFFF8A1F.toInt(); stroke.alpha = 110; stroke.strokeWidth = d(1.2f)
        c.drawRoundRect(rect, rad, rad, stroke)
        if (t < 0.35f) return
        val ca = ((t - 0.35f) / 0.65f).coerceIn(0f, 1f)

        // X (cancel)
        val bR = d(20f)
        val xL = left + d(6f) + bR
        val cy = h / 2f
        fill.color = 0xFF2E2620.toInt(); fill.alpha = (255 * ca).toInt()
        c.drawCircle(xL, cy, bR, fill)
        stroke.color = Color.WHITE; stroke.alpha = (235 * ca).toInt(); stroke.strokeWidth = d(2.2f)
        val s = d(6.5f)
        c.drawLine(xL - s, cy - s, xL + s, cy + s, stroke)
        c.drawLine(xL + s, cy - s, xL - s, cy + s, stroke)

        // ✓ (done)
        val xR = left + cw - d(6f) - bR
        fill.shader = LinearGradient(xR, cy - bR, xR, cy + bR, 0xFFFFA340.toInt(), 0xFFF06A0A.toInt(), Shader.TileMode.CLAMP)
        fill.alpha = (255 * ca).toInt()
        c.drawCircle(xR, cy, bR, fill)
        fill.shader = null
        stroke.color = Color.WHITE; stroke.alpha = (255 * ca).toInt(); stroke.strokeWidth = d(2.6f)
        path.reset()
        path.moveTo(xR - d(7f), cy + d(0.5f))
        path.lineTo(xR - d(2f), cy + d(5.5f))
        path.lineTo(xR + d(7.5f), cy - d(5f))
        c.drawPath(path, stroke)

        // live waveform (tall in the middle, reacts to your voice, never fully still)
        val wl = xL + bR + d(10f)
        val wr = xR - bR - d(10f)
        val n = bars.size
        val gap = (wr - wl) / n
        val bw = max(d(2.2f), gap * 0.42f)
        val maxH = h * 0.62f
        val minH = d(3.5f)
        bar.shader = LinearGradient(0f, cy - maxH / 2f, 0f, cy + maxH / 2f,
            intArrayOf(0xFFFFC27A.toInt(), 0xFFFF8A1F.toInt(), 0xFFFFC27A.toInt()), null, Shader.TileMode.CLAMP)
        bar.alpha = (255 * ca).toInt()
        for (i in 0 until n) {
            val env = 0.35f + 0.65f * sin(PI.toFloat() * (i + 0.5f) / n)
            val idle = 0.10f + 0.07f * sin(phase * 2.2f + i * 0.55f)
            val v = max(idle, bars[i]) * env
            val bh = minH + (maxH - minH) * v.coerceIn(0f, 1f)
            val x = wl + gap * i + (gap - bw) / 2f
            rect.set(x, cy - bh / 2f, x + bw, cy + bh / 2f)
            c.drawRoundRect(rect, bw / 2f, bw / 2f, bar)
        }
        bar.shader = null
    }

    override fun onDetachedFromWindow() {
        revealAnim?.cancel()
        lastT = 0L
        super.onDetachedFromWindow()
    }
}
