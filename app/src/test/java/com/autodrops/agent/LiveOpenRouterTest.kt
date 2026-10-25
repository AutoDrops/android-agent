package com.autodrops.agent

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/** Live E2E of the app's REAL LlmClient against openrouter.ai. Key read at runtime, never printed. Skipped if absent. */
@RunWith(RobolectricTestRunner::class)
class LiveOpenRouterTest {
    private val keyFile = File(System.getenv("DRON_LIVE_KEY_FILE") ?: "/workspace/free-api/keys/openrouter-dron.key")
    private fun key(): String { assumeTrue(keyFile.canRead()); return keyFile.readText().trim() }
    private fun rethrowUnlessBudget(e: RuntimeException) { assumeFalse("budget/rate: ${e.message}", e.message?.contains("HTTP 402")==true || e.message?.contains("HTTP 429")==true); throw e }
    private val model = System.getenv("DRON_LIVE_MODEL") ?: SecureStore.DEFAULT_MODEL

    @Test fun modelsListContainsDefault() = try { runBlocking {
        val ms = LlmClient().models(SecureStore.DEFAULT_BASE, key())
        assertTrue(ms.size > 10); assertTrue("default model in live list", ms.any { it.id == SecureStore.DEFAULT_MODEL })
    } } catch (e: RuntimeException) { rethrowUnlessBudget(e) }

    @Test fun streamingChatAndToolRound() = try { runBlocking {
        val k = key(); val c = LlmClient()
        val chat = c.complete(SecureStore.DEFAULT_BASE, k, model,
            JSONArray().put(JSONObject().put("role", "user").put("content", "Reply with the word PONG only.")), JSONArray(), true) {}
        assertTrue(chat.optString("content"), chat.optString("content").contains("PONG", true))
        val tools = JSONArray().put(JSONObject().put("type", "function").put("function", JSONObject()
            .put("name", "get_time").put("description", "Get current time")
            .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject()))))
        val hist = JSONArray().put(JSONObject().put("role", "user").put("content", "Call get_time, then tell me the time."))
        val m1 = c.complete(SecureStore.DEFAULT_BASE, k, model, hist, tools, true) {}
        val calls = m1.optJSONArray("tool_calls"); assertTrue("tool call returned", calls != null && calls.length() > 0)
        m1.remove("_usage"); hist.put(m1)
        hist.put(JSONObject().put("role", "tool").put("tool_call_id", calls!!.getJSONObject(0).getString("id")).put("content", "12:34"))
        val m2 = c.complete(SecureStore.DEFAULT_BASE, k, model, hist, tools, true) {}
        assertTrue(m2.optString("content"), m2.optString("content").contains("12:34") || m2.optString("content").contains("12"))
    } } catch (e: RuntimeException) { rethrowUnlessBudget(e) }

    @Test fun badKeyGivesClearHttpError() = runBlocking {
        key()
        val e = runCatching { LlmClient().complete(SecureStore.DEFAULT_BASE, "sk-or-v1-invalid", model,
            JSONArray().put(JSONObject().put("role", "user").put("content", "hi")), JSONArray(), true) {} }.exceptionOrNull()
        assertTrue(e?.message ?: "none", e?.message?.startsWith("HTTP 401") == true)
    }
}
