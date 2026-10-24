package com.autodrops.agent

import org.json.JSONArray
import org.json.JSONObject

/** Backup/restore for RAG notes, rules, device inventory and NON-SECRET settings. Never includes API keys. */
object ExportBundle {
    const val VERSION = 1
    // Only these preference keys are portable; api_key / approved_hosts are deliberately excluded.
    val SAFE_SETTINGS = listOf("base_url", "model", "stream", "shell_mode", "matrix_rain", "disabled_tools")

    /** Keep only portable, non-secret keys. Never let api_key/approved_hosts through. */
    private fun safe(settings: JSONObject): JSONObject {
        val out = JSONObject()
        for (k in SAFE_SETTINGS) if (settings.has(k)) out.put(k, settings.get(k))
        return out
    }

    fun build(settingsIn: JSONObject, rag: List<RagDoc>, rules: List<Rule>, devices: List<DeviceEntity>): String {
        val settings = safe(settingsIn)
        val ragArr = JSONArray().apply { rag.forEach { put(JSONObject().put("title", it.title).put("content", it.content)) } }
        val ruleArr = JSONArray().apply { rules.forEach { put(it.toJson()) } }
        val devArr = JSONArray().apply { devices.forEach { put(it.toJson()) } }
        return JSONObject().put("app", "DRON.exe").put("bundleVersion", VERSION)
            .put("settings", settings).put("rag", ragArr).put("rules", ruleArr).put("devices", devArr)
            .toString(2)
    }

    data class Parsed(val settings: JSONObject, val rag: List<Pair<String, String>>, val rules: List<Rule>, val devices: List<DeviceEntity>)

    fun parse(json: String): Parsed {
        val o = JSONObject(json)
        // Reconstruct settings strictly from the allowlist; ignore any extra/secret keys in the file.
        val s = safe(o.optJSONObject("settings") ?: JSONObject())
        val ragArr = o.optJSONArray("rag") ?: JSONArray()
        val rag = (0 until ragArr.length()).map { val r = ragArr.getJSONObject(it); r.optString("title") to r.optString("content") }
        val ruleArr = o.optJSONArray("rules") ?: JSONArray()
        val rules = (0 until ruleArr.length()).map { Rule.fromJson(ruleArr.getJSONObject(it)).copy(id = 0) }
        val devArr = o.optJSONArray("devices") ?: JSONArray()
        val devices = (0 until devArr.length()).map { DeviceEntity.fromJson(devArr.getJSONObject(it)).copy(id = 0) }
        return Parsed(s, rag, rules, devices)
    }
}
