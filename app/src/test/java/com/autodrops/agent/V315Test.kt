package com.autodrops.agent

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v3.15: phone-communication + clipboard control tools.
 * Pure logic (SMS parse/format, number/message validation, permission-gating)
 * plus tool-registry risk-policy wiring. Built + unit-tested; not verified on real hardware.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class V315Test {
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private fun store(): SecureStore {
        val p = ctx.getSharedPreferences("v315_settings", Context.MODE_PRIVATE)
        p.edit().clear().commit(); return SecureStore(p)
    }
    private fun reg() = ToolRegistry(ctx, store())
    private fun riskOf(name: String): Risk = reg().toolRisks().first { it.first == name }.second

    // ---- Permission gating (pure, fail-closed) ----
    @Test fun perms_missing_detects_absent() {
        val granted = setOf("android.permission.READ_SMS")
        val miss = Perms.missing(granted, listOf("android.permission.READ_SMS", "android.permission.SEND_SMS"))
        assertEquals(listOf("android.permission.SEND_SMS"), miss)
    }
    @Test fun perms_missing_empty_when_all_granted() {
        assertTrue(Perms.missing(setOf("a", "b"), listOf("a", "b")).isEmpty())
    }

    // ---- SMS number / message validation ----
    @Test fun sms_number_validation() {
        assertTrue(SmsFormat.validNumber("+380671234567"))
        assertTrue(SmsFormat.validNumber("(050) 123-45-67"))
        assertFalse(SmsFormat.validNumber(""))
        assertFalse(SmsFormat.validNumber("hello"))
        assertFalse(SmsFormat.validNumber("1".repeat(25)))
    }
    @Test fun sms_message_validation() {
        assertTrue(SmsFormat.validMessage("hi"))
        assertFalse(SmsFormat.validMessage(""))
        assertFalse(SmsFormat.validMessage("x".repeat(1601)))
    }

    // ---- SMS inbox formatting ----
    @Test fun sms_format_empty() { assertEquals("no SMS", SmsFormat.format(emptyList())) }
    @Test fun sms_format_renders_and_limits() {
        val msgs = (1..5).map { SmsFormat.Msg("+38050$it", "body $it", 0L) }
        val out = SmsFormat.format(msgs, 3)
        assertEquals(3, out.lines().size)
        assertTrue(out.contains("body 1"))
        assertTrue(out.contains("1970-01-01"))
    }
    @Test fun sms_format_sanitizes_control_chars() {
        val out = SmsFormat.format(listOf(SmsFormat.Msg("+1", "a\u202Eb", 0L)))
        assertFalse(out.contains("\u202E"))
    }

    // ---- Risk-policy wiring of new + existing comm tools ----
    @Test fun sms_send_is_high() { assertEquals(Risk.HIGH, riskOf("sms_send")) }
    @Test fun sms_read_is_sensitive() { assertEquals(Risk.SENSITIVE, riskOf("sms_read")) }
    @Test fun notification_post_is_low() { assertEquals(Risk.LOW, riskOf("notification_post")) }
    @Test fun notifications_read_is_sensitive() { assertEquals(Risk.SENSITIVE, riskOf("notifications")) }
    @Test fun contacts_is_sensitive() { assertEquals(Risk.SENSITIVE, riskOf("contacts")) }

    @Test fun clipboard_read_sensitive_write_effect() {
        val r = reg()
        val spec = r.specs.first { it.name == "clipboard" }
        assertEquals(Risk.SENSITIVE, spec.risk(JSONObject().put("op", "get")))
        assertEquals(Risk.EFFECT, spec.risk(JSONObject().put("op", "set")))
    }

    // ---- Fail-closed exec: invalid sms_send args rejected before any send ----
    @Test fun sms_send_rejects_bad_number() = runBlocking {
        val spec = reg().specs.first { it.name == "sms_send" }
        val res = spec.exec(JSONObject().put("number", "nope").put("message", "hi"))
        assertEquals("invalid number", res)
    }
    @Test fun sms_send_rejects_empty_message() = runBlocking {
        val spec = reg().specs.first { it.name == "sms_send" }
        val res = spec.exec(JSONObject().put("number", "+380671234567").put("message", ""))
        assertEquals("invalid message (empty or >1600 chars)", res)
    }

    // ---- Schema validation rejects unknown fields (fail-closed) ----
    @Test fun sms_read_schema_rejects_unknown() {
        val spec = reg().specs.first { it.name == "sms_read" }
        assertNotNull(Schema.validate(spec.schema, JSONObject().put("bogus", 1)))
        assertNull(Schema.validate(spec.schema, JSONObject().put("limit", 10)))
    }
}
