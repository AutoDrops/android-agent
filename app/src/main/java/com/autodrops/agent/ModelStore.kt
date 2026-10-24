package com.autodrops.agent

import org.json.JSONArray
import org.json.JSONObject

/**
 * A downloadable local model entry. [engine] selects the runtime:
 *  - "mediapipe": a MediaPipe LLM Inference .task bundle (REAL on-device generate path, see LocalLlm)
 *  - "gguf": a llama.cpp GGUF file (download/verify works; native inference engine still pending)
 * sha256 pins the expected hash for supply-chain protection (empty = TOFU, never auto-selected).
 */
data class LocalModel(
    val id: String,
    val name: String,
    val url: String,
    val sizeMb: Int,
    val sha256: String,
    val engine: String = "gguf",
    val note: String = ""
) {
    val isRunnable: Boolean get() = engine == "mediapipe"
    fun fileName(): String = if (engine == "mediapipe") "$id.task" else "$id.gguf"
    fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("url", url)
        .put("sizeMb", sizeMb).put("sha256", sha256).put("engine", engine).put("note", note)
    companion object {
        fun fromJson(o: JSONObject) = LocalModel(o.optString("id"), o.optString("name"), o.optString("url"),
            o.optInt("sizeMb"), o.optString("sha256"), o.optString("engine", "gguf"), o.optString("note"))
    }
}

/** Curated catalog of tiny instruct models. MediaPipe .task entries run fully on-device offline. */
object ModelCatalogLocal {
    val CATALOG: List<LocalModel> = listOf(
        // REAL runnable on-device model (MediaPipe LLM Inference). Free, no auth; SHA-256 pinned.
        LocalModel(
            id = "qwen2.5-0.5b-instruct-mp-q8",
            name = "Qwen2.5-0.5B-Instruct (MediaPipe .task, Q8)",
            url = "https://huggingface.co/litert-community/Qwen2.5-0.5B-Instruct/resolve/main/Qwen2.5-0.5B-Instruct_multi-prefill-seq_q8_ekv1280.task",
            sizeMb = 521,
            sha256 = "e608953f169aeb1bd7b9155fec2559825e08453fc209b84eda3a781ed0452fd2",
            engine = "mediapipe",
            note = "Працює офлайн на пристрої через MediaPipe. ~521 МБ."),
        // «БЕЗ ЦЕНЗУРИ» on-device model: abliterated Gemma-2-2B-it converted to MediaPipe .task (int4, GPU backend).
        // Source: IlyaGusev/gemma-2-2b-it-abliterated (HF), license: Google Gemma Terms (accepted by owner).
        // HONESTY: converted locally + SHA-256 pinned, NOT run on real hardware here (no emulator/KVM in sandbox).
        LocalModel(
            id = "gemma2-2b-it-ablit-mp-int4",
            name = "Gemma-2-2B-it «без цензури» (MediaPipe .task, int4)",
            url = "https://github.com/AutoDrops/android-agent/releases/download/v3.9/gemma2-2b-it-ablit-int4.task",
            sizeMb = 1540,
            sha256 = "541f2434dac9633b74730af3fa0d0e7d893dc28726aba12d3d420ab179afbaa7",
            engine = "mediapipe",
            note = "«Без цензури» (abliterated). Офлайн через MediaPipe, int4, ~1.6 ГБ. Сконвертовано + SHA-256 закріплено, на реальному пристрої не перевірено."),
        // GGUF entries: download+verify real; llama.cpp JNI inference still pending.
        LocalModel(
            id = "qwen2.5-0.5b-instruct-q4",
            name = "Qwen2.5-0.5B-Instruct (GGUF Q4) — завантаження",
            url = "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q4_k_m.gguf",
            sizeMb = 400,
            sha256 = "",
            engine = "gguf",
            note = "GGUF; рушій llama.cpp ще в розробці."),
        LocalModel(
            id = "smollm2-360m-instruct-q4",
            name = "SmolLM2-360M-Instruct (GGUF Q4) — завантаження",
            url = "https://huggingface.co/HuggingFaceTB/SmolLM2-360M-Instruct-GGUF/resolve/main/smollm2-360m-instruct-q4_k_m.gguf",
            sizeMb = 270,
            sha256 = "",
            engine = "gguf",
            note = "GGUF; рушій llama.cpp ще в розробці.")
    )
    fun byId(id: String) = CATALOG.firstOrNull { it.id == id }
    fun runnable() = CATALOG.filter { it.isRunnable }
    fun toJsonArray(): JSONArray = JSONArray().apply { CATALOG.forEach { put(it.toJson()) } }
}
