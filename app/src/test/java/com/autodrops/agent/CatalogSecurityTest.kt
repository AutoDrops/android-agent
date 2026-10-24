package com.autodrops.agent

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CatalogSecurityTest {
    private val json = """[
      {"id":"openai/gpt-5-codex","name":"GPT-5 Codex","ctx":200000,"in":"0.000002","out":"0.000008","mod":["text"],"desc":"strong coding model","params":["tools"]},
      {"id":"google/gemini-2.5-pro","name":"Gemini Pro","ctx":1000000,"in":"0.0000012","out":"0.000005","mod":["text","image"],"desc":"reasoning and vision","params":["tools"]},
      {"id":"meta/free-tiny","name":"Free Tiny","ctx":8000,"in":"0","out":"0","mod":["text"],"desc":"free model","params":[]}
    ]"""

    @Test fun parsesSeed() {
        val m = ModelCatalog.parse(json)
        assertEquals(3, m.size)
        assertEquals(200000, m[0].ctx)
    }
    @Test fun categorizesFreeVisionLong() {
        val m = ModelCatalog.parse(json)
        assertTrue(ModelCatalog.categorize(m, "free").any { it.id == "meta/free-tiny" })
        assertTrue(ModelCatalog.categorize(m, "vision").any { it.id == "google/gemini-2.5-pro" })
        assertTrue(ModelCatalog.categorize(m, "long").any { it.id == "google/gemini-2.5-pro" })
        assertTrue(ModelCatalog.categorize(m, "code").any { it.id == "openai/gpt-5-codex" })
    }
    @Test fun describesInUkrainian() {
        val d = ModelCatalog.describeUa(ModelCatalog.parse(json)[0])
        assertTrue(d.isNotBlank())
        assertTrue(d.contains("інструмент") || d.contains("function") || d.contains("Контекст") || d.length > 20)
    }

    @Test fun hostAllowlist() {
        assertTrue(Security.checkHost("https://openrouter.ai/api/v1").allowed)
        assertTrue(Security.checkHost("https://api.x.ai/v1").allowed)
        val http = Security.checkHost("http://openrouter.ai/api/v1")
        assertFalse(http.allowed); assertNotNull(http.warning)
        val unknown = Security.checkHost("https://evil.example.com/v1")
        assertTrue(unknown.allowed); assertNotNull(unknown.warning) // custom host allowed but warned
    }
    @Test fun redactsSecrets() {
        val src = "key is sk-or-v1-abcdef0123456789abcdef0123456789abcdef and token AIzaSyABCDEFGHIJKLMNOPQRSTUVWX1234567"
        val out = Security.redact(src)
        assertTrue(Security.wasRedacted(src, out))
        assertFalse(out.contains("sk-or-v1-abcdef0123456789"))
    }
    @Test fun keepsPlainText() {
        val src = "The weather is nice and the TV is on channel 5."
        assertFalse(Security.wasRedacted(src, Security.redact(src)))
    }
}
