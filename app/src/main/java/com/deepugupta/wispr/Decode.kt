/*
 * Wispr by Deepu Gupta
 * Copyright 2026 Deepu Gupta
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 * SPDX-License-Identifier: Apache-2.0
 */
package com.deepugupta.wispr

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * MODE B (import) only: local media file -> 16 kHz mono PCM WAV chunks.
 * Live system playback capture has its own engine ([PlaybackCapture]); this file
 * never captures anything - it only reads a file the user picked.
 *
 * Path: Storage-Access-Framework Uri -> MediaExtractor (any container)
 *       -> MediaCodec (phone's own audio decoder) -> mono downmix
 *       -> [Chunker] (linear resample + quiet-dip chunking) -> AudioChunk WAV files.
 *
 * Supported containers/codecs are whatever the phone itself can decode:
 * MP3, WAV, M4A/AAC, OGG/Opus, FLAC, and the audio track of MP4 / webm / MKV video.
 * No FFmpeg, no bundled native libs - and no app-specific assumptions
 * (YouTube/Facebook/music apps are only four of many sources; nothing is hardcoded).
 */
object Decode {
    class DecErr(msg: String) : Exception(msg)

    /** Scratch directory for transit WAV chunks (safe to delete when no session is running). */
    fun scratch(app: Context): File = File(app.cacheDir, "transit")

    /** Human file name behind a content Uri (best effort). */
    fun name(app: Context, uri: Uri): String {
        return try {
            var out: String? = null
            app.contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (i >= 0) out = c.getString(i)
                }
            }
            out?.takeIf { it.isNotBlank() } ?: (uri.lastPathSegment ?: "audio")
        } catch (e: Exception) {
            "audio"
        }
    }

    /**
     * Decodes any media Uri into ordered WAV chunks of about [Chunker.CHUNK_SEC] seconds.
     * [isCancelled] lets long imports stop early: partial chunks are discarded and a
     * [DecErr] "cancelled" is thrown, so callers never treat a partial run as complete.
     */
    fun extractToChunks(
        app: Context,
        uri: Uri,
        onProgress: (done: Long, total: Long) -> Unit,
        isCancelled: () -> Boolean = { false }
    ): List<AudioChunk> {
        val dir = Decode.scratch(app).apply { mkdirs() }
        val chunker = Chunker(dir, "t${System.currentTimeMillis()}")
        try {
            val ex = MediaExtractor()
            try {
                ex.setDataSource(app, uri, null)
                val t = pickAudio(ex) ?: throw DecErr("No audio track in this file.")
                val fmt = ex.getTrackFormat(t)
                val totalSec = if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) / 1_000_000L else -1
                decodeStream(ex, fmt, chunker, onProgress, totalSec, isCancelled)
            } finally {
                runCatching { ex.release() }
            }
        } catch (e: DecErr) {
            chunker.files.forEach { it.delete() }
            throw e
        } catch (e: Exception) {
            chunker.files.forEach { it.delete() }
            throw DecErr("Couldn't decode this file on this phone (${e.javaClass.simpleName}).")
        }
        if (isCancelled()) {
            chunker.files.forEach { it.delete() }
            throw DecErr("Cancelled.")
        }
        return chunker.done
    }

    /** Picks the audio stream (for a video file: its soundtrack). */
    private fun pickAudio(ex: MediaExtractor): Int {
        for (i in 0 until ex.trackCount) {
            val f = ex.getTrackFormat(i)
            if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                ex.selectTrack(i)
                return i
            }
        }
        return -1
    }

    /** Streaming decode: compressed sample in, PCM out, into the chunker. */
    private fun decodeStream(
        ex: MediaExtractor,
        fmt: MediaFormat,
        chunker: Chunker,
        onProgress: (Long, Long) -> Unit,
        totalSec: Long,
        isCancelled: () -> Boolean
    ) {
        val mime = fmt.getString(MediaFormat.KEY_MIME)!!
        val codec = try { MediaCodec.createDecoderByType(mime) } catch (e: Exception) {
            throw DecErr("This audio format ${
                mime.substringAfter('/')
            } can't be decoded on this phone.")
        }
        try {
            codec.configure(fmt, null, null, 0)
            var rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var ch = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            codec.start()
            val info = MediaCodec.BufferInfo()
            var eosIn = false
            var eosOut = false
            var gotAny = false
            while (!eosOut) {
                if (isCancelled()) throw DecErr("Cancelled.")
                if (!eosIn) {
                    val i = codec.dequeueInputBuffer(10_000L)
                    if (i >= 0) {
                        val ib = codec.getInputBuffer(i)!!
                        val sz = ex.readSampleData(ib, 0)
                        if (sz < 0) {
                            codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            eosIn = true
                        } else {
                            codec.queueInputBuffer(i, 0, sz, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, 10_000L)
                when {
                    o >= 0 -> {
                        val fo = codec.outputFormat
                        rate = fo.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        ch = fo.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        if (info.size > 0) {
                            gotAny = true
                            chunker.feed(rate, toShorts(codec.getOutputBuffer(o)!!, ch))
                            onProgress(chunker.framesOut / Chunker.SR, totalSec)
                        }
                        codec.releaseOutputBuffer(o, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) eosOut = true
                    }
                    o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val fo = codec.outputFormat
                        rate = fo.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        ch = fo.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    }
                }
            }
            chunker.finishStream()
            if (!gotAny) throw DecErr("No audio in this file.")
        } finally {
            runCatching { codec.stop() }
            runCatching { codec.release() }
        }
    }

    /** Any channel-count little-endian PCM16 decoder buffer -> mono. */
    private fun toShorts(buf: ByteBuffer, ch: Int): ShortArray {
        buf.order(ByteOrder.LITTLE_ENDIAN)
        val frames = buf.remaining() / 2 / ch
        if (frames <= 0) return ShortArray(0)
        val o = ShortArray(frames)
        for (f in 0 until frames) {
            var acc = 0
            for (c in 0 until ch) acc += buf.short.toInt()
            o[f] = (acc / ch).toShort()
        }
        return o
    }
}
