package com.autodrops.agent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

data class ModelInfo(val id: String, val name: String, val context: Int, val promptPrice: String, val completionPrice: String, val tools: Boolean)

/** OpenAI-compatible client: /chat/completions (SSE streaming incl. tool_call deltas) and /models. */
class LlmClient {
    private val http = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS).build()

    private fun req(base: String, key: String) = Request.Builder()
        .header("Authorization", "Bearer $key")
        .header("HTTP-Referer", "https://github.com/AutoDrops/android-agent")
        .header("X-Title", "Android Agent")

    suspend fun models(base: String, key: String): List<ModelInfo> = withContext(Dispatchers.IO) {
        http.newCall(req(base, key).url("$base/models").get().build()).execute().use { r ->
            val txt = r.body?.string() ?: ""
            if (!r.isSuccessful) throw RuntimeException("HTTP ${r.code}")
            val arr = JSONObject(txt).getJSONArray("data")
            (0 until arr.length()).map { i ->
                val m = arr.getJSONObject(i)
                val pr = m.optJSONObject("pricing")
                val params = m.optJSONArray("supported_parameters")
                val tools = params == null || (0 until params.length()).any { params.optString(it) == "tools" }
                ModelInfo(m.getString("id"), m.optString("name", m.getString("id")), m.optInt("context_length", 0),
                    pr?.optString("prompt") ?: "", pr?.optString("completion") ?: "", tools)
            }.sortedBy { it.id }
        }
    }

    /** Returns final assistant message JSON. onDelta receives streamed text. */
    suspend fun complete(base: String, key: String, model: String, messages: JSONArray, tools: JSONArray,
                         stream: Boolean, onDelta: (String) -> Unit): JSONObject = withContext(Dispatchers.IO) {
        val body = JSONObject().put("model", model).put("messages", messages).put("stream", stream)
            .put("usage", JSONObject().put("include", true))
        if (tools.length() > 0) body.put("tools", tools).put("tool_choice", "auto")
        val call = http.newCall(req(base, key).url("$base/chat/completions")
            .post(body.toString().toRequestBody("application/json".toMediaType())).build())
        call.execute().use { r ->
            if (!r.isSuccessful) throw RuntimeException("HTTP ${r.code}")
            if (!stream) {
                val o = JSONObject(r.body!!.string())
                o.optJSONObject("error")?.let { throw RuntimeException(it.optString("message")) }
                val m = o.getJSONArray("choices").getJSONObject(0).getJSONObject("message")
                o.optJSONObject("usage")?.let { m.put("_usage", it) }
                return@withContext m
            }
            val content = StringBuilder()
            val calls = sortedMapOf<Int, JSONObject>()
            var usageObj: JSONObject? = null
            val src = r.body!!.source()
            while (!src.exhausted()) {
                coroutineContext.ensureActive()
                val line = src.readUtf8Line() ?: break
                if (!line.startsWith("data:")) continue
                val data = line.removePrefix("data:").trim()
                if (data == "[DONE]") break
                val o = runCatching { JSONObject(data) }.getOrNull() ?: continue
                o.optJSONObject("error")?.let { throw RuntimeException(it.optString("message")) }
                o.optJSONObject("usage")?.let { usageObj = it }
                val ch = o.optJSONArray("choices")?.optJSONObject(0) ?: continue
                val d = ch.optJSONObject("delta") ?: continue
                val txt = d.optString("content", "")
                if (txt.isNotEmpty() && txt != "null") { content.append(txt); onDelta(txt) }
                d.optJSONArray("tool_calls")?.let { tc ->
                    for (i in 0 until tc.length()) {
                        val t = tc.getJSONObject(i)
                        val idx = t.optInt("index", i)
                        val acc = calls.getOrPut(idx) { JSONObject().put("id", "").put("type", "function")
                            .put("function", JSONObject().put("name", "").put("arguments", "")) }
                        if (t.optString("id").isNotEmpty()) acc.put("id", t.getString("id"))
                        t.optJSONObject("function")?.let { f ->
                            val af = acc.getJSONObject("function")
                            if (f.has("name") && !f.isNull("name")) af.put("name", af.getString("name") + f.getString("name"))
                            if (f.has("arguments") && !f.isNull("arguments")) af.put("arguments", af.getString("arguments") + f.getString("arguments"))
                        }
                    }
                }
            }
            val msg = JSONObject().put("role", "assistant").put("content", content.toString())
            usageObj?.let { msg.put("_usage", it) }
            if (calls.isNotEmpty()) msg.put("tool_calls", JSONArray(calls.values.mapIndexed { i, c ->
                if (c.getString("id").isEmpty()) c.put("id", "call_$i"); c }))
            msg
        }
    }
}
