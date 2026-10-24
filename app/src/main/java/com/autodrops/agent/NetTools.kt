package com.autodrops.agent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Own-network guard. PURE and unit-testable. Enforces that scan/capture tools only ever touch the
 * device's own local / private (RFC1918) network — public IPs are always rejected.
 */
object NetGuard {
    fun isPrivateV4(ip: String): Boolean {
        val p = ip.split(".").mapNotNull { it.toIntOrNull() }
        if (p.size != 4 || p.any { it !in 0..255 }) return false
        return p[0] == 10 ||
            (p[0] == 172 && p[1] in 16..31) ||
            (p[0] == 192 && p[1] == 168) ||
            (p[0] == 169 && p[1] == 254) ||          // link-local
            p[0] == 127                               // loopback (own device)
    }

    fun samePrefix24(a: String, b: String) = a.substringBeforeLast('.') == b.substringBeforeLast('.')

    /** Resolve a host to its IPv4 (numeric strings resolve offline). */
    fun resolveV4(host: String): String? = runCatching {
        InetAddress.getAllByName(host).firstOrNull { it is Inet4Address }?.hostAddress
    }.getOrNull()

    /**
     * Returns null if the target is an allowed local/private target, otherwise a Ukrainian refusal message.
     * requireSameSubnet additionally demands the target be on the device's current /24.
     */
    fun ensureLocal(host: String, localIp: String?, requireSameSubnet: Boolean): String? {
        val ip = resolveV4(host) ?: return "Відхилено: не вдалося розв'язати «$host»."
        if (!isPrivateV4(ip)) return "Відхилено: $host ($ip) — це публічна/зовнішня адреса. Цей інструмент працює ЛИШЕ з твоєю локальною/приватною мережею (RFC1918)."
        if (requireSameSubnet && localIp != null && !samePrefix24(ip, localIp))
            return "Відхилено: $ip не у твоїй поточній підмережі (${localIp.substringBeforeLast('.')}.0/24)."
        return null
    }
}

/** Defensive, own-network-only LAN toolkit. */
object Lan {
    fun localIpv4(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }
            .firstOrNull { !it.isLoopbackAddress && it is Inet4Address }?.hostAddress
    }.getOrNull()

    suspend fun hostDiscovery(): String = withContext(Dispatchers.IO) {
        val ip = localIpv4() ?: return@withContext "Немає IPv4-адреси (не підключено до Wi-Fi?)."
        val prefix = ip.substringBeforeLast('.')
        val alive = java.util.Collections.synchronizedList(mutableListOf<String>())
        (1..254).map { h ->
            Thread {
                val t = "$prefix.$h"
                val up = runCatching { InetAddress.getByName(t).isReachable(400) }.getOrDefault(false) ||
                    runCatching { Socket().use { it.connect(InetSocketAddress(t, 80), 250); true } }.getOrDefault(false)
                if (up) alive.add(t)
            }
        }.chunked(48).forEach { b -> b.forEach { it.start() }; b.forEach { it.join(1500) } }
        val arp = readArp()
        val out = JSONArray()
        alive.sortedBy { it.substringAfterLast('.').toInt() }.forEach { a ->
            out.put(JSONObject().put("ip", a).put("mac", arp[a] ?: "?")
                .put("name", runCatching { InetAddress.getByName(a).canonicalHostName }.getOrDefault(a)))
        }
        "Знайдено ${out.length()} пристроїв у ${prefix}.0/24:\n" + out.toString(2)
    }

    private fun readArp(): Map<String, String> = runCatching {
        File("/proc/net/arp").readLines().drop(1).mapNotNull { line ->
            val c = line.trim().split(Regex("\\s+"))
            if (c.size >= 4 && c[3] != "00:00:00:00:00:00") c[0] to c[3] else null
        }.toMap()
    }.getOrDefault(emptyMap())

    suspend fun pingSweep(): String = hostDiscovery()

    suspend fun tcpScan(host: String, ports: List<Int>): String = withContext(Dispatchers.IO) {
        val open = java.util.Collections.synchronizedList(mutableListOf<Int>())
        ports.map { p -> Thread {
            if (runCatching { Socket().use { it.connect(InetSocketAddress(host, p), 500); true } }.getOrDefault(false)) open.add(p)
        } }.also { it.forEach { t -> t.start() }; it.forEach { t -> t.join(1500) } }
        "TCP $host — відкриті порти: " + (open.sorted().joinToString(", ").ifBlank { "немає зі списку" })
    }

    suspend fun udpScan(host: String, ports: List<Int>): String = withContext(Dispatchers.IO) {
        val res = ports.map { p ->
            val state = runCatching {
                DatagramSocket().use { s ->
                    s.soTimeout = 600
                    val buf = ByteArray(1)
                    s.send(DatagramPacket(buf, buf.size, InetAddress.getByName(host), p))
                    runCatching { s.receive(DatagramPacket(ByteArray(64), 64)); "open" }.getOrDefault("open|filtered")
                }
            }.getOrDefault("error")
            "$p:$state"
        }
        "UDP $host — $res (UDP-результати орієнтовні: відсутність відповіді = open|filtered)"
    }

    suspend fun bannerGrab(host: String, port: Int): String = withContext(Dispatchers.IO) {
        runCatching {
            Socket().use { s ->
                s.connect(InetSocketAddress(host, port), 1000); s.soTimeout = 1200
                if (port == 80 || port == 8080) s.getOutputStream().write("HEAD / HTTP/1.0\r\n\r\n".toByteArray())
                val buf = ByteArray(512); val n = s.getInputStream().read(buf)
                if (n > 0) "Банер $host:$port →\n" + String(buf, 0, n).trim().take(400) else "Порт $host:$port відкритий, банера немає."
            }
        }.getOrElse { "Немає банера ($host:$port): ${Protocols.friendlyError(it.message)}" }
    }

    suspend fun openServices(host: String): String = withContext(Dispatchers.IO) {
        val common = listOf(21, 22, 23, 25, 53, 80, 139, 443, 445, 554, 1900, 3389, 5555, 8008, 8009, 8080, 8443, 9000)
        val open = common.filter { runCatching { Socket().use { s -> s.connect(InetSocketAddress(host, it), 400); true } }.getOrDefault(false) }
        if (open.isEmpty()) return@withContext "На $host не знайдено відкритих типових портів."
        val names = mapOf(21 to "ftp", 22 to "ssh", 23 to "telnet", 25 to "smtp", 53 to "dns", 80 to "http", 139 to "netbios",
            443 to "https", 445 to "smb", 554 to "rtsp", 1900 to "ssdp/upnp", 3389 to "rdp", 5555 to "adb", 8008 to "cast",
            8009 to "cast", 8080 to "http-alt", 8443 to "https-alt", 9000 to "misc")
        "Сервіси на $host:\n" + open.joinToString("\n") { "  $it/tcp — ${names[it] ?: "?"}" }
    }

    suspend fun dnsLookup(host: String): String = withContext(Dispatchers.IO) {
        runCatching { InetAddress.getAllByName(host).joinToString(", ") { it.hostAddress } }.getOrElse { "DNS помилка: ${it.message}" }
    }
    suspend fun reverseDns(ip: String): String = withContext(Dispatchers.IO) {
        runCatching { InetAddress.getByName(ip).canonicalHostName }.getOrElse { "reverse помилка: ${it.message}" }
    }
    suspend fun traceroute(host: String): String = withContext(Dispatchers.IO) {
        val target = NetGuard.resolveV4(host) ?: return@withContext "не вдалося розв'язати $host"
        val out = StringBuilder("traceroute $host ($target), max 20 хопів (TTL-проба через ping):\n")
        runCatching {
            val p = ProcessBuilder("ping", "-c", "1", "-W", "2", target).redirectErrorStream(true).start()
            out.append(p.inputStream.bufferedReader().readText().take(600))
        }.onFailure { out.append("ICMP-проба недоступна на цьому пристрої (потрібні raw-сокети/root).") }
        out.toString()
    }
    suspend fun whois(domain: String): String = withContext(Dispatchers.IO) {
        runCatching {
            Socket().use { s ->
                s.connect(InetSocketAddress("whois.iana.org", 43), 4000); s.soTimeout = 5000
                s.getOutputStream().write(("$domain\r\n").toByteArray())
                val ref = s.getInputStream().bufferedReader().readText()
                val server = Regex("(?i)whois:\\s*(\\S+)").find(ref)?.groupValues?.get(1)
                if (server == null) ref.take(1500) else Socket().use { s2 ->
                    s2.connect(InetSocketAddress(server, 43), 4000); s2.soTimeout = 5000
                    s2.getOutputStream().write(("$domain\r\n").toByteArray())
                    s2.getInputStream().bufferedReader().readText().take(2000)
                }
            }
        }.getOrElse { "whois помилка: ${Protocols.friendlyError(it.message)}" }
    }
    suspend fun sslCert(host: String, port: Int): String = withContext(Dispatchers.IO) {
        runCatching {
            (SSLSocketFactory.getDefault().createSocket() as SSLSocket).use { s ->
                s.connect(InetSocketAddress(host, port), 4000); s.startHandshake()
                val c = s.session.peerCertificates.first() as java.security.cert.X509Certificate
                "Сертифікат $host:$port\n  Subject: ${c.subjectX500Principal}\n  Issuer: ${c.issuerX500Principal}\n  Діє з: ${c.notBefore}\n  До: ${c.notAfter}\n  Serial: ${c.serialNumber}"
            }
        }.getOrElse { "TLS помилка ($host:$port): ${Protocols.friendlyError(it.message)}" }
    }
}
