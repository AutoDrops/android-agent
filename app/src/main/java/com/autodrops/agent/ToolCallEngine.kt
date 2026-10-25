package com.autodrops.agent

import org.json.JSONArray
import org.json.JSONObject

/**
 * v3.22 §5: single entry for turning an assistant turn into tool_calls.
 * Order: native OpenAI tool_calls → text/XML/JSON protocol → ForcedIntent from the user prompt.
 * Does NOT execute — that stays in AgentViewModel so confirmation / NetGuard / schema still apply.
 */
object ToolCallEngine {
    data class Decision(val calls: JSONArray?, val textMode: Boolean, val info: String?, val nudge: Boolean, val forcedUsed: Boolean)

    fun decide(content: String, native: JSONArray?, known: Set<String>, forced: List<String>,
               noExec: Boolean, nudgesLeft: Int, step: Int): Decision {
        if (native != null && native.length() > 0) return Decision(native, false, null, false, false)
        if (noExec) return Decision(null, false, null, false, false)
        val parsed = TextTools.parse(content, known)
        if (parsed.isNotEmpty()) return Decision(TextTools.toToolCalls(parsed, step), true,
            "Модель не викликала інструмент напряму — виконую ${parsed.size} команд(и) з її тексту.", false, false)
        if (forced.isNotEmpty() && "shell" in known)
            return Decision(TextTools.toToolCalls(forced.map { TextTools.Call("shell", JSONObject().put("command", it)) }, step), true,
                "Модель не виконала команду — запускаю напряму: ${forced.joinToString("; ")}", false, true)
        if (TextTools.narratesWithoutAction(content) && nudgesLeft > 0)
            return Decision(null, false, "Модель лише описала команди — прошу її реально їх виконати…", true, false)
        return Decision(null, false, null, false, false)
    }
}
