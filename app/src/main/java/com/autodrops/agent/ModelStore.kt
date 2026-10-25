package com.autodrops.agent

import org.json.JSONArray
import org.json.JSONObject

/**
 * A downloadable local model entry. [engine] selects the runtime:
 *  - "mediapipe": a MediaPipe LLM Inference .task bundle (REAL on-device generate path, see LocalLlm)
 *  - "gguf": a llama.cpp GGUF file (download/verify + on-device inference via GgufLlm / llama-android AAR)
 * sha256 pins the expected hash for supply-chain protection (empty = TOFU, never auto-selected).
 */
data class LocalModel(
    val id: String,
    val name: String,
    val url: String,
    val sizeMb: Int,
    val sha256: String,
    val engine: String = "gguf",
    val note: String = "",
    // v3.9: when non-empty, the file is downloaded as these parts (in order) and concatenated; the
    // combined file is then verified against [sha256]. Lets us host >1GB assets as sub-1GB GitHub parts.
    val parts: List<String> = emptyList()
) {
    /** True when this entry can be loaded by an on-device engine linked into THIS build. */
    val isRunnable: Boolean get() = when (engine) {
        "mediapipe" -> true  // MediaPipe engine is always linked (see LocalLlm.ENGINE_LINKED)
        "gguf" -> GgufLlm.ENGINE_LINKED
        else -> false
    }
    fun fileName(): String = if (engine == "mediapipe") "$id.task" else "$id.gguf"
    /** The ordered list of URLs to fetch: the multi-part list when set, otherwise the single [url]. */
    fun downloadUrls(): List<String> = if (parts.isNotEmpty()) parts else listOf(url)
    fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("url", url)
        .put("sizeMb", sizeMb).put("sha256", sha256).put("engine", engine).put("note", note)
        .put("parts", JSONArray(parts))
    companion object {
        fun fromJson(o: JSONObject): LocalModel {
            val pj = o.optJSONArray("parts")
            val pl = if (pj != null) (0 until pj.length()).map { pj.getString(it) } else emptyList()
            return LocalModel(o.optString("id"), o.optString("name"), o.optString("url"),
                o.optInt("sizeMb"), o.optString("sha256"), o.optString("engine", "gguf"), o.optString("note"), pl)
        }
    }
}

/** Curated catalog of tiny instruct models. MediaPipe .task and (when linked) GGUF entries run on-device. */
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
            url = "https://github.com/AutoDrops/android-agent/releases/download/v3.9/gemma2-2b-it-ablit-int4.task.part00",
            sizeMb = 1540,
            sha256 = "541f2434dac9633b74730af3fa0d0e7d893dc28726aba12d3d420ab179afbaa7",
            engine = "mediapipe",
            note = "«Без цензури» (abliterated). Офлайн через MediaPipe, int4, ~1.6 ГБ (3 частини). Сконвертовано + SHA-256 закріплено, на реальному пристрої не перевірено.",
            parts = listOf(
                "https://github.com/AutoDrops/android-agent/releases/download/v3.9/gemma2-2b-it-ablit-int4.task.part00",
                "https://github.com/AutoDrops/android-agent/releases/download/v3.9/gemma2-2b-it-ablit-int4.task.part01",
                "https://github.com/AutoDrops/android-agent/releases/download/v3.9/gemma2-2b-it-ablit-int4.task.part02")),
        // GGUF entries: download+verify + on-device inference via llama.cpp AAR (GgufLlm).
        // «БЕЗ ЦЕНЗУРИ» GGUF: Llama-3.2-1B-Instruct-abliterated Q4_K_M (~912 МБ).
        // Source: mradermacher/Llama-3.2-1B-Instruct-abliterated-GGUF (HF). Hosted as release asset for stable URL.
        // HONESTY: SHA-256 pinned; natives arm64-v8a wired+compiled; NOT run on real hardware here (no emulator/KVM).
        LocalModel(
            id = "llama32-1b-instruct-ablit-q4",
            name = "Llama-3.2-1B-Instruct «без цензури» (GGUF Q4_K_M)",
            url = "https://github.com/AutoDrops/android-agent/releases/download/v3.21/llama32-1b-instruct-ablit-q4.gguf",
            sizeMb = 912,
            sha256 = "68336c25367576f49b29f01ee9034658b51bbded6a7f215e934d997de96301e0",
            engine = "gguf",
            note = "«Без цензури» (abliterated). Офлайн через llama.cpp, Q4_K_M, ~912 МБ. SHA-256 закріплено. На реальному пристрої не перевірено (немає емулятора в середовищі збірки). Тільки arm64-v8a."),
        // Small instruct GGUF (HF direct). TOFU hash (empty pin) — never auto-selected for offline chat.
        LocalModel(
            id = "qwen2.5-0.5b-instruct-q4",
            name = "Qwen2.5-0.5B-Instruct (GGUF Q4) — завантаження",
            url = "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q4_k_m.gguf",
            sizeMb = 400,
            sha256 = "",
            engine = "gguf",
            note = "GGUF Q4; рушій llama.cpp вшито. SHA не закріплено (TOFU) — не авто-вибір."),
        LocalModel(
            id = "smollm2-360m-instruct-q4",
            name = "SmolLM2-360M-Instruct (GGUF Q8) — завантаження",
            url = "https://huggingface.co/HuggingFaceTB/SmolLM2-360M-Instruct-GGUF/resolve/main/smollm2-360m-instruct-q8_0.gguf",
            sizeMb = 369,
            sha256 = "48ab3034d0dd401fbc721eb1df3217902fee7dab9078992d66431f09b7750201",
            engine = "gguf",
            note = "GGUF Q8; рушій llama.cpp вшито. SHA-256 закріплено (з HF LFS). v3.21: старе посилання q4_k_m давало 404 — виправлено.")
    )
    fun byId(id: String) = CATALOG.firstOrNull { it.id == id }
    fun runnable() = CATALOG.filter { it.isRunnable }
    fun toJsonArray(): JSONArray = JSONArray().apply { CATALOG.forEach { put(it.toJson()) } }
}
