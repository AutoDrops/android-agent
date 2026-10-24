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

/** SSDP (UPnP) discovery and DLNA AVTransport/RenderingControl SOAP control. Parsing lives in Protocols. */
object Upnp {
    private val http = OkHttpClient.Builder().connectTimeout(4, TimeUnit.SECONDS).readTimeout(6, TimeUnit.SECONDS).build()

    suspend fun discover(timeoutMs: Int = 3000): JSONArray = withContext(Dispatchers.IO) {
        val found = LinkedHashMap<String, JSONObject>()
        val msg = Protocols.ssdpSearchMessage().toByteArray()
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
                    val parsed = Protocols.parseSsdpResponse(String(pkt.data, 0, pkt.length)) ?: continue
                    val loc = parsed.location ?: continue
                    found.getOrPut(loc) {
                        JSONObject().put("location", loc).put("st", parsed.st ?: "").put("ip", pkt.address.hostAddress)
                    }
                }
            }
        }
        found.values.forEach { d ->
            runCatching {
                val xml = http.newCall(Request.Builder().url(d.getString("location")).build()).execute().use { it.body?.string() ?: "" }
                val dev = Protocols.parseDeviceDescription(xml)
                dev.friendlyName?.let { d.put("name", it) }
                dev.modelName?.let { d.put("model", it) }
                val svc = JSONObject()
                Protocols.resolveUrl(d.getString("location"), dev.avTransportPath)?.let { svc.put("avtransport", it) }
                Protocols.resolveUrl(d.getString("location"), dev.renderingPath)?.let { svc.put("rendering", it) }
                d.put("services", svc)
            }
        }
        JSONArray(found.values.toList())
    }

    private fun soap(controlUrl: String, service: String, action: String, body: String): String {
        val req = Request.Builder().url(controlUrl).header("SOAPAction", Protocols.soapAction(service, action))
            .post(Protocols.soapEnvelope(service, action, body).toRequestBody("text/xml; charset=\"utf-8\"".toMediaType())).build()
        return http.newCall(req).execute().use {
            val t = it.body?.string() ?: ""
            if (!it.isSuccessful) "SOAP ${it.code}: ${t.take(300)}" else t.take(600)
        }
    }

    fun play(ctrl: String, uri: String): String {
        soap(ctrl, "AVTransport", "SetAVTransportURI", Protocols.setAvTransportBody(uri))
        return soap(ctrl, "AVTransport", "Play", Protocols.playBody())
    }
    fun transport(ctrl: String, action: String): String = soap(ctrl, "AVTransport", action, Protocols.instanceBody())
    fun setVolume(ctrl: String, vol: Int): String = soap(ctrl, "RenderingControl", "SetVolume", Protocols.setVolumeBody(vol))
    fun getVolume(ctrl: String): String {
        val resp = soap(ctrl, "RenderingControl", "GetVolume", Protocols.getVolumeBody())
        return Protocols.parseGetVolumeResponse(resp)?.let { "volume=$it" } ?: resp
    }
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
                val open = ports.filter { port -> runCatching { Socket().use { it.connect(InetSocketAddress(target, port), timeoutMs); true } }.getOrDefault(false) }
                if (open.isNotEmpty()) synchronized(results) { results.put(JSONObject().put("host", target).put("open_ports", JSONArray(open))) }
            }
        }.chunked(32).forEach { batch -> batch.forEach { it.start() }; batch.forEach { it.join((timeoutMs * 3).toLong()) } }
        results
    }

    suspend fun ping(host: String): String = withContext(Dispatchers.IO) {
        val a = runCatching { InetAddress.getByName(host) }.getOrElse { return@withContext "resolve failed: ${it.message}" }
        val t0 = System.nanoTime()
        if (runCatching { a.isReachable(2000) }.getOrDefault(false)) "reachable in ${(System.nanoTime() - t0) / 1_000_000}ms (${a.hostAddress})"
        else if (runCatching { Socket().use { it.connect(InetSocketAddress(a, 80), 2000); true } }.getOrDefault(false)) "reachable via TCP:80 (${a.hostAddress})"
        else "unreachable (${a.hostAddress})"
    }

    suspend fun dns(host: String): String = withContext(Dispatchers.IO) {
        runCatching { InetAddress.getAllByName(host).joinToString(", ") { it.hostAddress } }.getOrElse { "DNS failed: ${it.message}" }
    }
}
