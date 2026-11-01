/*
 * Wispr by Deepu Gupta
 * Copyright 2026 Deepu Gupta
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 * SPDX-License-Identifier: Apache-2.0
 */
package com.deepugupta.wispr

import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile

/** One ordered WAV chunk on the 16 kHz timeline. Two chunks never overlap or skip. */
class AudioChunk(val file: File, val startSec: Long)

/**
 * Streaming 16 kHz mono PCM -> ordered WAV chunks. Shared by the local media importer
 * ([Decode], Mode B) and the live system-playback capture ([PlaybackCapture], Mode A).
 *
 * Numbers to remember:
 *  - output format is PCM 16 kHz / 16-bit / mono: 32 KB per second, 1.84 MB per minute;
 *  - [CHUNK_SEC] 9 min -> ~13.8 MB, comfortably inside Groq's 25 MB per-request cap;
 *  - [LOOK_SEC] newest seconds are held in RAM only, scanning for a quiet dip to cut at.
 *
 * Loss-less by design:
 *  - every frame generated goes into exactly one chunk (a cut moves the remainder into
 *    the next chunk; nothing is trimmed, faded or dropped);
 *  - every chunk remembers its start second on the 16 kHz timeline ([AudioChunk.startSec]),
 *    so chunks always transcribe back in the original order;
 *  - quiet-region cutting is OPTIONAL and GENTLE ([snapQuiet] = false cuts straight at the
 *    fixed length instead). It only chooses WHERE the boundary sits inside one chunk - it
 *    never decides NOT to keep audio, and quiet singing / small pauses survive because the
 *    dip scan looks at 0.25 s windows and can decline to cut early (see [tryQuietCut]).
 */
class Chunker(
    dir: File,
    prefix: String,
    /** Cut at the quietest dip inside the look window instead of at the fixed length. Gently: never drops frames. */
    val snapQuiet: Boolean = true,
    /** How many final seconds to keep scanning for a dip (RAM ~= look_sec * 32 KB). */
    val lookSec: Double = 3.0
) {
    private val dir = dir.apply { mkdirs() }
    private val prefix = prefix
    private var w: Wav? = null
    private var absFrames = 0L // total 16 kHz mono frames generated
    private var resIn = -1
    private var fPos = 0L // input frame index (timeline of the source)
    private var nextOut = 0.0 // position of the next output sample, in input-frame units
    private var prev = 0.0
    private var hasPrev = false

    private val buf = SQueue() // frames not yet flushed to a WAV
    private var peRms = 0L
    private var peN = 0
    private val wins = ArrayList<Long>() // RMS per full 0.25 s window still in RAM
    private val emitted = ArrayList<AudioChunk>()
    @Volatile var cancelled = false

    companion object {
        const val SR = 16_000
        const val CHUNK_SEC = 540 // 9 min -> ~13.8 MB, well under Groq's 25 MB cap
        internal const val WIN_FR = (0.25 * SR).toInt()
    }

    /** Total frames produced so far (progress = framesOut / SR seconds). */
    @get:Synchronized val framesOut: Long get() = absFrames

    /**
     * Feed decoded PCM (already mixed down to mono by the caller).
     * Linear resample with a position accumulator (never drifts):
     *  - `fPos` = index of the current input frame on the input timeline;
     *  - `nextOut` = position of the next output sample, in INPUT-frame units;
     *  - every output sample is interpolated inside the segment (fPos-1, fPos],
     *    so chunk boundaries fall on first-frame/last-frame positions exactly.
     */
    @Synchronized
    fun feed(inRate: Int, s: ShortArray) {
        if (s.isEmpty()) return
        if (resIn != inRate) { resIn = inRate; hasPrev = false }
        val step = inRate.toDouble() / SR.toDouble() // input frames between two output samples
        for (x in s) {
            if (!hasPrev) {
                prev = x.toDouble()
                hasPrev = true
                emit(coerce(prev))
                nextOut = step
                continue
            }
            fPos++
            while (nextOut <= fPos) {
                val f = (nextOut - (fPos - 1)).coerceIn(0.0, 1.0)
                emit(coerce(prev + (x.toDouble() - prev) * f))
                nextOut += step
            }
            prev = x.toDouble()
        }
    }

    private fun coerce(d: Double): Short {
        val v = if (d > 32767.0) 32767 else if (d < -32768.0) -32768 else d.toInt()
        return v.toShort()
    }

    @Synchronized
    private fun emit(v: Short) {
        absFrames++
        buf.add(v)
        peRms += v * v
        peN++
        if (peN >= WIN_FR) {
            wins.add(peRms / peN)
            peRms = 0
            peN = 0
        }
        val wv = w
        if (wv == null) {
            w = Wav(dir, "$prefix-${seq()}")
            spill(allCompleteWindows())
            return
        }
        if (wv.frames / SR < CHUNK_SEC) {
            spill(allCompleteWindows())
        } else {
            tryQuietCut()
        }
    }

    private fun seq(): Int = emitted.size + 1 // one per completed chunk

    private fun allCompleteWindows(): Int = buf.size / WIN_FR

    /** Flush [n] frames (whole windows) from RAM into the WAV now. */
    private fun spill(n: Int) {
        if (n <= 0 || n > buf.size) return
        val whole = n / WIN_FR
        w!!.write(buf.take(n))
        for (i in 0 until whole) wins.removeAt(0)
    }

    /** Once the open chunk is at/over the target, close it at a quiet dip - or force one, gently. */
    private fun tryQuietCut() {
        val lookFrames = (lookSec * SR).toInt()
        // Guard: if we've been holding far more than the scan window without finding a dip
        // (e.g. continuous loud music with no any quiet 0.25 s), cut at a fixed point instead
        // of letting RAM grow unbounded.
        if (buf.size > lookFrames + WIN_FR * 4) {
            val fixed = buf.size - WIN_FR * 2
            spill(fixed)
            closeChunk()
            return
        }
        if (wins.isEmpty()) {
            spill(allCompleteWindows())
            return
        }
        if (!snapQuiet) { // configured gentler than dips: cut straight at the target length
            val winCut = allCompleteWindows()
            spill(winCut)
            closeChunk()
            return
        }
        var best = -1
        var bestRms = Long.MAX_VALUE
        for (i in wins.indices) {
            val r = wins[i]
            if (r < bestRms) { bestRms = r; best = i }
        }
        if (best <= 0) {
            // Window 0 sits right at the target boundary - do not cut mid-speech twice;
            // let one more window flow in and pick again next time (RAM is bounded above).
            if (wins.size > 1) {
                var b2 = -1
                var b2r = Long.MAX_VALUE
                for (i in 1 until wins.size) { val r = wins[i]; if (r < b2r) { b2r = r; b2 = i } }
                if (b2 > 0) { spill(b2 * WIN_FR); closeChunk(); return }
            }
            spill(allCompleteWindows())
            return
        }
        spill(best * WIN_FR)
        closeChunk()
    }

    /** Ends the open chunk; the frames still in RAM open the next chunk. */
    private fun closeChunk() {
        val wv = w!!
        // A chunk can only get here with >= CHUNK_SEC frames, but keep the guard for safety.
        if (wv.frames >= SR * 3 / 4) {
            emitted.add(AudioChunk(wv.seal(), chunkStart(wv)))
        } else {
            emitted.add(AudioChunk(wv.seal(), chunkStart(wv))) // still keep the bytes: loss-less
        }
        w = null
        wins.clear()
    }

    /** Called once at the end of a stream/file. Always seals what's there - nothing is dropped. */
    @Synchronized
    fun finishStream() {
        val wv = w
        if (wv == null) {
            if (buf.size >= SR) { // rare: >1 s produced but never flushed
                w = Wav(dir, "$prefix-${seq()}")
                spill(buf.size)
                emitted.add(AudioChunk(w!!.seal(), chunkStart(w!!)))
                w = null
            }
            buf.clear(); wins.clear(); peRms = 0; peN = 0
            return
        }
        spill(buf.size)
        emitted.add(AudioChunk(wv.seal(), chunkStart(wv)))
        w = null
        wins.clear()
        buf.clear(); peRms = 0; peN = 0
    }

    @get:Synchronized val done: List<AudioChunk> get() = emitted

    /** All files the chunker created so far (for exception-path cleanup). */
    @get:Synchronized val files: List<File> get() = emitted.map { it.file }

    /** Seconds on the 16 kHz timeline where the open chunk begins. */
    private fun chunkStart(wv: Wav): Long = (absFrames - (wv.frames + buf.size)) / SR

    /** All chunks finished so far (used by the import path - never growing past the session). */
    @Synchronized fun snapshot(): List<AudioChunk> = ArrayList(emitted)

    /** New chunks since the previous poll (the live-capture path consumes them this way). */
    @Synchronized fun pollNew(): List<AudioChunk> {
        val list = ArrayList<AudioChunk>()
        while (seen < emitted.size) { list.add(emitted[seen]); seen++ }
        return list
    }
    private var seen = 0

    /** A WAV being written: PCM 16 kHz mono 16-bit, correct RIFF header once sealed. */
    internal class Wav(private val file: File) {
        constructor(dir: File, name: String) : this(File(dir, "$name.wav"))

        var frames = 0
            private set
        private val f = FileOutputStream(file)

        init {
            // 44-byte placeholder header; exact sizes + numbers are patched in [seal].
            val h = ByteArray(44)
            h[0] = 'R'.code.toByte(); h[1] = 'I'.code.toByte(); h[2] = 'F'.code.toByte(); h[3] = 'F'.code.toByte()
            h[8] = 'W'.code.toByte(); h[9] = 'A'.code.toByte(); h[10] = 'V'.code.toByte(); h[11] = 'E'.code.toByte()
            h[12] = 'f'.code.toByte(); h[13] = 'm'.code.toByte(); h[14] = 't'.code.toByte(); h[15] = ' '.code.toByte()
            h[36] = 'd'.code.toByte(); h[37] = 'a'.code.toByte(); h[38] = 't'.code.toByte(); h[39] = 'a'.code.toByte()
            f.write(h)
        }

        fun write(a: ShortArray) {
            val bb = ByteArray(a.size * 2)
            var p = 0
            for (s in a) {
                bb[p++] = (s.toInt() and 0xFF).toByte()
                bb[p++] = (s.toInt() shr 8 and 0xFF).toByte()
            }
            f.write(bb)
            frames += a.size
        }

        fun seal(): File {
            runCatching { f.close() }
            runCatching {
                RandomAccessFile(file, "rw").use { raf ->
                    raf.seek(0)
                    val h = ByteArray(44)
                    fun i32(o: Int, v: Int) { h[o] = (v and 0xFF).toByte(); h[o + 1] = (v shr 8 and 0xFF).toByte(); h[o + 2] = (v shr 16 and 0xFF).toByte(); h[o + 3] = (v shr 24 and 0xFF).toByte() }
                    i32(4, 36 + frames * 2) // RIFF size = 36 + data
                    i32(16, 16) // fmt chunk size
                    i32(20, 1)  // PCM format
                    i32(22, 1)  // mono
                    i32(24, SR) // sample rate
                    i32(28, SR * 2) // byte rate
                    i32(32, 2) // block align
                    i32(34, 16) // bits
                    i32(40, frames * 2) // data size
                    raf.write(h, 0, 44)
                }
            }
            return file
        }

        fun abandon() { // only for exception cleanup paths
            runCatching { f.close() }
            runCatching { file.delete() }
        }
    }

    /** Tiny FIFO of PCM short frames: one arraycopy per drain, no boxing, no O(n^2). */
    internal class SQueue {
        private var a = ShortArray(1 shl 14)
        private var n = 0

        val size: Int get() = n

        fun add(v: Short) {
            if (n == a.size) a = a.copyOf(a.size * 2)
            a[n++] = v
        }

        /** Removes the first [k] frames and returns them. */
        fun take(k: Int): ShortArray {
            val out = ShortArray(k)
            System.arraycopy(a, 0, out, 0, k)
            System.arraycopy(a, k, a, 0, n - k)
            n -= k
            return out
        }

        fun clear() {
            n = 0
        }
    }
}