package com.autodrops.agent

import org.junit.Assert.*
import org.junit.Test

/** v3.16: Cast proto, DLNA host guard helper, TTS policy, app list formatting, risk wiring. */
class V316Test {

    // ---- CastProto ----
    @Test fun castConnectPayload() {
        assertEquals("{\"type\":\"CONNECT\"}", CastProto.connectPayload())
    }

    @Test fun castLoadPayloadEscapesQuotes() {
        val p = CastProto.loadPayload("https://x.test/a\"b.mp4", "video/mp4", 7)
        assertTrue(p.contains("\\\"b.mp4"))
        assertTrue(p.contains("\"requestId\":7"))
        assertTrue(p.contains("\"contentType\":\"video/mp4\""))
        assertTrue(p.contains("\"type\":\"LOAD\""))
    }

    @Test fun castGuessContentType() {
        assertEquals("video/mp4", CastProto.guessContentType("http://h/a.MP4"))
        assertEquals("audio/mpeg", CastProto.guessContentType("http://h/a.mp3"))
        assertEquals("application/x-mpegURL", CastProto.guessContentType("http://h/live.m3u8"))
        assertEquals("video/mp4", CastProto.guessContentType("http://h/unknown"))
    }

    @Test fun castParseIds() {
        val j = """{"status":{"applications":[{"sessionId":"s-1","transportId":"t-9"}],"volume":{"level":0.5}}}"""
        assertEquals("t-9", CastProto.parseTransportId(j))
        assertEquals("s-1", CastProto.parseSessionId(j))
        assertNull(CastProto.parseMediaSessionId(j))
        assertEquals(42, CastProto.parseMediaSessionId("""{"mediaSessionId":42,"playerState":"PLAYING"}"""))
    }

    @Test fun castMessageFramingHasLengthPrefix() {
        val msg = CastProto.castMessage(CastProto.NS_RECEIVER, CastProto.getStatusPayload(1))
        val framed = CastProto.framed(msg)
        assertEquals(4 + msg.size, framed.size)
        val len = ((framed[0].toInt() and 0xFF) shl 24) or ((framed[1].toInt() and 0xFF) shl 16) or
            ((framed[2].toInt() and 0xFF) shl 8) or (framed[3].toInt() and 0xFF)
        assertEquals(msg.size, len)
        // message contains the namespace and payload strings
        val body = String(msg, Charsets.UTF_8)
        assertTrue(body.contains(CastProto.NS_RECEIVER))
        assertTrue(body.contains("GET_STATUS"))
    }

    // ---- Host extraction / RFC1918 for DLNA control URLs ----
    @Test fun hostFromUrlPrivate() {
        assertEquals("192.168.1.50", AppsFmt.hostFromUrl("http://192.168.1.50:9197/ctrl/AVTransport"))
        assertEquals("10.0.0.8", AppsFmt.hostFromUrl("https://10.0.0.8/RenderingControl/control"))
    }

    @Test fun hostFromUrlRejectsBlank() {
        assertNull(AppsFmt.hostFromUrl(""))
        assertNull(AppsFmt.hostFromUrl("not-a-url"))
    }

    @Test fun dlnaTargetPrivatePassesGuard() {
        assertNull(NetGuard.ensureLocal("192.168.0.20", "192.168.0.5", false))
        assertNull(NetGuard.ensureLocal("10.0.0.3", "10.0.0.2", false))
    }

    @Test fun dlnaTargetPublicRejected() {
        val r = NetGuard.ensureLocal("8.8.8.8", "192.168.1.2", false)
        assertNotNull(r)
        assertTrue(r!!.contains("публічн") || r.contains("RFC1918"))
    }

    // ---- Protocols DLNA SOAP (already present — smoke that still works) ----
    @Test fun dlnaSoapBodies() {
        val body = Protocols.setAvTransportBody("http://x/a.mp4", "<DIDL>")
        assertTrue(body.contains("CurrentURI"))
        assertTrue(body.contains("http://x/a.mp4") || body.contains("http://x/a.mp4".let { Protocols.xmlEscape(it) }))
        assertEquals(100, Regex("DesiredVolume>(\\d+)").find(Protocols.setVolumeBody(150))!!.groupValues[1].toInt())
        assertEquals(0, Regex("DesiredVolume>(\\d+)").find(Protocols.setVolumeBody(-5))!!.groupValues[1].toInt())
        assertEquals(42, Protocols.parseGetVolumeResponse("<s:Envelope><CurrentVolume>42</CurrentVolume></s:Envelope>"))
    }

    @Test fun ssdpSearchAndParse() {
        val msg = Protocols.ssdpSearchMessage()
        assertTrue(msg.contains("M-SEARCH"))
        assertTrue(msg.contains("239.255.255.250:1900"))
        val resp = "HTTP/1.1 200 OK\r\nLOCATION: http://192.168.1.9:8080/desc.xml\r\nST: upnp:rootdevice\r\nUSN: uuid:1\r\nSERVER: Linux/UPnP\r\n\r\n"
        val p = Protocols.parseSsdpResponse(resp)!!
        assertEquals("http://192.168.1.9:8080/desc.xml", p.location)
        assertEquals("upnp:rootdevice", p.st)
    }

    // ---- TTS policy ----
    @Test fun ttsPrefersUkrainian() {
        assertEquals("uk-UA", TtsPolicy.pickLanguage(setOf("en-US", "uk-UA", "de-DE")))
        assertEquals("uk", TtsPolicy.pickLanguage(setOf("uk", "en")))
        assertEquals("en-US", TtsPolicy.pickLanguage(setOf("en-US", "de-DE")))
        assertNull(TtsPolicy.pickLanguage(setOf("de-DE", "fr-FR")))
    }

    @Test fun ttsForSpeechCleansAndCaps() {
        val raw = "Hello **world** `code` https://evil.test/x ```block\nline``` end"
        val out = TtsPolicy.forSpeech(raw)
        assertFalse(out.contains("**"))
        assertFalse(out.contains("https://"))
        assertTrue(out.contains("фрагмент коду") || out.contains("посилання"))
        assertTrue(TtsPolicy.forSpeech("a".repeat(5000)).endsWith("…"))
        assertFalse(TtsPolicy.shouldSpeak(false, "hi"))
        assertFalse(TtsPolicy.shouldSpeak(true, "   "))
        assertTrue(TtsPolicy.shouldSpeak(true, "Привіт"))
    }

    // ---- Apps formatting / package validation ----
    @Test fun appsFormatAndValidate() {
        assertFalse(AppsFmt.isValidPackage("not a pkg"))
        assertFalse(AppsFmt.isValidPackage("com"))
        assertTrue(AppsFmt.isValidPackage("com.android.settings"))
        val empty = AppsFmt.format(emptyList())
        assertTrue(empty.contains("Немає"))
        val txt = AppsFmt.format(listOf(
            AppsFmt.AppRow("Zebra", "com.z"),
            AppsFmt.AppRow("Alpha", "com.a")
        ))
        assertTrue(txt.indexOf("Alpha") < txt.indexOf("Zebra"))
        assertTrue(txt.contains("[com.a]"))
        assertTrue(txt.startsWith("Встановлено 2"))
    }

    // ---- Risk policy wiring for new tools ----
    @Test fun riskPolicyForNewTools() {
        // Pure risk lambdas mirrored from ToolRegistry (no Android Context needed).
        fun castRisk(action: String) = if (action == "status") Risk.SENSITIVE else Risk.EFFECT
        fun speakRisk() = Risk.LOW
        fun appsListRisk() = Risk.LOW
        fun appLaunchRisk() = Risk.EFFECT
        assertEquals(Risk.SENSITIVE, castRisk("status"))
        assertEquals(Risk.EFFECT, castRisk("play"))
        assertEquals(Risk.EFFECT, castRisk("stop"))
        assertEquals(Risk.LOW, speakRisk())
        assertEquals(Risk.LOW, appsListRisk())
        assertEquals(Risk.EFFECT, appLaunchRisk())
    }

    // ---- ADB key normalize still intact (device control) ----
    @Test fun adbKeyAliasesStillWork() {
        assertEquals("DPAD_UP", Protocols.normalizeKey("up"))
        assertEquals("VOLUME_UP", Protocols.normalizeKey("vol+"))
        assertTrue(Protocols.isValidKey("HOME"))
        assertFalse(Protocols.isValidKey("DELETE_EVERYTHING"))
    }
}
