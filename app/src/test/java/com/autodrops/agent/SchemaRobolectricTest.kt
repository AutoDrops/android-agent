package com.autodrops.agent

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Uses Robolectric to get a real org.json on the classpath for the strict arg validator. */
@RunWith(RobolectricTestRunner::class)
class SchemaRobolectricTest {

    private val keyevent = Schema.obj(
        "action" to Schema.str("", listOf("keyevent", "launch", "shell")),
        "host" to Schema.str(""),
        "key" to Schema.str(""),
        "port" to Schema.int("", 1, 65535),
        required = listOf("action", "host"))

    @Test fun acceptsValidArgs() {
        assertNull(Schema.validate(keyevent, JSONObject("""{"action":"keyevent","host":"10.0.0.5","key":"DPAD_UP"}""")))
    }
    @Test fun rejectsMissingRequired() {
        assertTrue(Schema.validate(keyevent, JSONObject("""{"action":"keyevent"}""")).orEmpty().contains("host"))
    }
    @Test fun rejectsUnknownField() {
        assertTrue(Schema.validate(keyevent, JSONObject("""{"action":"keyevent","host":"h","evil":"x"}""")).orEmpty().contains("unknown"))
    }
    @Test fun rejectsBadEnum() {
        assertTrue(Schema.validate(keyevent, JSONObject("""{"action":"wipe","host":"h"}""")).orEmpty().contains("one of"))
    }
    @Test fun rejectsRangeViolation() {
        assertTrue(Schema.validate(keyevent, JSONObject("""{"action":"shell","host":"h","port":99999}""")).orEmpty().contains(">"))
    }
    @Test fun rejectsWrongType() {
        assertTrue(Schema.validate(keyevent, JSONObject("""{"action":"shell","host":123}""")).orEmpty().contains("string"))
    }

    @Test fun sanitizeStripsControlAndBidi() {
        val spoof = "ls\u202Erm -rf /\u0007"
        val clean = sanitizeForDisplay(spoof)
        assertFalse(clean.contains('\u202E'))
        assertFalse(clean.contains('\u0007'))
    }
}
