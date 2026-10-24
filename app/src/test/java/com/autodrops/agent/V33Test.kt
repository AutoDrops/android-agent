package com.autodrops.agent

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class V33Test {
    // ---- Chunking ----
    @Test fun chunkSmallTextSinglePiece() {
        assertEquals(1, Chunking.chunk("short note", 1200).size)
    }
    @Test fun chunkLargeTextOverlaps() {
        val txt = (1..400).joinToString(" ") { "word$it." }
        val chunks = Chunking.chunk(txt, 300, 50)
        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { it.length <= 360 })
        // reconstruct coverage: every word present somewhere
        assertTrue(chunks.joinToString(" ").contains("word399"))
    }
    @Test fun chunkEmpty() { assertTrue(Chunking.chunk("   ").isEmpty()) }

    // ---- Plan codec ----
    @Test fun planFromStringsAndRoundTrip() {
        val steps = PlanCodec.fromStrings("[\"scan lan\",\"label tv\",\"report\"]")
        assertEquals(3, steps.size)
        assertEquals("pending", steps[0].status)
        steps[1].status = "done"
        val back = PlanCodec.decode(PlanCodec.encode(steps))
        assertEquals("done", back[1].status)
        assertEquals("report", back[2].text)
    }
    @Test fun planFromBadJson() { assertTrue(PlanCodec.fromStrings("not json").isEmpty()) }

    // ---- Device inventory json ----
    @Test fun deviceJsonRoundTrip() {
        val d = DeviceEntity(id = 3, ip = "192.168.1.10", mac = "aa:bb", label = "TV")
        val back = DeviceEntity.fromJson(JSONObject(d.toJson().toString()))
        assertEquals(d.ip, back.ip); assertEquals(d.label, back.label); assertEquals(d.mac, back.mac)
    }

    // ---- Export bundle (never leaks secrets) ----
    @Test fun exportExcludesApiKeyAndRoundTrips() {
        val settings = JSONObject().put("base_url", "https://openrouter.ai/api/v1").put("model", "x/y")
            .put("api_key", "sk-or-SECRET").put("approved_hosts", "evil.com")
        val rag = listOf(RagDoc(title = "n1", content = "body one", embedding = ""))
        val rules = listOf(Rule(name = "r1", trigger = "charging"))
        val devices = listOf(DeviceEntity(ip = "10.0.0.5", label = "nas"))
        val json = ExportBundle.build(settings, rag, rules, devices)  // pass secrets directly; build must strip them
        assertFalse(json.contains("sk-or-SECRET"))
        assertFalse(json.contains("evil.com"))
        val parsed = ExportBundle.parse(json)
        assertEquals(1, parsed.rag.size); assertEquals("n1", parsed.rag[0].first)
        assertEquals(1, parsed.rules.size); assertEquals(1, parsed.devices.size)
        assertEquals("nas", parsed.devices[0].label)
    }
    @Test fun parseStripsSecretsDefensively() {
        val malicious = JSONObject().put("settings", JSONObject().put("api_key", "sk-leak").put("model", "m"))
            .put("rag", org.json.JSONArray()).put("rules", org.json.JSONArray()).put("devices", org.json.JSONArray()).toString()
        val p = ExportBundle.parse(malicious)
        assertFalse(p.settings.has("api_key"))
        assertEquals("m", p.settings.optString("model"))
    }

    // ---- RagContext ----
    @Test fun ragContextFiltersLowScores() {
        val hits = listOf(Embeddings.Scored(1, "t", "c", 0.5f), Embeddings.Scored(2, "t2", "c2", 0.01f))
        val ctx = RagContext.build(hits, 0.08f)
        assertNotNull(ctx)
        assertTrue(ctx!!.contains("t")); assertFalse(ctx.contains("c2"))
        assertNull(RagContext.build(emptyList()))
    }

    // ---- Cost/Usage ----
    @Test fun usageEstimateAndSum() {
        val est = Usage.estimate(1000, 500, "0.000001", "0.000002")
        assertEquals(0.002, est, 1e-9)
        val sum = Usage(10, 5, 0.1) + Usage(20, 5, 0.2)
        assertEquals(30, sum.promptTokens); assertEquals(40, sum.totalTokens)
        assertEquals(0.3, sum.cost, 1e-9)
    }
}
