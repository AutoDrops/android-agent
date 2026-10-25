package com.autodrops.agent

import org.junit.Assert.*
import org.junit.Test

/** v3.17: crash-report formatting, capabilities listing, self-test modeling, permission mapping. */
class V317Test {

    // ---- CrashReport ----
    @Test fun crashFormatHasHeaderAndStack() {
        val r = CrashReport.format(
            threadName = "main", throwableClass = "java.lang.IllegalStateException",
            message = "boom", stack = listOf("com.a.B.c(B.kt:10)", "com.a.B.d(B.kt:20)"),
            epochMs = 0L, appVersion = "3.17", deviceModel = "Pixel 7", sdkInt = 34)
        assertTrue(r.contains("DRON.exe crash report"))
        assertTrue(r.contains("version: 3.17"))
        assertTrue(r.contains("device: Pixel 7"))
        assertTrue(r.contains("thread: main"))
        assertTrue(r.contains("type: java.lang.IllegalStateException"))
        assertTrue(r.contains("message: boom"))
        assertTrue(r.contains("com.a.B.c(B.kt:10)"))
    }

    @Test fun crashFormatHandlesNullMessageAndEmptyStack() {
        val r = CrashReport.format("worker", "E", null, emptyList(), 0L)
        assertTrue(r.contains("message: (none)"))
        assertTrue(r.contains("(no stack)"))
    }

    @Test fun crashFormatFromThrowableWalksCause() {
        val root = IllegalArgumentException("root-cause")
        val t = RuntimeException("outer", root)
        val r = CrashReport.format("main", t, 0L, appVersion = "3.17")
        assertTrue(r.contains("type: java.lang.RuntimeException"))
        assertTrue(r.contains("Caused by: java.lang.IllegalArgumentException"))
    }

    @Test fun crashHeadlineExtractsType() {
        val r = CrashReport.format("main", "X", "m", listOf("a(a.kt:1)"), 0L)
        assertEquals("X", CrashReport.shortHeadline(r))
    }

    // ---- Capabilities ----
    @Test fun gatingAndRiskUaCoverAllRisks() {
        Risk.values().forEach {
            assertTrue(Capabilities.gatingUa(it).isNotBlank())
            assertTrue(Capabilities.riskUa(it).isNotBlank())
        }
        assertTrue(Capabilities.gatingUa(Risk.LOW).contains("Без підтвердження"))
        assertTrue(Capabilities.gatingUa(Risk.HIGH).contains("підтвердження"))
    }

    @Test fun capabilityRowsPreferUaDocsElseFallback() {
        val rows = Capabilities.rows(listOf(
            Triple("shell", "English description", Risk.HIGH),
            Triple("made_up_tool", "fallback desc", Risk.LOW)))
        val shell = rows.first { it.name == "shell" }
        assertEquals(ToolDocs.ua("shell"), shell.description)
        assertNotEquals("English description", shell.description)
        val mk = rows.first { it.name == "made_up_tool" }
        assertEquals("fallback desc", mk.description)
        assertEquals(Capabilities.gatingUa(Risk.HIGH), shell.gating)
    }

    // ---- SelfTest ----
    @Test fun selfTestOverallPriority() {
        assertEquals(CheckStatus.PASS, SelfTest.overall(listOf(CheckResult("a", CheckStatus.PASS, ""))))
        assertEquals(CheckStatus.WARN, SelfTest.overall(listOf(CheckResult("a", CheckStatus.PASS, ""), CheckResult("b", CheckStatus.WARN, ""))))
        assertEquals(CheckStatus.FAIL, SelfTest.overall(listOf(CheckResult("a", CheckStatus.WARN, ""), CheckResult("b", CheckStatus.FAIL, ""))))
        assertEquals(CheckStatus.SKIP, SelfTest.overall(listOf(CheckResult("a", CheckStatus.SKIP, ""))))
    }

    @Test fun selfTestSummaryCounts() {
        val r = listOf(
            CheckResult("a", CheckStatus.PASS, ""), CheckResult("b", CheckStatus.PASS, ""),
            CheckResult("c", CheckStatus.FAIL, ""), CheckResult("d", CheckStatus.WARN, ""),
            CheckResult("e", CheckStatus.SKIP, ""))
        assertEquals("✓2 ✗1 ⚠1 –1", SelfTest.summary(r))
    }

    @Test fun selfTestIcons() {
        assertEquals("✓", SelfTest.icon(CheckStatus.PASS))
        assertEquals("✗", SelfTest.icon(CheckStatus.FAIL))
    }

    // ---- PermissionCatalog ----
    @Test fun permissionSdkFiltering() {
        val legacy = PermissionCatalog.forSdk(26)
        val modern = PermissionCatalog.forSdk(34)
        assertTrue(modern.size >= legacy.size)
        assertTrue(modern.any { it.permission.endsWith("POST_NOTIFICATIONS") })
        assertFalse(legacy.any { it.permission.endsWith("POST_NOTIFICATIONS") })
        assertFalse(legacy.any { it.permission.endsWith("NEARBY_WIFI_DEVICES") })
    }

    @Test fun permissionStatusMapping() {
        assertEquals(CheckStatus.PASS, PermissionCatalog.statusCheck(true))
        assertEquals(CheckStatus.WARN, PermissionCatalog.statusCheck(false))
        assertEquals("надано", PermissionCatalog.statusLabel(true))
        assertEquals("READ_SMS", PermissionCatalog.shortName("android.permission.READ_SMS"))
        assertTrue(PermissionCatalog.ALL.all { it.rationale.isNotBlank() && it.uaName.isNotBlank() })
    }
}
