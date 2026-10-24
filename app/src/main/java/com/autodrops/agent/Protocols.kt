package com.autodrops.agent

import org.json.JSONObject

/** Pure, unit-testable protocol logic: SSDP, UPnP device XML, DLNA SOAP, ADB, key & accessibility mapping. */
object Protocols {

    // ---------- SSDP ----------
    data class SsdpResponse(val location: String?, val st: String?, val usn: String?, val server: String?)

    fun ssdpSearchMessage(st: String = "ssdp:all", mx: Int = 2): String =
        "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: $mx\r\nST: $st\r\n\r\n"

    private fun header(resp: String, name: String): String? =
        Regex("(?im)^$name:\\s*(.+?)\\s*$").find(resp)?.groupValues?.get(1)?.trim()

    fun parseSsdpResponse(resp: String): SsdpResponse? {
        val firstLine = resp.lineSequence().firstOrNull()?.trim().orEmpty()
        if (!firstLine.startsWith("HTTP/1.1") && !firstLine.startsWith("NOTIFY")) return null
        return SsdpResponse(header(resp, "LOCATION"), header(resp, "ST") ?: header(resp, "NT"),
            header(resp, "USN"), header(resp, "SERVER"))
    }

    // ---------- UPnP device description ----------
    data class UpnpDevice(val friendlyName: String?, val modelName: String?,
                          val avTransportPath: String?, val renderingPath: String?)

    fun parseDeviceDescription(xml: String): UpnpDevice {
        fun tag(t: String) = Regex("<$t>(.*?)</$t>", RegexOption.DOT_MATCHES_ALL).find(xml)?.groupValues?.get(1)?.trim()
        var av: String? = null; var rc: String? = null
        Regex("<service>(.*?)</service>", RegexOption.DOT_MATCHES_ALL).findAll(xml).forEach { m ->
            val s = m.groupValues[1]
            val type = Regex("<serviceType>(.*?)</serviceType>").find(s)?.groupValues?.get(1) ?: ""
            val ctrl = Regex("<controlURL>(.*?)</controlURL>").find(s)?.groupValues?.get(1)?.trim()
            if (type.contains("AVTransport")) av = ctrl
            if (type.contains("RenderingControl")) rc = ctrl
        }
        return UpnpDevice(tag("friendlyName"), tag("modelName"), av, rc)
    }

    /** Resolve a possibly-relative control URL against the device description base URL. */
    fun resolveUrl(baseLocation: String, path: String?): String? {
        if (path.isNullOrBlank()) return null
        if (path.startsWith("http")) return path
        return runCatching {
            val b = java.net.URL(baseLocation)
            java.net.URL(b.protocol, b.host, b.port, if (path.startsWith("/")) path else "/$path").toString()
        }.getOrNull()
    }

    // ---------- DLNA SOAP ----------
    fun soapEnvelope(service: String, action: String, body: String): String =
        """<?xml version="1.0"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body><u:$action xmlns:u="urn:schemas-upnp-org:service:$service:1">$body</u:$action></s:Body></s:Envelope>"""

    fun soapAction(service: String, action: String) = "\"urn:schemas-upnp-org:service:$service:1#$action\""

    fun setAvTransportBody(uri: String, meta: String = "") =
        "<InstanceID>0</InstanceID><CurrentURI>${xmlEscape(uri)}</CurrentURI><CurrentURIMetaData>${xmlEscape(meta)}</CurrentURIMetaData>"
    fun playBody() = "<InstanceID>0</InstanceID><Speed>1</Speed>"
    fun instanceBody() = "<InstanceID>0</InstanceID>"
    fun setVolumeBody(vol: Int) =
        "<InstanceID>0</InstanceID><Channel>Master</Channel><DesiredVolume>${vol.coerceIn(0, 100)}</DesiredVolume>"
    fun getVolumeBody() = "<InstanceID>0</InstanceID><Channel>Master</Channel>"

    fun parseGetVolumeResponse(xml: String): Int? =
        Regex("<CurrentVolume>(\\d+)</CurrentVolume>").find(xml)?.groupValues?.get(1)?.toIntOrNull()

    fun xmlEscape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&apos;")

    // ---------- ADB ----------
    /** ADB CNXN handshake message (A_CNXN=0x4e584e43, version 0x01000000, maxdata 256k). */
    fun adbConnectPayload(): ByteArray = "host::features=shell_v2,cmd\u0000".toByteArray(Charsets.US_ASCII)

    fun adbMessage(command: Int, arg0: Int, arg1: Int, payload: ByteArray): ByteArray {
        val checksum = payload.fold(0) { acc, b -> acc + (b.toInt() and 0xFF) }
        val buf = java.nio.ByteBuffer.allocate(24 + payload.size).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        buf.putInt(command); buf.putInt(arg0); buf.putInt(arg1); buf.putInt(payload.size)
        buf.putInt(checksum); buf.putInt(command.inv()); buf.put(payload)
        return buf.array()
    }

    const val A_CNXN = 0x4e584e43
    const val A_OPEN = 0x4e45504f
    const val A_OKAY = 0x59414b4f
    const val A_WRTE = 0x45545257

    /** ADB shell service string for a keyevent, as sent in an OPEN message. */
    fun adbShellKeyevent(key: String): String = "shell:input keyevent KEYCODE_${normalizeKey(key)}\u0000"
    fun adbShellCommand(cmd: String): String = "shell:$cmd\u0000"

    // ---------- Key / TV mapping ----------
    private val KEY_ALIASES = mapOf(
        "up" to "DPAD_UP", "down" to "DPAD_DOWN", "left" to "DPAD_LEFT", "right" to "DPAD_RIGHT",
        "ok" to "DPAD_CENTER", "select" to "DPAD_CENTER", "enter" to "DPAD_CENTER",
        "vol+" to "VOLUME_UP", "volup" to "VOLUME_UP", "vol-" to "VOLUME_DOWN", "voldown" to "VOLUME_DOWN",
        "mute" to "VOLUME_MUTE", "play" to "MEDIA_PLAY_PAUSE", "pause" to "MEDIA_PLAY_PAUSE"
    )
    private val VALID_KEYS = setOf(
        "DPAD_UP", "DPAD_DOWN", "DPAD_LEFT", "DPAD_RIGHT", "DPAD_CENTER",
        "HOME", "BACK", "MENU", "VOLUME_UP", "VOLUME_DOWN", "VOLUME_MUTE",
        "MEDIA_PLAY_PAUSE", "MEDIA_NEXT", "MEDIA_PREVIOUS", "POWER", "WAKEUP"
    )
    fun normalizeKey(raw: String): String {
        val k = raw.trim().uppercase().removePrefix("KEYCODE_")
        val alias = KEY_ALIASES[raw.trim().lowercase()]
        return alias ?: k
    }
    fun isValidKey(raw: String): Boolean = normalizeKey(raw) in VALID_KEYS

    // ---------- Accessibility action mapping ----------
    const val ACTION_CLICK = 0x00000010        // AccessibilityNodeInfo.ACTION_CLICK
    const val ACTION_SET_TEXT = 0x00200000     // ACTION_SET_TEXT
    const val GLOBAL_BACK = 1
    const val GLOBAL_HOME = 2
    const val GLOBAL_RECENTS = 3
    const val GLOBAL_NOTIFICATIONS = 4
    const val GLOBAL_QUICK_SETTINGS = 5

    fun globalActionCode(action: String): Int? = when (action.trim().lowercase()) {
        "back" -> GLOBAL_BACK; "home" -> GLOBAL_HOME; "recents", "recent" -> GLOBAL_RECENTS
        "notifications", "notification" -> GLOBAL_NOTIFICATIONS
        "quick_settings", "quicksettings" -> GLOBAL_QUICK_SETTINGS
        else -> null
    }

    val UI_ACTIONS = setOf("dump", "click", "set_text", "tap", "swipe", "global")
    fun isValidUiAction(a: String) = a in UI_ACTIONS

    /** Friendly, non-leaking error text for the UI. */
    fun friendlyError(raw: String?): String {
        val m = raw ?: "Unknown error"
        return when {
            m.contains("401") || m.contains("No auth", true) || m.contains("invalid_api_key", true) ->
                "Authentication failed — check your API key in the Models tab."
            m.contains("402") || m.contains("insufficient", true) || m.contains("credits", true) ->
                "Out of credits on this provider."
            m.contains("429") -> "Rate limited — wait a moment and try again."
            m.startsWith("HTTP 5") || m.contains("502") || m.contains("503") -> "The provider is temporarily unavailable."
            m.contains("UnknownHost", true) || m.contains("Unable to resolve", true) || m.contains("timeout", true) ->
                "Network problem — check your connection."
            m.contains("not enabled", true) || m.contains("not granted", true) -> m
            else -> "Something went wrong: ${m.take(160)}"
        }
    }
}
