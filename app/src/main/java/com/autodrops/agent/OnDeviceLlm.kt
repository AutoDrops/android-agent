package com.autodrops.agent

import android.content.Context
import java.io.File

/**
 * Facade over the two on-device engines:
 *  - MediaPipe LLM Inference for `.task` bundles ([LocalLlm])
 *  - llama.cpp for GGUF files ([GgufLlm])
 *
 * Routes by [LocalModel.engine]. Keeps both engines honest: never fabricates a reply, surfaces
 * native errors, and reports which backend is active. Used by AgentViewModel for offline chat.
 */
object OnDeviceLlm {
    val anyEngineLinked: Boolean get() = LocalLlm.ENGINE_LINKED || GgufLlm.ENGINE_LINKED

    fun isRunnable(m: LocalModel): Boolean = when (m.engine) {
        "mediapipe" -> LocalLlm.ENGINE_LINKED
        "gguf" -> GgufLlm.ENGINE_LINKED
        else -> false
    }

    val loadedId: String? get() = LocalLlm.loadedId ?: GgufLlm.loadedId
    val isLoaded: Boolean get() = LocalLlm.isLoaded || GgufLlm.isLoaded
    val lastError: String? get() = GgufLlm.lastError ?: LocalLlm.lastError

    fun ensureLoaded(ctx: Context, model: LocalModel, file: File): Boolean = when (model.engine) {
        "mediapipe" -> { GgufLlm.unload(); LocalLlm.ensureLoaded(ctx, model, file) }
        "gguf" -> { LocalLlm.unload(); GgufLlm.ensureLoaded(ctx, model, file) }
        else -> false
    }

    fun generate(prompt: String): String? = when {
        GgufLlm.isLoaded -> GgufLlm.generate(prompt)
        LocalLlm.isLoaded -> LocalLlm.generate(prompt)
        else -> { /* set nothing — callers check lastError on both */ null }
    }

    fun generateStream(prompt: String, onPartial: (String) -> Unit): String? = when {
        GgufLlm.isLoaded -> GgufLlm.generateStream(prompt, onPartial)
        LocalLlm.isLoaded -> LocalLlm.generateStream(prompt, onPartial)
        else -> null
    }

    fun unload() { LocalLlm.unload(); GgufLlm.unload() }

    fun status(m: LocalModel, downloaded: Boolean): String = when (m.engine) {
        "mediapipe" -> LocalLlm.status(downloaded)
        "gguf" -> GgufLlm.status(downloaded)
        else -> "Невідомий рушій: ${m.engine}"
    }

    fun offlineFallback(): String = when {
        LocalLlm.ENGINE_LINKED && GgufLlm.ENGINE_LINKED ->
            "Немає мережі. Завантаж .task (MediaPipe) або GGUF (llama.cpp) модель у Налаштуваннях для офлайн-відповіді."
        LocalLlm.ENGINE_LINKED -> LocalLlm.offlineFallback()
        GgufLlm.ENGINE_LINKED ->
            "Немає мережі. Завантаж GGUF-модель у Налаштуваннях, щоб відповідати офлайн через llama.cpp."
        else -> "Немає мережі, а локальний рушій недоступний у цій збірці. Увімкни мережу для OpenRouter."
    }

    fun engineLabel(m: LocalModel): String = when (m.engine) {
        "mediapipe" -> "MediaPipe"
        "gguf" -> "llama.cpp (GGUF)"
        else -> m.engine
    }
}
