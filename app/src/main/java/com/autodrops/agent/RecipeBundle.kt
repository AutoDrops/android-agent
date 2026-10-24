package com.autodrops.agent

import org.json.JSONArray
import org.json.JSONObject

/** Shareable "agent recipes" (saved prompts/macros) export/import as portable JSON. No secrets. */
object RecipeBundle {
    const val VERSION = 1
    fun build(recipes: List<Recipe>): String {
        val arr = JSONArray().apply { recipes.forEach { put(JSONObject().put("name", it.name).put("prompt", it.prompt)) } }
        return JSONObject().put("app", "DRON.exe").put("kind", "agent-recipes").put("version", VERSION).put("recipes", arr).toString(2)
    }
    /** Returns name->prompt pairs; tolerates both our bundle and a bare array. */
    fun parse(json: String): List<Pair<String, String>> {
        val trimmed = json.trim()
        val arr = if (trimmed.startsWith("[")) JSONArray(trimmed) else JSONObject(trimmed).optJSONArray("recipes") ?: JSONArray()
        return (0 until arr.length()).take(200).mapNotNull {
            val o = arr.getJSONObject(it); val p = o.optString("prompt").take(4000)
            if (p.isBlank()) null else o.optString("name").take(120).ifBlank { p.take(24) } to p
        }
    }
}
