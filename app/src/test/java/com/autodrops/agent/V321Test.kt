package com.autodrops.agent

import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class V321Test {
    @Test fun redirectsToFirstPartyCdnAllowed() {
        assertTrue(DownloadRedirects.allowed("github.com", "release-assets.githubusercontent.com"))
        assertTrue(DownloadRedirects.allowed("huggingface.co", "us.aws.cdn.hf.co"))
        assertTrue(DownloadRedirects.allowed("huggingface.co", "cas-bridge.xethub.hf.co"))
        assertFalse(DownloadRedirects.allowed("github.com", "evil.example.com"))
        assertFalse(DownloadRedirects.allowed("huggingface.co", "hf.co.evil.com"))
        assertFalse(DownloadRedirects.allowed("unknown.org", "cdn.unknown.org"))
    }
    @Test fun friendlyErrorKeepsRawCause() {
        val m = Protocols.friendlyError("HTTP 401: User not found.")
        assertTrue(m.contains("401") && m.contains("User not found"))
        assertTrue(Protocols.friendlyError("weird").contains("weird"))
    }
    @Test fun bootLogFlagsMissingKey() {
        assertTrue(BootLog.lines(false, "m", "3.21").any { it.contains("[FAIL] Введіть ключ OpenRouter") })
        assertFalse(BootLog.lines(true, "m", "3.21").any { it.contains("[FAIL]") })
    }
    @Test fun noEmbeddedKeyInBuild() { assertEquals("", BuildConfig.EMBEDDED_API_KEY) }

    /** Live: every catalog URL reachable via the same redirect policy the app enforces (range 0-0). */
    @Test fun liveCatalogUrlsReachable() {
        assumeTrue(System.getenv("DRON_LIVE") == "1")
        val http = OkHttpClient()
        val urls = ModelCatalogLocal.CATALOG.flatMap { it.downloadUrls() }
        for (u in urls) {
            http.newCall(Request.Builder().url(u).header("Range", "bytes=0-0").build()).execute().use { r ->
                assertTrue("$u -> ${r.code}", r.code == 206 || r.code == 200)
                assertTrue("$u redirect to ${r.request.url.host}", DownloadRedirects.allowed(java.net.URI(u).host, r.request.url.host))
            }
        }
    }
}
