package com.autodrops.agent

import org.json.JSONArray
import org.json.JSONObject

/** A visible multi-step plan the agent declares via set_plan and advances via complete_step. */
data class PlanStep(val text: String, var status: String = "pending") // pending|running|done|failed

object PlanCodec {
    fun encode(steps: List<PlanStep>): JSONArray =
        JSONArray().apply { steps.forEach { put(JSONObject().put("text", it.text).put("status", it.status)) } }
    fun decode(arr: JSONArray): List<PlanStep> =
        (0 until arr.length()).map { val o = arr.getJSONObject(it); PlanStep(o.optString("text"), o.optString("status", "pending")) }
    /** Parse a JSON array of step strings (model output) into a fresh pending plan. */
    fun fromStrings(json: String): List<PlanStep> {
        val arr = runCatching { JSONArray(json) }.getOrElse { return emptyList() }
        return (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotBlank() }?.let { s -> PlanStep(s) } }
    }
}

/** Shared, observable plan surface the agent writes to and the UI renders. */
object AgentPlan {
    val steps = androidx.compose.runtime.mutableStateListOf<PlanStep>()
    fun set(list: List<PlanStep>) { steps.clear(); steps.addAll(list) }
    fun clear() { steps.clear() }
    fun mark(index: Int, status: String) { if (index in steps.indices) steps[index] = steps[index].copy(status = status) }
    fun running(index: Int) = mark(index, "running")
}
