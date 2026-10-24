package com.autodrops.agent

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class V32Test {
    // ---- RAG index search ----
    private fun doc(id: Long, title: String, body: String) =
        RagDoc(id = id, title = title, content = body, embedding = Embeddings.encode(Embeddings.embed("$title $body")))

    @Test fun ragSearchRanksRelevantFirst() {
        val docs = listOf(
            doc(1, "Wi-Fi setup", "configure the router password and the network ssid at home"),
            doc(2, "Pancake recipe", "mix flour eggs milk and fry on a pan for breakfast"),
            doc(3, "Android TV", "control the television over adb and dlna on the local network")
        )
        val hits = Embeddings.search(Embeddings.embed("how to configure my router network ssid"), docs, 3)
        assertEquals(1L, hits.first().id)
        assertTrue(hits.first().score > hits.last().score)
    }
    @Test fun ragEncodeDecodeRoundTrip() {
        val v = Embeddings.embed("hello local vector memory")
        val back = Embeddings.decode(Embeddings.encode(v))
        assertEquals(Embeddings.DIM, back.size)
        assertEquals(1.0f, Embeddings.cosine(v, back), 1e-4f)
    }
    @Test fun ragEmptyQueryIsSafe() {
        assertTrue(Embeddings.search(Embeddings.embed(""), emptyList(), 5).isEmpty())
    }

    // ---- Rule scheduling serialization ----
    @Test fun ruleJsonRoundTrip() {
        val r = Rule(id = 7, name = "On charge", trigger = "charging", param = "", intervalMinutes = 30,
            action = "run_prompt", allowedPrompt = "scan my lan", enabled = true)
        val back = Rule.fromJson(JSONObject(r.toJson().toString()))
        assertEquals(r.name, back.name)
        assertEquals(r.trigger, back.trigger)
        assertEquals(r.intervalMinutes, back.intervalMinutes)
        assertEquals(r.action, back.action)
        assertEquals(r.allowedPrompt, back.allowedPrompt)
        assertEquals(r.enabled, back.enabled)
    }
    @Test fun ruleDefaultsApplied() {
        val back = Rule.fromJson(JSONObject().put("name", "x").put("trigger", "interval"))
        assertEquals(60, back.intervalMinutes)
        assertEquals("notify", back.action)
        assertTrue(back.enabled)
    }

    // ---- OCR cleanup ----
    @Test fun ocrCleanupStripsNoiseAndControlChars() {
        val raw = "Hello\u0000World\n\n   \n\t  Line  two \n***\n\n\n42"
        val out = Ocr.cleanup(raw)
        assertFalse(out.contains("\u0000"))
        assertTrue(out.contains("HelloWorld"))
        assertTrue(out.contains("Line two"))
        assertTrue(out.contains("42"))
        assertFalse(out.contains("***")) // punctuation-only line dropped
        assertFalse(out.contains("\n\n\n"))
    }
}
