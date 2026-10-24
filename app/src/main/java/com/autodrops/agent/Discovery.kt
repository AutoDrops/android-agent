package com.autodrops.agent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.*
import java.util.concurrent.TimeUnit

/** SSDP (UPnP) discovery and DLNA AVTransport/RenderingControl SOAP control. */
object Upnp {
    private val http = OkHttpClient.Builder().connectTimeout(4, TimeUnit.SECONDS).readTimeout(6, TimeUnit.SECONDS).build()

    suspend fun discover(timeoutMs: Int = 3000): JSONArray = withContext(Dispatchers.IO) {
        val found = LinkedHashMap<String, JSONObject>()
        val msg = ("M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\n" +
            "MX: 2\r\nST: ssdp:all\r\n\r\n").toByteArray()
        runCatching {
            MulticastSocket().use { sock ->
                sock.soTimeout = 800
                val addr = InetAddress.getByName("239.255.255.250")
                sock.send(DatagramPacket(msg, msg.size, addr, 1900))
                val buf = ByteArray(2048)
                val end = System.currentTimeMillis() + timeoutMs
                while (System.currentTimeMillis() < end) {
                    val pkt = DatagramPacket(buf, buf.size)
                    try { sock.receive(pkt) } catch (e: SocketTimeoutException) {
                        runCatching { sock.send(DatagramPacket(msg, msg.size, addr, 1900)) }; continue
                    }
                    val resp = String(pkt.data, 0, pkt.length)
                    val location = Regex("(?im)^LOCATION:\\s*(.+)$").find(resp)?.groupValues?.get(1)?.trim() ?: continue
                    val st = Regex("(?im)^ST:\\s*(.+)$").find(resp)?.groupValues?.get(1)?.trim() ?: ""
                    found.getOrPut(location) {
                        JSONObject().put("location", location).put("st", st).put("ip", pkt.address.hostAddress)
                    }
                }
            }
        }
        // enrich with device description (name + control URLs)
        found.values.forEach { d ->
            runCatching {
                val xml = http.newCall(Request.Builder().url(d.getString("location")).build()).execute().use { it.body?.string() ?: "" }
                Regex("<friendlyName>(.*?)</friendlyName>").find(xml)?.let { d.put("name", it.groupValues[1]) }
                Regex("<modelName>(.*?)</modelName>").find(xml)?.let { d.put("model", it.groupValues[1]) }
                val base = URL(d.getString("location"))
                d.put("services", servicesOf(xml, base))
            }
        }
        JSONArray(found.values.toList())
    }

    private fun abs(base: URL, path: String): String =
        if (path.startsWith("http")) path else URL(base.protocol, base.host, base.port, path).toString()

    private fun servicesOf(xml: String, base: URL): JSONObject {
        val o = JSONObject()
        Regex("<service>(.*?)</service>", RegexOption.DOT_MATCHES_ALL).findAll(xml).forEach { m ->
            val s = m.groupValues[1]
            val type = Regex("<serviceType>(.*?)</serviceType>").find(s)?.groupValues?.get(1) ?: ""
            val ctrl = Regex("<controlURL>(.*?)</controlURL>").find(s)?.groupValues?.get(1) ?: ""
            if (type.contains("AVTransport")) o.put("avtransport", abs(base, ctrl))
            if (type.contains("RenderingControl")) o.put("rendering", abs(base, ctrl))
        }
        return o
    }

    private fun soap(controlUrl: String, service: String, action: String, body: String): String {
        val envelope = """<?xml version="1.0"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body><u:$action xmlns:u="urn:schemas-upnp-org:service:$service:1">$body</u:$action></s:Body></s:Envelope>"""
        val req = Request.Builder().url(controlUrl)
            .header("SOAPAction", "\"urn:schemas-upnp-org:service:$service:1#$action\"")
            .post(envelope.toRequestBody("text/xml; charset=\"utf-8\"".toMediaType())).build()
        return http.newCall(req).execute().use {
            val t = it.body?.string() ?: ""
            if (!it.isSuccessful) "SOAP ${it.code}: ${t.take(300)}" else t.take(600)
        }
    }

    fun play(ctrl: String, uri: String): String {
        val meta = ""
        soap(ctrl, "AVTransport", "SetAVTransportURI",
            "<InstanceID>0</InstanceID><CurrentURI>$uri</CurrentURI><CurrentURIMetaData>$meta</CurrentURIMetaData>")
        return soap(ctrl, "AVTransport", "Play", "<InstanceID>0</InstanceID><Speed>1</Speed>")
    }
    fun transport(ctrl: String, action: String): String =
        soap(ctrl, "AVTransport", action, "<InstanceID>0</InstanceID>")
    fun setVolume(ctrl: String, vol: Int): String =
        soap(ctrl, "RenderingControl", "SetVolume",
            "<InstanceID>0</InstanceID><Channel>Master</Channel><DesiredVolume>${vol.coerceIn(0,100)}</DesiredVolume>")
    fun getVolume(ctrl: String): String =
        soap(ctrl, "RenderingControl", "GetVolume", "<InstanceID>0</InstanceID><Channel>Master</Channel>")
}

/** LAN helpers: subnet port scan, ping, DNS. */
object Net {
    fun localIpv4(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }
            .firstOrNull { !it.isLoopbackAddress && it is Inet4Address }?.hostAddress
    }.getOrNull()

    suspend fun portScan(ports: List<Int>, timeoutMs: Int = 300): JSONArray = withContext(Dispatchers.IO) {
        val ip = localIpv4() ?: return@withContext JSONArray().put("no IPv4 address")
        val prefix = ip.substringBeforeLast('.')
        val results = JSONArray()
        (1..254).map { host ->
            val target = "$prefix.$host"
            Thread {
                val open = ports.filter { port ->
                    runCatching { Socket().use { it.connect(InetSocketAddress(target, port), timeoutMs); true }.let { true } }
                        .getOrDefault(false)
                }
                if (open.isNotEmpty()) synchronized(results) {
                    results.put(JSONObject().put("host", target).put("open_ports", JSONArray(open)))
                }
            }
        }.chunked(32).forEach { batch -> batch.forEach { it.start() }; batch.forEach { it.join((timeoutMs * 3).toLong()) } }
        results
    }

    suspend fun ping(host: String): String = withContext(Dispatchers.IO) {
        val a = runCatching { InetAddress.getByName(host) }.getOrElse { return@withContext "resolve failed: ${it.message}" }
        val t0 = System.nanoTime()
        val ok = runCatching { a.isReachable(2000) }.getOrDefault(false)
        if (ok) "reachable in ${(System.nanoTime() - t0) / 1_000_000}ms (${a.hostAddress})"
        else {
            val tcp = runCatching { Socket().use { it.connect(InetSocketAddress(a, 80), 2000); true } }.getOrDefault(false)
            if (tcp) "reachable via TCP:80 (${a.hostAddress})" else "unreachable (${a.hostAddress})"
        }
    }

    suspend fun dns(host: String): String = withContext(Dispatchers.IO) {
        runCatching { InetAddress.getAllByName(host).joinToString(", ") { it.hostAddress } }
            .getOrElse { "DNS failed: ${it.message}" }
    }
}
