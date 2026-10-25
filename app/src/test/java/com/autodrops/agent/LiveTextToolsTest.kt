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

/** Live: models that only narrate get the text tool protocol; the parser must extract a real call, result is fed back. Key never printed. */
@RunWith(RobolectricTestRunner::class)
class LiveTextToolsTest {
    private val keyFile = File(System.getenv("DRON_LIVE_KEY_FILE") ?: "/workspace/free-api/keys/openrouter-dron.key")
    private val known = setOf("shell", "ping", "file_read")

    private fun run(model: String): String = try { runBlocking {
        assumeTrue(keyFile.canRead()); val k = keyFile.readText().trim(); val c = LlmClient()
        val h = JSONArray().put(JSONObject().put("role", "system").put("content", "You are an on-device Android agent.\n\n" + TextTools.instructions(known)))
            .put(JSONObject().put("role", "user").put("content", "How much RAM is free on my phone? Run free -m."))
        var calls = emptyList<TextTools.Call>(); var log = ""
        for (i in 0..2) {
            val m = c.complete(SecureStore.DEFAULT_BASE, k, model, h, JSONArray(), false) {}
            val txt = m.optString("content"); m.remove("_usage"); h.put(m); log += "[$i] ${txt.take(200)}\n"
            calls = TextTools.parse(txt, known)
            if (calls.isNotEmpty()) break
            h.put(JSONObject().put("role", "user").put("content", TextTools.NUDGE))
        }
        assertTrue("no tool call parsed from $model:\n$log", calls.isNotEmpty())
        h.put(JSONObject().put("role", "user").put("content", TextTools.resultMessage(calls[0].name, calls[0].arguments.toString(),
            "              total        used        free\nMem:           7712        4100        3612")))
        val fin = c.complete(SecureStore.DEFAULT_BASE, k, model, h, JSONArray(), false) {}.optString("content")
        println("LIVE $model call=${calls[0].name} ${calls[0].arguments} final=${fin.take(160).replace('\n',' ')}")
        assertTrue("final mentions result: $fin", fin.contains("3612") || fin.contains("3.6") || fin.contains("3,6") || fin.contains("3.5"))
        fin
    } } catch (e: RuntimeException) {
        assumeFalse("budget/rate: ${e.message}", e.message?.contains("HTTP 402") == true || e.message?.contains("HTTP 429") == true); throw e
    }
    @Test fun dolphinVenice() { run(System.getenv("DRON_DOLPHIN") ?: "cognitivecomputations/dolphin-mistral-24b-venice-edition:free") }
    @Test fun freeModel() { run(System.getenv("DRON_FREE") ?: "poolside/laguna-s-2.1:free") }
}
