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

/** v3.12: Screen OCR -> LLM chain (staleness/formatting) + proactive WorkManager rules (risk/scheduling/validation). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class V312Test {
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private fun store(): SecureStore {
        val p = ctx.getSharedPreferences("v312_settings", Context.MODE_PRIVATE)
        p.edit().clear().commit()
        return SecureStore(p)
    }
    private fun reg() = ToolRegistry(ctx, store())

    // ---- OCR presentation / staleness ----
    @Test fun ocr_no_capture_prompts_user() {
        val out = Ocr.formatForAgent(null, 0L, 1_000_000L)
        assertTrue(out.contains("Немає захопленого екрана"))
    }

    @Test fun ocr_fresh_capture_shows_text_and_age() {
        val now = 1_000_000L
        val out = Ocr.formatForAgent("Привіт екран", now - 30_000L, now)
        assertTrue(out.contains("Привіт екран"))
        assertTrue(out.contains("30 с тому"))
        assertFalse(out.contains("застарів"))
    }

    @Test fun ocr_stale_capture_warns() {
        val now = 1_000_000L
        val out = Ocr.formatForAgent("старий текст", now - 400_000L, now, maxAgeSec = 300)
        assertTrue(out.contains("застарів"))
        assertTrue(Ocr.isStale(now - 400_000L, now, 300))
        assertFalse(Ocr.isStale(now - 100_000L, now, 300))
    }

    @Test fun ocr_truncates_long_text() {
        val big = "x".repeat(9000)
        val out = Ocr.formatForAgent(big, 1L, 2L, maxChars = 8000)
        assertTrue(out.contains("обрізано"))
        assertTrue(out.length < 9000 + 200)
    }

    @Test fun ocr_cleanup_drops_noise_lines() {
        val cleaned = Ocr.cleanup("hello\n\n\n   \n\u0007world")
        assertEquals("hello\nworld", cleaned)
    }

    @Test fun ocr_age_seconds_null_when_never_captured() {
        assertNull(Ocr.ageSeconds(0L, 100L))
        assertEquals(5L, Ocr.ageSeconds(95_000L, 100_000L))
    }

    // ---- screen_ocr tool wiring + gating ----
    @Test fun screen_ocr_tool_registered_and_high_risk() {
        val spec = reg().specs.associateBy { it.name }["screen_ocr"]
        assertNotNull(spec)
        assertEquals(Risk.HIGH, spec!!.risk(JSONObject()))
    }

    @Test fun screen_ocr_tool_without_capture_prompts() = runBlocking {
        ScreenText.last = null; ScreenText.capturedAt = 0L
        val out = reg().specs.associateBy { it.name }.getValue("screen_ocr").exec(JSONObject())
        assertTrue(out.contains("Немає захопленого екрана"))
    }

    // ---- Rule policy: risk, scheduling, battery, validation ----
    @Test fun rule_risk_run_prompt_is_high_notify_low() {
        assertEquals(Risk.HIGH, RulePolicy.risk("run_prompt"))
        assertEquals(Risk.LOW, RulePolicy.risk("notify"))
        assertFalse(RulePolicy.runsInBackground("run_prompt"))
        assertTrue(RulePolicy.runsInBackground("notify"))
    }

    @Test fun interval_due_gating() {
        val now = 10_000_000L
        assertTrue("never run => due", RulePolicy.isDue(0L, 60, now))
        assertFalse("just ran => not due", RulePolicy.isDue(now - 60_000L, 60, now))
        assertTrue("window elapsed => due", RulePolicy.isDue(now - 61 * 60_000L, 60, now))
        // interval below WorkManager min is clamped to 15
        assertFalse(RulePolicy.isDue(now - 60_000L, 5, now))
        assertEquals(now + 15 * 60_000L, RulePolicy.nextDueMillis(now, 5))
    }

    @Test fun battery_low_trigger() {
        assertTrue(RulePolicy.batteryLowTriggered(15, 20))
        assertTrue(RulePolicy.batteryLowTriggered(20, 20))
        assertFalse(RulePolicy.batteryLowTriggered(50, 20))
        assertFalse("unknown level never triggers", RulePolicy.batteryLowTriggered(-1, 20))
        assertEquals(20, RulePolicy.batteryThreshold(""))
        assertEquals(35, RulePolicy.batteryThreshold("35"))
        assertEquals(100, RulePolicy.batteryThreshold("999"))
    }

    @Test fun rule_validation() {
        assertNotNull(RulePolicy.validate("", "charging", "notify", 60, ""))
        assertNotNull(RulePolicy.validate("n", "bogus", "notify", 60, ""))
        assertNotNull(RulePolicy.validate("n", "interval", "notify", 5, ""))
        assertNotNull(RulePolicy.validate("n", "wifi_ssid", "notify", 60, ""))
        assertNotNull(RulePolicy.validate("n", "battery_low", "notify", 60, "abc"))
        assertNull(RulePolicy.validate("n", "charging", "notify", 60, ""))
        assertNull(RulePolicy.validate("n", "battery_low", "run_prompt", 60, "20"))
    }

    // ---- Rule persistence round-trip (Room) ----
    @Test fun rule_persist_and_update_roundtrip() = runBlocking {
        val db = AppDb.get(ctx)
        val id = db.dao().insert(Rule(name = "v312-test", trigger = "battery_low", param = "15", action = "run_prompt", allowedPrompt = "say hi"))
        val loaded = db.dao().ruleById(id)
        assertNotNull(loaded)
        assertEquals("v312-test", loaded!!.name)
        assertEquals("run_prompt", loaded.action)
        db.dao().update(loaded.copy(enabled = false, lastRun = 123L))
        val updated = db.dao().ruleById(id)
        assertFalse(updated!!.enabled)
        assertEquals(123L, updated.lastRun)
        db.dao().deleteRule(id)
        assertNull(db.dao().ruleById(id))
    }

    // ---- gate-not-remove: prior features still present ----
    @Test fun prior_features_preserved() {
        val names = reg().specs.map { it.name }.toSet()
        assertTrue(names.containsAll(listOf("screen_ocr", "shell", "run_script", "file_write", "arp_scan", "os_detect", "adbtv", "ui_action")))
    }

    @Test fun rule_toJson_roundtrip_includes_new_fields() {
        val r = Rule(name = "r", trigger = "interval", intervalMinutes = 30, action = "run_prompt", allowedPrompt = "p", lastRun = 99L)
        val back = Rule.fromJson(r.toJson())
        assertEquals(30, back.intervalMinutes)
        assertEquals("run_prompt", back.action)
        assertEquals("p", back.allowedPrompt)
        assertEquals(99L, back.lastRun)
    }
}
