/*
 * Wispr by Deepu Gupta
 * Copyright 2026 Deepu Gupta
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 * SPDX-License-Identifier: Apache-2.0
 */
package com.deepugupta.wispr

import org.json.JSONArray
import org.json.JSONObject

/**
 * Every Groq model Wispr can use, all on Groq's FREE plan (checked 30 Sep 2026, console.groq.com/docs/rate-limits).
 * Free-plan limits: chat models 30 req/min + 1,000 req/day; Whisper 20 req/min + 2,000 req/day (8 h audio/day);
 * Compound 250 req/day. "preview" models can be removed by Groq at short notice; Wispr then falls back automatically.
 * To add a model later, add one line here. Nothing else needs to change.
 */
object Models {
    enum class Kind { STT, TEXT }

    class M(
        val id: String,
        val label: String,
        val note: String,
        val kind: Kind,
        val preview: Boolean = false,
        val vision: Boolean = false,
        val web: Boolean = false
    )

    val ALL: List<M> = listOf(
        M("whisper-large-v3", "Whisper Large v3", "Most accurate, best for fast speech", Kind.STT),
        M("whisper-large-v3-turbo", "Whisper v3 Turbo", "Fastest", Kind.STT),

        M("openai/gpt-oss-120b", "GPT-OSS 120B", "Best quality (default)", Kind.TEXT),
        M("openai/gpt-oss-20b", "GPT-OSS 20B", "Fastest replies", Kind.TEXT),
        M("qwen/qwen3.8-27b", "Qwen 3.8 27B", "Newest, reads images (OCR)", Kind.TEXT, preview = true, vision = true),
        M("qwen/qwen3.6-27b", "Qwen 3.6 27B", "Reads images (OCR), strong multilingual", Kind.TEXT, preview = true, vision = true),
        M("groq/compound", "Groq Compound", "Searches the web for live answers (250/day)", Kind.TEXT, web = true),
        M("groq/compound-mini", "Groq Compound Mini", "Quick web search answers (250/day)", Kind.TEXT, web = true)
    )

    const val DEFAULT_STT = "whisper-large-v3"
    const val FAST_STT = "whisper-large-v3-turbo"
    const val DEFAULT_LLM = "openai/gpt-oss-120b"
    const val FAST_LLM = "openai/gpt-oss-20b"
    const val DEFAULT_VISION = "qwen/qwen3.8-27b"

    /** Model ids Groq has shut down. Saved settings pointing at these are moved to a default. */
    val DEAD: Set<String> = setOf(
        "llama-3.3-70b-versatile", "llama-3.1-8b-instant", "llama3-70b-8192", "llama3-8b-8192",
        "qwen/qwen3-32b", "meta-llama/llama-4-scout-17b-16e-instruct", "meta-llama/llama-4-maverick-17b-128e-instruct",
        "gemma2-9b-it", "mixtral-8x7b-32768", "deepseek-r1-distill-llama-70b", "moonshotai/kimi-k2-instruct",
        "moonshotai/kimi-k2-instruct-0905", "distil-whisper-large-v3-en"
    )

    fun find(id: String): M? = ALL.firstOrNull { it.id == id }
    fun isVision(id: String): Boolean = find(id)?.vision == true

    /** Chain to try: the user's pick first, then safe defaults (skips duplicates). */
    fun sttChain(chosen: String): List<String> = listOf(chosen, DEFAULT_STT, FAST_STT).distinct()
    fun textChain(chosen: String): List<String> = listOf(chosen, DEFAULT_LLM, FAST_LLM).distinct()
    fun visionChain(chosen: String): List<String> =
        (listOf(chosen).filter { isVision(it) } + ALL.filter { it.vision }.map { it.id }).distinct()

    /** Ids that are not chat/speech models (safety, TTS, prompt guards). Hidden from the pickers. */
    private val NOT_CHAT = Regex("guard|safeguard|orpheus|tts|whisper|embed", RegexOption.IGNORE_CASE)

    /**
     * Catalog for the UI. [live] = ids returned by Groq's /models for this key (null if not fetched yet).
     * Unknown new chat models from Groq are appended, so new free models show up without an app update.
     */
    fun json(live: Set<String>?): String {
        val a = JSONArray()
        for (m in ALL) {
            a.put(
                JSONObject().put("id", m.id).put("label", m.label).put("note", m.note)
                    .put("kind", m.kind.name.lowercase()).put("preview", m.preview)
                    .put("vision", m.vision).put("web", m.web)
                    .put("live", live?.contains(m.id) ?: true)
            )
        }
        live?.filter { id -> find(id) == null && id !in DEAD && !NOT_CHAT.containsMatchIn(id) }?.sorted()?.forEach { id ->
            a.put(
                JSONObject().put("id", id).put("label", id.substringAfter('/')).put("note", "New on Groq")
                    .put("kind", "text").put("preview", true).put("vision", false).put("web", false).put("live", true)
            )
        }
        return a.toString()
    }
}
