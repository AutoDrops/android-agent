package com.autodrops.agent

import org.json.JSONArray
import org.json.JSONObject

/** Risk classes drive the deterministic confirmation policy (never the model). */
enum class Risk { LOW, SENSITIVE, EFFECT, HIGH, ELEVATED }

class ToolSpec(
    val name: String,
    val description: String,
    val schema: JSONObject,
    val risk: (JSONObject) -> Risk,
    val preview: (JSONObject) -> String = { it.toString(2) },
    val exec: suspend (JSONObject) -> String
) {
    fun toOpenAi(): JSONObject = JSONObject().put("type", "function")
        .put("function", JSONObject().put("name", name).put("description", description).put("parameters", schema))
}

object Schema {
    fun obj(vararg props: Pair<String, JSONObject>, required: List<String> = emptyList()) = JSONObject()
        .put("type", "object").put("properties", JSONObject().apply { props.forEach { put(it.first, it.second) } })
        .put("required", JSONArray(required)).put("additionalProperties", false)
    fun str(d: String, enum: List<String>? = null) = JSONObject().put("type", "string").put("description", d)
        .apply { enum?.let { put("enum", JSONArray(it)) } }
    fun int(d: String, min: Int? = null, max: Int? = null) = JSONObject().put("type", "integer").put("description", d)
        .apply { min?.let { put("minimum", it) }; max?.let { put("maximum", it) } }
    fun bool(d: String) = JSONObject().put("type", "boolean").put("description", d)

    /** Strict validation: unknown fields, missing required, wrong types, enum and range violations are rejected. */
    fun validate(schema: JSONObject, args: JSONObject): String? {
        val props = schema.optJSONObject("properties") ?: JSONObject()
        val req = schema.optJSONArray("required") ?: JSONArray()
        for (i in 0 until req.length()) if (!args.has(req.getString(i))) return "missing required '${req.getString(i)}'"
        for (k in args.keys()) {
            val p = props.optJSONObject(k) ?: return "unknown field '$k'"
            val v = args.get(k)
            when (p.optString("type")) {
                "string" -> if (v !is String) return "'$k' must be string"
                "integer" -> {
                    val n = (v as? Number)?.toDouble() ?: return "'$k' must be integer"
                    if (n % 1.0 != 0.0) return "'$k' must be integer"
                    if (p.has("minimum") && n < p.getInt("minimum")) return "'$k' < ${p.getInt("minimum")}"
                    if (p.has("maximum") && n > p.getInt("maximum")) return "'$k' > ${p.getInt("maximum")}"
                }
                "boolean" -> if (v !is Boolean) return "'$k' must be boolean"
            }
            p.optJSONArray("enum")?.let { e -> if ((0 until e.length()).none { e.get(it) == v }) return "'$k' must be one of $e" }
        }
        return null
    }
}

fun JSONObject.str(k: String) = optString(k, "")
fun JSONObject.intOr(k: String, d: Int) = if (has(k)) optInt(k, d) else d

/** Remove control / bidi characters so confirmation previews cannot be visually spoofed. */
fun sanitizeForDisplay(s: String): String =
    s.replace(Regex("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F\\u007F\\u202A-\\u202E\\u2066-\\u2069\\u200B-\\u200F]"), "�")
