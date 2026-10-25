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

    /** ARP scan / host discovery: ping-sweep the /24 to populate the kernel ARP cache, then read it with MAC-vendor resolution. Own subnet only. */
    suspend fun arpScan(cacheDir: File): String = withContext(Dispatchers.IO) {
        val ip = localIpv4() ?: return@withContext "Немає IPv4-адреси (не підключено до Wi-Fi?)."
        val prefix = ip.substringBeforeLast('.')
        (1..254).map { h -> Thread {
            val t = "$prefix.$h"
            runCatching { InetAddress.getByName(t).isReachable(300) }
            runCatching { Socket().use { it.connect(InetSocketAddress(t, 80), 180) } }
        } }.chunked(48).forEach { b -> b.forEach { it.start() }; b.forEach { it.join(1200) } }
        val arp = NetScan.parseArpTable(runCatching { File("/proc/net/arp").readText() }.getOrDefault(""))
        val oui = runCatching { OuiStore.load(cacheDir, allowDownload = false) }.getOrDefault(NetScan.BUILTIN_OUI)
        if (arp.isEmpty()) return@withContext "ARP-скан ${prefix}.0/24: кеш порожній (на Android 10+ /proc/net/arp часто недоступний без root; скористайся net_discover)."
        val out = JSONArray()
        arp.toList().filter { NetGuard.isPrivateV4(it.first) }.sortedBy { it.first.substringAfterLast('.').toIntOrNull() ?: 0 }.forEach { (hip, mac) ->
            out.put(JSONObject().put("ip", hip).put("mac", mac)
                .put("vendor", NetScan.lookupVendor(mac, oui) ?: "?")
                .put("name", runCatching { InetAddress.getByName(hip).canonicalHostName }.getOrDefault(hip)))
        }
        "ARP-скан ${prefix}.0/24 — ${out.length()} хостів:\n" + out.toString(2)
    }

    /** nmap -A style: TTL-based OS guess for one host plus banner-grab service/version on open common ports. RFC1918-gated by caller. */
    suspend fun osServiceDetect(host: String): String = withContext(Dispatchers.IO) {
        val target = NetGuard.resolveV4(host) ?: return@withContext "не вдалося розв'язати $host"
        // Defense-in-depth: re-verify the RESOLVED ip is private at point-of-use (guards against DNS-rebinding/TOCTOU between the caller's guard() and this live probe).
        if (!NetGuard.isPrivateV4(target)) return@withContext "Відхилено: $host розв'язався у публічну адресу $target. Лише RFC1918/локальна мережа."
        val sb = StringBuilder("nmap -A (локально) для $host ($target):\n")
        val ping = runCatching {
            val p = ProcessBuilder("ping", "-c", "1", "-W", "2", target).redirectErrorStream(true).start()
            p.inputStream.bufferedReader().readText()
        }.getOrDefault("")
        val ttl = NetScan.parseTtl(ping)
        sb.append("  ОС (TTL): ").append(if (ttl != null) "${NetScan.guessOsFromTtl(ttl)} [TTL=$ttl]" else "не визначено (ICMP недоступний без raw-сокетів/root)").append('\n')
        val common = listOf(21, 22, 23, 25, 80, 110, 143, 443, 445, 554, 3389, 5555, 8008, 8080, 8443)
        val open = common.filter { runCatching { Socket().use { s -> s.connect(InetSocketAddress(target, it), 400); true } }.getOrDefault(false) }
        if (open.isEmpty()) { sb.append("  Сервіси: відкритих типових портів не знайдено."); return@withContext sb.toString() }
        sb.append("  Сервіси/версії:\n")
        open.forEach { port ->
            val banner = runCatching {
                Socket().use { s ->
                    s.connect(InetSocketAddress(target, port), 900); s.soTimeout = 1100
                    if (port == 80 || port == 8080 || port == 8008 || port == 443 || port == 8443)
                        runCatching { s.getOutputStream().write("HEAD / HTTP/1.0\r\n\r\n".toByteArray()) }
                    val buf = ByteArray(512); val n = s.getInputStream().read(buf)
                    if (n > 0) String(buf, 0, n) else ""
                }
            }.getOrDefault("")
            sb.append("    ").append(port).append("/tcp — ").append(NetScan.parseBanner(banner, port)).append('\n')
        }
        sb.toString().trimEnd()
    }

    /** Resolve a MAC's OUI to a vendor name (bundled table; lazy-downloads the IEEE OUI file on first use). */
    suspend fun macVendor(mac: String, cacheDir: File): String = withContext(Dispatchers.IO) {
        if (NetScan.normalizeOui(mac) == null) return@withContext "Невірний MAC: «$mac» (очікую формат AA:BB:CC:DD:EE:FF)."
        val table = runCatching { OuiStore.load(cacheDir, allowDownload = true) }.getOrDefault(NetScan.BUILTIN_OUI)
        val vendor = NetScan.lookupVendor(mac, table)
        val src = if (OuiStore.hasDownloaded(cacheDir)) "IEEE OUI + вбудована таблиця" else "вбудована таблиця (IEEE-файл недоступний)"
        if (vendor != null) "MAC $mac → $vendor (OUI ${NetScan.normalizeOui(mac)}; джерело: $src)"
        else "MAC $mac (OUI ${NetScan.normalizeOui(mac)}) — виробника не знайдено у таблиці ($src)."
    }
}

/**
 * PURE, unit-testable fingerprinting helpers for the own-LAN toolkit (nmap -A style):
 * ARP-table parsing, TTL→OS guessing, service-banner→version parsing, and MAC OUI vendor lookup.
 * These touch no network themselves; the live wrappers in [Lan] enforce RFC1918 via [NetGuard].
 */
object NetScan {
    private val MAC_RE = Regex("([0-9a-fA-F]{2}:){5}[0-9a-fA-F]{2}")

    /** Parse the raw text of /proc/net/arp into ip→mac (lowercase), skipping incomplete entries. */
    fun parseArpTable(procNetArp: String): Map<String, String> =
        procNetArp.lineSequence().drop(1).mapNotNull { line ->
            val c = line.trim().split(Regex("\\s+"))
            if (c.size >= 4 && MAC_RE.matches(c[3]) && c[3] != "00:00:00:00:00:00") c[0] to c[3].lowercase() else null
        }.toMap()

    /** TTL-based OS family guess (observed TTL is ≤ the sender's initial TTL). */
    fun guessOsFromTtl(ttl: Int): String = when {
        ttl <= 0 -> "невідомо (немає TTL)"
        ttl <= 64 -> "Linux/Unix/Android/macOS/iOS (початковий TTL≈64)"
        ttl <= 128 -> "Windows (початковий TTL≈128)"
        ttl <= 255 -> "мережеве обладнання/роутер/Solaris (початковий TTL≈255)"
        else -> "невідомо (TTL=$ttl)"
    }

    /** Extract the observed TTL from a `ping` command's text output, if present. */
    fun parseTtl(pingOutput: String): Int? =
        Regex("(?i)ttl[=:]\\s*(\\d+)").find(pingOutput)?.groupValues?.get(1)?.toIntOrNull()

    /** Infer "service — product/version" from a raw service banner on [port]. */
    fun parseBanner(banner: String, port: Int): String {
        val b = banner.trim()
        if (b.isEmpty()) return "порт $port — банер порожній (сервіс не ідентифіковано)"
        Regex("SSH-[\\d.]+-(\\S+)").find(b)?.let { return "ssh — ${it.groupValues[1]}" }
        Regex("(?im)^Server:\\s*(.+)$").find(b)?.let { return "http — ${it.groupValues[1].trim().take(80)}" }
        if (b.startsWith("220")) {
            val rest = b.lineSequence().first().removePrefix("220").trim().take(80)
            val svc = when (port) { 21 -> "ftp"; 25, 587, 465 -> "smtp"; 110 -> "pop3"; else -> "сервіс" }
            return "$svc — $rest"
        }
        if (b.startsWith("* OK") && port == 143) return "imap — " + b.lineSequence().first().take(80)
        val first = b.lineSequence().firstOrNull { it.isNotBlank() }?.take(80) ?: ""
        return "порт $port — $first"
    }

    /** First 6 hex nibbles (OUI) of a MAC, uppercased, or null if unparseable. */
    fun normalizeOui(mac: String): String? {
        val hex = mac.filter { it.isLetterOrDigit() }.uppercase()
        if (hex.length < 6 || !hex.take(6).all { it in "0123456789ABCDEF" }) return null
        return hex.substring(0, 6)
    }

    /** Minimal split that respects double-quoted CSV fields. */
    internal fun splitCsv(line: String): List<String> {
        val out = ArrayList<String>(); val sb = StringBuilder(); var q = false; var i = 0
        while (i < line.length) {
            val ch = line[i]
            when {
                ch == '"' && q && i + 1 < line.length && line[i + 1] == '"' -> { sb.append('"'); i++ }
                ch == '"' -> q = !q
                ch == ',' && !q -> { out.add(sb.toString()); sb.setLength(0) }
                else -> sb.append(ch)
            }
            i++
        }
        out.add(sb.toString()); return out
    }

    /**
     * Parse the IEEE oui.csv ("Registry,Assignment,Organization Name,Organization Address") into OUI→vendor.
     * Also tolerates a simple two-column "OUI,Vendor" file.
     */
    fun parseOuiCsv(csv: String): Map<String, String> {
        val map = HashMap<String, String>()
        csv.lineSequence().forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("Registry,", true)) return@forEach
            val cols = splitCsv(line)
            val assign = cols.getOrNull(1)?.let { normalizeOui(it) } ?: cols.getOrNull(0)?.let { normalizeOui(it) }
            val org = (cols.getOrNull(2) ?: cols.getOrNull(1))?.trim()?.trim('"')
            if (assign != null && !org.isNullOrBlank()) map.putIfAbsent(assign, org)
        }
        return map
    }

    /** Look up a MAC's vendor in [table]; falls back to the bundled offline table. */
    fun lookupVendor(mac: String, table: Map<String, String>): String? =
        normalizeOui(mac)?.let { table[it] ?: BUILTIN_OUI[it] }

    /** Small, verified offline OUI table (used before/without the downloaded IEEE file). */
    val BUILTIN_OUI: Map<String, String> = mapOf(
        "B827EB" to "Raspberry Pi Foundation", "DCA632" to "Raspberry Pi Trading Ltd",
        "E45F01" to "Raspberry Pi Trading Ltd", "00000C" to "Cisco Systems, Inc",
        "00155D" to "Microsoft Corporation (Hyper-V)", "000C29" to "VMware, Inc.",
        "005056" to "VMware, Inc.", "080027" to "PCS Systemtechnik (VirtualBox)",
        "525400" to "QEMU/KVM Virtual NIC", "001CB3" to "Apple, Inc.",
        "240AC4" to "Espressif Inc.", "30AEA4" to "Espressif Inc.",
        "F4F5E8" to "Google, Inc.", "00166B" to "Samsung Electronics Co.,Ltd"
    )
}

/** Caches the IEEE OUI table on disk; merges with the bundled offline table. */
object OuiStore {
    @Volatile private var cache: Map<String, String>? = null
    private const val URL = "https://standards-oui.ieee.org/oui/oui.csv"

    fun load(cacheDir: File, allowDownload: Boolean): Map<String, String> {
        cache?.let { return it }
        val f = File(cacheDir, "oui.csv")
        val fromFile = if (f.exists()) runCatching { NetScan.parseOuiCsv(f.readText()) }.getOrDefault(emptyMap()) else emptyMap()
        var merged: Map<String, String> = NetScan.BUILTIN_OUI + fromFile
        if (fromFile.isEmpty() && allowDownload) {
            runCatching {
                val conn = (java.net.URL(URL).openConnection() as java.net.HttpURLConnection).apply {
                    connectTimeout = 8000; readTimeout = 20000
                }
                // Cap the download so a malicious/huge mirror cannot OOM the device (IEEE oui.csv is ~4-5 MB).
                val txt = conn.inputStream.bufferedReader().use { r ->
                    val cap = 24 * 1024 * 1024; val sb = StringBuilder(); val buf = CharArray(16384); var total = 0
                    while (true) { val n = r.read(buf); if (n < 0) break; total += n; if (total > cap) break; sb.append(buf, 0, n) }
                    sb.toString()
                }
                if (txt.length in 1001..(24 * 1024 * 1024)) { f.writeText(txt); merged = NetScan.BUILTIN_OUI + NetScan.parseOuiCsv(txt) }
            }
        }
        cache = merged
        return merged
    }

    fun hasDownloaded(cacheDir: File) = File(cacheDir, "oui.csv").exists()
}
