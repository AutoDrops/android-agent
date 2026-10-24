package com.autodrops.agent

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.net.ServerSocket
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ProtocolsTest {

    // ---------- SSDP ----------
    @Test fun ssdpSearchMessageWellFormed() {
        val m = Protocols.ssdpSearchMessage()
        assertTrue(m.startsWith("M-SEARCH * HTTP/1.1\r\n"))
        assertTrue(m.contains("HOST: 239.255.255.250:1900"))
        assertTrue(m.contains("MAN: \"ssdp:discover\""))
        assertTrue(m.endsWith("\r\n\r\n"))
    }

    @Test fun parseSsdpResponseExtractsHeaders() {
        val resp = "HTTP/1.1 200 OK\r\nCACHE-CONTROL: max-age=1800\r\n" +
            "LOCATION: http://192.168.1.50:8200/desc.xml\r\nST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n" +
            "USN: uuid:abc::urn\r\nSERVER: Linux/3 UPnP/1.0\r\n\r\n"
        val r = Protocols.parseSsdpResponse(resp)!!
        assertEquals("http://192.168.1.50:8200/desc.xml", r.location)
        assertTrue(r.st!!.contains("MediaRenderer"))
        assertEquals("uuid:abc::urn", r.usn)
    }

    @Test fun parseSsdpNotifyAndGarbage() {
        assertNotNull(Protocols.parseSsdpResponse("NOTIFY * HTTP/1.1\r\nLOCATION: http://x/y\r\nNT: upnp:rootdevice\r\n\r\n"))
        assertNull(Protocols.parseSsdpResponse("random udp noise"))
    }


    @Test fun parseSsdpAcceptsHttp10AndMixedCase() {
        val r = Protocols.parseSsdpResponse("HTTP/1.0 200 OK\r\nlocation: http://h/d.xml\r\nSt: upnp:rootdevice\r\n\r\n")!!
        assertEquals("http://h/d.xml", r.location)
        assertEquals("upnp:rootdevice", r.st)
    }

    // ---------- UPnP device description ----------
    @Test fun parseDeviceDescriptionAndServices() {
        val xml = """
            <root><device><friendlyName>Living Room TV</friendlyName><modelName>Bravia</modelName>
            <serviceList>
              <service><serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType><controlURL>/AVTransport/ctrl</controlURL></service>
              <service><serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType><controlURL>/RC/ctrl</controlURL></service>
            </serviceList></device></root>""".trimIndent()
        val d = Protocols.parseDeviceDescription(xml)
        assertEquals("Living Room TV", d.friendlyName)
        assertEquals("Bravia", d.modelName)
        assertEquals("/AVTransport/ctrl", d.avTransportPath)
        assertEquals("/RC/ctrl", d.renderingPath)
    }

    @Test fun resolveUrlRelativeAndAbsolute() {
        assertEquals("http://192.168.1.50:8200/AVTransport/ctrl",
            Protocols.resolveUrl("http://192.168.1.50:8200/desc.xml", "/AVTransport/ctrl"))
        assertEquals("http://other/x", Protocols.resolveUrl("http://192.168.1.50:8200/desc.xml", "http://other/x"))
        assertNull(Protocols.resolveUrl("http://h/d.xml", null))
    }

    // ---------- DLNA SOAP ----------
    @Test fun soapEnvelopeAndActionHeader() {
        val env = Protocols.soapEnvelope("AVTransport", "Play", Protocols.playBody())
        assertTrue(env.contains("<u:Play xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\">"))
        assertTrue(env.contains("<Speed>1</Speed>"))
        assertEquals("\"urn:schemas-upnp-org:service:AVTransport:1#Play\"", Protocols.soapAction("AVTransport", "Play"))
    }

    @Test fun soapEscapesMediaUrl() {
        val body = Protocols.setAvTransportBody("http://h/a?x=1&y=2")
        assertTrue(body.contains("x=1&amp;y=2"))
        assertFalse(body.contains("x=1&y=2"))
    }

    @Test fun setVolumeClampsAndParses() {
        assertTrue(Protocols.setVolumeBody(150).contains("<DesiredVolume>100</DesiredVolume>"))
        assertTrue(Protocols.setVolumeBody(-5).contains("<DesiredVolume>0</DesiredVolume>"))
        assertEquals(37, Protocols.parseGetVolumeResponse("<s:Envelope><CurrentVolume>37</CurrentVolume></s:Envelope>"))
        assertNull(Protocols.parseGetVolumeResponse("no volume here"))
    }

    // ---------- DLNA against a mock HTTP server on the box ----------
    @Test fun dlnaControlAgainstMockServer() {
        val server = ServerSocket(0)
        val received = StringBuilder()
        val t = Thread {
            repeat(2) {
                server.accept().use { sock ->
                    val input = sock.getInputStream().bufferedReader()
                    val reqLines = StringBuilder()
                    var line = input.readLine()
                    var contentLength = 0
                    while (!line.isNullOrEmpty()) {
                        reqLines.appendLine(line)
                        if (line.startsWith("Content-Length", true)) contentLength = line.substringAfter(":").trim().toInt()
                        line = input.readLine()
                    }
                    val body = CharArray(contentLength); input.read(body, 0, contentLength)
                    synchronized(received) { received.append(reqLines).append(String(body)) }
                    val resp = "<s:Envelope><CurrentVolume>42</CurrentVolume></s:Envelope>"
                    sock.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Length: ${resp.length}\r\n\r\n$resp").toByteArray())
                    sock.getOutputStream().flush()
                }
            }
        }.apply { start() }
        val url = "http://127.0.0.1:${server.localPort}/ctrl"
        val playResult = Upnp.play(url, "http://media/clip.mp4")   // 2 requests? play = SetAVTransportURI + Play; but we accept 2
        t.join(3000); server.close()
        assertTrue(received.contains("SOAPAction"))
        assertTrue(received.contains("SetAVTransportURI") || received.contains("Play"))
    }

    @Test fun getVolumeParsesMockResponse() {
        val server = ServerSocket(0)
        Thread {
            server.accept().use { sock ->
                val r = sock.getInputStream().bufferedReader()
                var l = r.readLine(); var cl = 0
                while (!l.isNullOrEmpty()) { if (l.startsWith("Content-Length", true)) cl = l.substringAfter(":").trim().toInt(); l = r.readLine() }
                val b = CharArray(cl); r.read(b, 0, cl)
                val resp = "<CurrentVolume>42</CurrentVolume>"
                sock.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Length: ${resp.length}\r\n\r\n$resp").toByteArray())
            }
        }.start()
        val res = Upnp.getVolume("http://127.0.0.1:${server.localPort}/ctrl")
        server.close()
        assertEquals("volume=42", res)
    }

    // ---------- ADB protocol ----------
    @Test fun adbMessageHeaderLayout() {
        val payload = "shell:input keyevent KEYCODE_DPAD_UP\u0000".toByteArray()
        val msg = Protocols.adbMessage(Protocols.A_OPEN, 1, 0, payload)
        val buf = ByteBuffer.wrap(msg).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(Protocols.A_OPEN, buf.int)       // command
        assertEquals(1, buf.int)                       // arg0
        assertEquals(0, buf.int)                        // arg1
        assertEquals(payload.size, buf.int)            // data length
        val checksum = buf.int
        assertEquals(payload.fold(0) { a, b -> a + (b.toInt() and 0xFF) }, checksum)
        assertEquals(Protocols.A_OPEN.inv(), buf.int)  // magic
    }

    @Test fun adbShellServiceStrings() {
        assertEquals("shell:input keyevent KEYCODE_DPAD_UP\u0000", Protocols.adbShellKeyevent("up"))
        assertEquals("shell:input keyevent KEYCODE_HOME\u0000", Protocols.adbShellKeyevent("KEYCODE_HOME"))
        assertEquals("shell:ls -la\u0000", Protocols.adbShellCommand("ls -la"))
        assertEquals(Protocols.A_CNXN, 0x4e584e43)
    }

    @Test fun adbHandshakeAgainstMockServer() {
        // Mock ADB peer: read a CNXN message and reply with a CNXN back.
        val server = ServerSocket(0)
        val gotCommand = IntArray(1)
        val t = Thread {
            server.accept().use { sock ->
                val ins = sock.getInputStream()
                val header = ByteArray(24); var n = 0
                while (n < 24) { val r = ins.read(header, n, 24 - n); if (r < 0) break; n += r }
                val b = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
                gotCommand[0] = b.int
                val len = run { b.int; b.int; b.int }
                if (len > 0) { val p = ByteArray(len); var m = 0; while (m < len) { val r = ins.read(p, m, len - m); if (r < 0) break; m += r } }
                val reply = Protocols.adbMessage(Protocols.A_CNXN, 0x01000000, 256 * 1024, "device::\u0000".toByteArray())
                sock.getOutputStream().write(reply); sock.getOutputStream().flush()
            }
        }.apply { start() }
        java.net.Socket("127.0.0.1", server.localPort).use { c ->
            c.getOutputStream().write(Protocols.adbMessage(Protocols.A_CNXN, 0x01000000, 256 * 1024, Protocols.adbConnectPayload()))
            val header = ByteArray(24); var n = 0
            while (n < 24) { val r = c.getInputStream().read(header, n, 24 - n); if (r < 0) break; n += r }
            val cmd = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).int
            assertEquals(Protocols.A_CNXN, cmd)
        }
        t.join(3000); server.close()
        assertEquals(Protocols.A_CNXN, gotCommand[0])
    }

    // ---------- Key & accessibility mapping ----------
    @Test fun keyNormalizationAndValidation() {
        assertEquals("DPAD_UP", Protocols.normalizeKey("up"))
        assertEquals("DPAD_CENTER", Protocols.normalizeKey("ok"))
        assertEquals("VOLUME_UP", Protocols.normalizeKey("vol+"))
        assertEquals("HOME", Protocols.normalizeKey("KEYCODE_HOME"))
        assertTrue(Protocols.isValidKey("down"))
        assertTrue(Protocols.isValidKey("DPAD_LEFT"))
        assertFalse(Protocols.isValidKey("rm -rf /"))
        assertFalse(Protocols.isValidKey("NONSENSE"))
    }

    @Test fun accessibilityActionMapping() {
        assertEquals(Protocols.GLOBAL_BACK, Protocols.globalActionCode("back"))
        assertEquals(Protocols.GLOBAL_HOME, Protocols.globalActionCode("HOME"))
        assertEquals(Protocols.GLOBAL_RECENTS, Protocols.globalActionCode("recents"))
        assertEquals(Protocols.GLOBAL_QUICK_SETTINGS, Protocols.globalActionCode("quick_settings"))
        assertNull(Protocols.globalActionCode("launch_nukes"))
        assertTrue(Protocols.isValidUiAction("click"))
        assertTrue(Protocols.isValidUiAction("set_text"))
        assertFalse(Protocols.isValidUiAction("format_disk"))
        assertEquals(0x00000010, Protocols.ACTION_CLICK)
    }

    // ---------- friendly errors ----------
    @Test fun friendlyErrorsMapped() {
        assertTrue(Protocols.friendlyError("HTTP 401: No auth credentials").contains("Authentication"))
        assertTrue(Protocols.friendlyError("HTTP 429 Too Many").contains("Rate limited"))
        assertTrue(Protocols.friendlyError("java.net.UnknownHostException: openrouter.ai").contains("Network"))
        assertTrue(Protocols.friendlyError("402 insufficient credits").contains("credits"))
        assertTrue(Protocols.friendlyError("boom").startsWith("Something went wrong"))
    }
}
