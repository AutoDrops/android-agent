package com.autodrops.agent

import org.json.JSONObject

/**
 * v3.18: self-correction for invalid tool-call JSON (previously DEFERRED).
 *
 * Models occasionally emit tool-call `arguments` that are not valid JSON (truncated, trailing commas,
 * markdown-fenced, or a bare object without braces). Previously such input was silently coerced to an
 * empty object, which then failed schema validation with a confusing message. Now we:
 *   1. try a strict parse,
 *   2. try a light, SAFE repair (strip ```json fences / surrounding prose), then re-parse,
 *   3. if still invalid, return an explicit correction instruction so the model can resend valid JSON.
 *
 * Pure JVM logic, fully unit-tested. No behavior change for already-valid arguments.
 */
object ToolArgs {
    sealed class Result {
        data class Ok(val args: JSONObject, val repaired: Boolean) : Result()
        data class Invalid(val correction: String) : Result()
    }

    /** Extract the first balanced {...} JSON object substring, ignoring braces inside strings. */
    internal fun extractObject(s: String): String? {
        val start = s.indexOf('{')
        if (start < 0) return null
        var depth = 0; var inStr = false; var esc = false
        for (i in start until s.length) {
            val c = s[i]
            if (inStr) {
                when { esc -> esc = false; c == '\\' -> esc = true; c == '"' -> inStr = false }
            } else when (c) {
                '"' -> inStr = true
                '{' -> depth++
                '}' -> { depth--; if (depth == 0) return s.substring(start, i + 1) }
            }
        }
        return null
    }

    fun parse(name: String, raw: String): Result {
        val s = raw.trim()
        // Empty / "{}" is a legitimately empty argument object.
        if (s.isEmpty() || s == "{}") return Result.Ok(JSONObject(), repaired = false)
        runCatching { JSONObject(s) }.onSuccess { return Result.Ok(it, repaired = false) }
        // Light repair: drop markdown fences and any prose around a JSON object.
        val stripped = s.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        runCatching { JSONObject(stripped) }.onSuccess { return Result.Ok(it, repaired = true) }
        extractObject(s)?.let { obj -> runCatching { JSONObject(obj) }.onSuccess { return Result.Ok(it, repaired = true) } }
        return Result.Invalid(
            "Invalid arguments: the `arguments` field for tool '$name' was not valid JSON. " +
            "Resend this tool call with `arguments` as a single valid JSON object (double-quoted keys " +
            "and string values, no markdown fences, no trailing commas)."
        )
    }
}
