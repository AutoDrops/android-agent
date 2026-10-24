package com.autodrops.agent

import org.junit.Assert.*
import org.junit.Test

class V34Test {
    @Test fun sha256_known_vector() {
        // sha256("abc")
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            Hashing.sha256Hex("abc".toByteArray()))
    }
    @Test fun sha256_verify_caseinsensitive_and_mismatch() {
        val h = Hashing.sha256Hex("x".toByteArray())
        assertTrue(Hashing.verify(h.uppercase(), h))
        assertFalse(Hashing.verify(h, "deadbeef"))
        assertFalse(Hashing.verify("", ""))
    }
    @Test fun model_catalog_valid() {
        assertTrue(ModelCatalogLocal.CATALOG.isNotEmpty())
        ModelCatalogLocal.CATALOG.forEach {
            assertTrue(it.url.startsWith("https://"))
            assertTrue(it.id.isNotBlank()); assertTrue(it.sizeMb > 0)
        }
        assertNotNull(ModelCatalogLocal.byId(ModelCatalogLocal.CATALOG.first().id))
        assertNull(ModelCatalogLocal.byId("nope"))
    }
    @Test fun model_json_roundtrip() {
        val m = ModelCatalogLocal.CATALOG.first()
        assertEquals(m, LocalModel.fromJson(m.toJson()))
    }
    @Test fun tool_profiles_disable_sets() {
        val tools = listOf("read" to Risk.LOW, "write" to Risk.EFFECT, "nuke" to Risk.HIGH, "root" to Risk.ELEVATED)
        assertEquals(emptySet<String>(), ToolProfiles.disabledFor("full", tools))
        assertEquals(setOf("nuke", "root"), ToolProfiles.disabledFor("safe", tools))
        assertEquals(setOf("write", "nuke", "root"), ToolProfiles.disabledFor("read_only", tools))
    }
    @Test fun recipe_bundle_roundtrip_and_bare_array() {
        val json = RecipeBundle.build(listOf(Recipe(name = "A", prompt = "do a"), Recipe(name = "B", prompt = "do b")))
        val parsed = RecipeBundle.parse(json)
        assertEquals(2, parsed.size); assertEquals("A", parsed[0].first); assertEquals("do a", parsed[0].second)
        val bare = RecipeBundle.parse("""[{"name":"C","prompt":"do c"}]""")
        assertEquals(1, bare.size); assertEquals("C", bare[0].first)
        assertTrue(RecipeBundle.parse("""{"recipes":[{"prompt":""}]}""").isEmpty())
    }
    @Test fun local_llm_honest_status() {
        assertFalse(LocalLlm.ENGINE_PRESENT)
        assertTrue(LocalLlm.status(false).contains("не завантажено"))
        assertTrue(LocalLlm.status(true).contains("розробці"))
        assertTrue(LocalLlm.offlineFallback().isNotBlank())
    }
}
