package com.autodrops.agent

import org.json.JSONArray
import org.json.JSONObject

/** A downloadable local GGUF small-model entry. sha256 is mandatory for verification. */
data class LocalModel(
    val id: String,
    val name: String,
    val url: String,
    val sizeMb: Int,
    val sha256: String,
    val note: String = ""
) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("url", url)
        .put("sizeMb", sizeMb).put("sha256", sha256).put("note", note)
    companion object {
        fun fromJson(o: JSONObject) = LocalModel(o.optString("id"), o.optString("name"), o.optString("url"),
            o.optInt("sizeMb"), o.optString("sha256"), o.optString("note"))
    }
}

/** Curated catalog of tiny instruct models (GGUF Q4) suitable for offline fallback chat. */
object ModelCatalogLocal {
    // URLs point at well-known public GGUF mirrors; sha256 must be confirmed before a file is loaded.
    // sha256 left blank here means "verify-on-first-download is required"; UI refuses to load an unverified file.
    val CATALOG: List<LocalModel> = listOf(
        LocalModel(
            id = "qwen2.5-0.5b-instruct-q4",
            name = "Qwen2.5-0.5B-Instruct (Q4_K_M)",
            url = "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q4_k_m.gguf",
            sizeMb = 400,
            sha256 = "",
            note = "Найменша; швидка офлайн-відповідь, обмежена якість."),
        LocalModel(
            id = "smollm2-360m-instruct-q4",
            name = "SmolLM2-360M-Instruct (Q4_K_M)",
            url = "https://huggingface.co/HuggingFaceTB/SmolLM2-360M-Instruct-GGUF/resolve/main/smollm2-360m-instruct-q4_k_m.gguf",
            sizeMb = 270,
            sha256 = "",
            note = "Дуже мала; базові відповіді офлайн."),
        LocalModel(
            id = "qwen2.5-1.5b-instruct-q4",
            name = "Qwen2.5-1.5B-Instruct (Q4_K_M)",
            url = "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q4_k_m.gguf",
            sizeMb = 1100,
            sha256 = "",
            note = "Краща якість; більший розмір/повільніше.")
    )
    fun byId(id: String) = CATALOG.firstOrNull { it.id == id }
    fun toJsonArray(): JSONArray = JSONArray().apply { CATALOG.forEach { put(it.toJson()) } }
}
