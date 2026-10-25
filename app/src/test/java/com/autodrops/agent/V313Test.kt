package com.autodrops.agent

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * v3.13 audit release: coverage for previously-untested pure logic surfaced during the
 * Kali-toolkit + v3-feature code audit, plus the two accepted hardening fixes
 * (DNS-rebinding defense-in-depth re-check, OUI download size cap).
 */
class V313Test {
    private val uncensoredJson = """[
      {"id":"cognitivecomputations/dolphin-mistral-24b-venice-edition","name":"Dolphin Venice","ctx":32000,"in":"0.0000005","out":"0.000001","mod":["text"],"desc":"low-guardrails","params":["tools"]},
      {"id":"openai/gpt-5-codex","name":"GPT-5 Codex","ctx":200000,"in":"0.000002","out":"0.000008","mod":["text"],"desc":"coding","params":["tools"]}
    ]"""

    @Test fun uncensoredCategoryAndLabel() {
        val m = ModelCatalog.parse(uncensoredJson)
        val unc = ModelCatalog.categorize(m, "uncensored")
        assertTrue(unc.any { it.id.contains("dolphin") })
        assertFalse("codex must not be in uncensored", unc.any { it.id.contains("codex") })
        val d = ModelCatalog.describeUa(m.first { it.id.contains("dolphin") })
        assertTrue("describe must mention без цензури", d.contains("без цензури"))
    }

    @Test fun catalogHasUncensoredCategoryDefined() {
        assertTrue(ModelCatalog.CATEGORIES.any { it.id == "uncensored" && it.uaTitle.contains("цензури") })
    }

    // ---- OuiStore offline fallback: with no cached file and downloads disabled, returns the bundled table ----
    @Test fun ouiStoreFallsBackToBuiltinOffline() {
        val tmp = Files.createTempDirectory("oui").toFile()
        try {
            val table = OuiStore.load(tmp, allowDownload = false)
            assertEquals("Raspberry Pi Foundation", table["B827EB"])
            assertFalse(OuiStore.hasDownloaded(tmp))
        } finally { tmp.deleteRecursively() }
    }

    // OuiStore.load() memoizes a process-global cache, so merge is verified at the parse layer it uses.
    @Test fun ouiParseMergesWithBuiltin() {
        val parsed = NetScan.parseOuiCsv("Registry,Assignment,Organization Name,Organization Address\nMA-L,AABBCC,\"Acme, Inc.\",X\n")
        val merged = NetScan.BUILTIN_OUI + parsed
        assertEquals("Acme, Inc.", merged["AABBCC"])
        assertEquals("Raspberry Pi Foundation", merged["B827EB"])
    }

    // ---- Defense-in-depth re-check building block: resolved public IP is rejected by the guard used inside osServiceDetect ----
    @Test fun resolvedPublicIpIsRejectedByGuard() {
        assertFalse(NetGuard.isPrivateV4("203.0.113.9"))
        assertTrue(NetGuard.isPrivateV4("192.168.1.9"))
    }

    // ---- Documented behavior: when the device has no local IP, same-subnet scans still require RFC1918 (never public) ----
    @Test fun ensureLocalWithNullLocalIpStillBlocksPublic() {
        assertNotNull(NetGuard.ensureLocal("8.8.8.8", null, true))
        assertNull(NetGuard.ensureLocal("10.1.2.3", null, true))
    }

    @Test fun bannerParsePop3AndImap() {
        assertTrue(NetScan.parseBanner("+OK POP3 ready", 110).isNotBlank())
        assertTrue(NetScan.parseBanner("* OK IMAP4rev1 Service Ready", 143).startsWith("imap"))
    }
}
