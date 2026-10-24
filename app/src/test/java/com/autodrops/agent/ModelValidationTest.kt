package com.autodrops.agent

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ModelValidationTest {
    private val known = listOf("openai/gpt-4o-mini", "anthropic/claude-haiku-5.5", "anthropic/claude-sonnet-5")

    @Test fun valid_full_slug_passes() {
        assertNull(ModelValidation.validateSlug("anthropic/claude-haiku-5.5", known))
    }

    @Test fun missing_vendor_prefix_suggests_full_slug() {
        // This is the v3.6 HTTP 400 root cause: slug sent without the "anthropic/" prefix.
        val err = ModelValidation.validateSlug("claude-haiku-5.5", known)
        assertNotNull(err)
        assertTrue(err!!.contains("anthropic/claude-haiku-5.5"))
    }

    @Test fun unknown_model_reports_clearly() {
        val err = ModelValidation.validateSlug("vendor/does-not-exist", known)
        assertNotNull(err)
        assertTrue(err!!.contains("Оновити"))
    }

    @Test fun blank_model_reported() {
        assertNotNull(ModelValidation.validateSlug("  ", known))
    }

    @Test fun no_catalog_accepts_wellformed_slug_but_flags_bare() {
        assertNull(ModelValidation.validateSlug("anthropic/claude-haiku-5.5", emptyList()))
        assertNotNull(ModelValidation.validateSlug("claude-haiku-5.5", emptyList()))
    }

    @Test fun empty_messages_rejected() {
        assertNotNull(ModelValidation.validateMessages(JSONArray()))
    }

    @Test fun valid_messages_pass() {
        val a = JSONArray().put(JSONObject().put("role", "user").put("content", "hi"))
        assertNull(ModelValidation.validateMessages(a))
    }

    @Test fun bad_role_rejected() {
        val a = JSONArray().put(JSONObject().put("role", "robot").put("content", "hi"))
        assertNotNull(ModelValidation.validateMessages(a))
    }

    @Test fun extract_error_parses_openrouter_body() {
        val body = """{"error":{"message":"claude-haiku-5.5 is not a valid model ID","code":400}}"""
        val msg = ModelValidation.extractError(400, body)
        assertTrue(msg.contains("not a valid model ID"))
        assertTrue(msg.startsWith("HTTP 400"))
    }

    @Test fun extract_error_falls_back_to_raw() {
        assertTrue(ModelValidation.extractError(500, "upstream boom").contains("upstream boom"))
        assertEquals("HTTP 502", ModelValidation.extractError(502, ""))
    }
}
