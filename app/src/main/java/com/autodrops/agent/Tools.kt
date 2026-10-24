package com.autodrops.agent

import android.annotation.SuppressLint
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.ContactsContract
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.NetworkInterface
import kotlin.coroutines.resume

/** Builds the typed tool registry. Unknown tools / invalid args fail closed; risk drives confirmation. */
class ToolRegistry(private val ctx: Context, private val store: SecureStore) {
    private val db = AppDb.get(ctx)

    val specs: List<ToolSpec> = buildList {
        add(ToolSpec("network_info", "Current Wi-Fi connection info and local IP addresses. Optional nearby Wi-Fi scan.",
            Schema.obj("scan" to Schema.bool("Include nearby Wi-Fi scan results"), required = emptyList()),
            risk = { if (it.optBoolean("scan")) Risk.SENSITIVE else Risk.LOW }, exec = { networkInfo(it.optBoolean("scan")) }))

        add(ToolSpec("lan_scan", "TCP port scan of the current local subnet only (common ports).",
            Schema.obj("ports" to Schema.str("Comma-separated ports, default 22,80,443,554,1900,8008,8009,5555"), required = emptyList()),
            risk = { Risk.SENSITIVE }, exec = {
                val ports = it.str("ports").ifBlank { "22,80,443,554,1900,8008,8009,5555" }
                    .split(",").mapNotNull { p -> p.trim().toIntOrNull() }
                Net.portScan(ports).toString(2)
            }))

        add(ToolSpec("ping", "Ping / reachability check for a host.", Schema.obj("host" to Schema.str("Hostname or IP"), required = listOf("host")),
            risk = { Risk.LOW }, exec = { Net.ping(it.getString("host")) }))

        add(ToolSpec("dns_lookup", "Resolve a hostname to IP addresses.", Schema.obj("host" to Schema.str("Hostname"), required = listOf("host")),
            risk = { Risk.LOW }, exec = { Net.dns(it.getString("host")) }))

        add(ToolSpec("discover_devices", "Discover devices on the local network via mDNS/NSD and SSDP/UPnP (TVs, speakers, Cast, DLNA).",
            Schema.obj(required = emptyList()), risk = { Risk.SENSITIVE }, exec = { discoverDevices() }))

        add(ToolSpec("dlna_control", "Control a DLNA/UPnP renderer (TV/speaker): play a media URL, pause/stop, get/set volume. Use control URLs from discover_devices.",
            Schema.obj(
                "action" to Schema.str("Action", listOf("play", "pause", "stop", "get_volume", "set_volume")),
                "avtransport_url" to Schema.str("AVTransport control URL"),
                "rendering_url" to Schema.str("RenderingControl control URL"),
                "media_url" to Schema.str("Media URL to play"),
                "volume" to Schema.int("Volume 0-100", 0, 100), required = listOf("action")),
            risk = { Risk.EFFECT }, exec = { dlna(it) }))

        add(ToolSpec("adbtv", "ADB over network for Android TV: connect & send key events or launch. Pairing code may be required on Android 11+.",
            Schema.obj(
                "action" to Schema.str("Action", listOf("keyevent", "launch", "shell")),
                "host" to Schema.str("TV IP"), "port" to Schema.int("ADB port, default 5555"),
                "key" to Schema.str("Key e.g. DPAD_UP, DPAD_DOWN, DPAD_LEFT, DPAD_RIGHT, DPAD_CENTER, HOME, BACK, VOLUME_UP, VOLUME_DOWN"),
                "command" to Schema.str("Shell command or package to launch"), required = listOf("action", "host")),
            risk = { if (it.str("action") == "shell") Risk.ELEVATED else Risk.EFFECT }, exec = { adbTv(it) }))

        add(ToolSpec("toggle_settings", "Change or open a device setting: wifi, bluetooth, brightness, volume, dnd, flashlight.",
            Schema.obj("setting" to Schema.str("Setting", listOf("wifi", "bluetooth", "brightness", "volume", "dnd", "flashlight")),
                "value" to Schema.str("on/off, or 0-100 for brightness/volume"), required = listOf("setting")),
            risk = { Risk.EFFECT }, exec = { toggle(it.getString("setting"), it.str("value")) }))

        add(ToolSpec("file_op", "File operations inside the app workspace folder: list, read, write, delete, mkdir.",
            Schema.obj("op" to Schema.str("Operation", listOf("list", "read", "write", "delete", "mkdir")),
                "path" to Schema.str("Relative path inside workspace"), "content" to Schema.str("Content for write"),
                required = listOf("op", "path")),
            risk = { if (it.str("op") in setOf("write", "delete")) Risk.EFFECT else Risk.LOW }, exec = { fileOp(it) }))

        add(ToolSpec("shell", "Run a shell command in the workspace (bash via sh -c). Backend set in settings (sandbox/root).",
            Schema.obj("command" to Schema.str("Command line"), "timeout_sec" to Schema.int("Timeout, default 20", 1, 180),
                required = listOf("command")),
            risk = { if (store.shellMode == "root") Risk.ELEVATED else Risk.HIGH },
            preview = { "backend=${store.shellMode} cwd=workspace\n$ ${sanitizeForDisplay(it.str("command"))}" },
            exec = { Exec.run(ctx, it.getString("command"), it.intOr("timeout_sec", 20).coerceIn(1, 180), store.shellMode) }))

        add(ToolSpec("run_script", "Run a Python/Node/bash script via Termux (RUN_COMMAND intent). Requires Termux + termux-api installed and RUN_COMMAND allowed.",
            Schema.obj("interpreter" to Schema.str("Interpreter", listOf("bash", "python", "node")),
                "code" to Schema.str("Script source"), required = listOf("interpreter", "code")),
            risk = { Risk.HIGH }, preview = { "Termux ${it.str("interpreter")}:\n${sanitizeForDisplay(it.str("code"))}" },
            exec = { termuxRun(it.getString("interpreter"), it.getString("code")) }))

        add(ToolSpec("list_apps", "List installed launchable apps.", Schema.obj("filter" to Schema.str("Optional name filter"), required = emptyList()),
            risk = { Risk.LOW }, exec = { listApps(it.str("filter")) }))

        add(ToolSpec("launch_app", "Launch an app by package name.", Schema.obj("package" to Schema.str("Package name"), required = listOf("package")),
            risk = { Risk.EFFECT }, exec = { launchApp(it.getString("package")) }))

        add(ToolSpec("notifications", "Read active notifications (requires Notification Listener access).", Schema.obj(required = emptyList()),
            risk = { Risk.SENSITIVE }, exec = {
                if (AgentNotificationListener.instance == null) "Notification Listener not enabled. Grant access in settings."
                else AgentNotificationListener.active().toString(2)
            }))

        add(ToolSpec("clipboard", "Read or write the clipboard (foreground only).",
            Schema.obj("op" to Schema.str("get or set", listOf("get", "set")), "text" to Schema.str("Text for set"), required = listOf("op")),
            risk = { if (it.str("op") == "set") Risk.EFFECT else Risk.SENSITIVE }, exec = { clipboard(it) }))

        add(ToolSpec("contacts", "Search contacts by name (requires READ_CONTACTS).", Schema.obj("query" to Schema.str("Name query"), required = emptyList()),
            risk = { Risk.SENSITIVE }, exec = { contacts(it.str("query")) }))

        add(ToolSpec("send_sms", "Open the SMS app pre-filled (ACTION_SENDTO; user presses send).",
            Schema.obj("number" to Schema.str("Phone number"), "message" to Schema.str("Message body"), required = listOf("number", "message")),
            risk = { Risk.EFFECT }, exec = { sendSms(it.getString("number"), it.getString("message")) }))

        add(ToolSpec("ui_action", "Accessibility UI control: dump the screen, click/type by text or id, tap/swipe coordinates, or global back/home/recents.",
            Schema.obj("action" to Schema.str("Action", listOf("dump", "click", "set_text", "tap", "swipe", "global")),
                "text" to Schema.str("Text to find"), "id" to Schema.str("View id"), "value" to Schema.str("Value to type / global action"),
                "x" to Schema.int("x"), "y" to Schema.int("y"), "x2" to Schema.int("x2"), "y2" to Schema.int("y2"), required = listOf("action")),
            risk = { if (it.str("action") == "dump") Risk.SENSITIVE else Risk.EFFECT }, exec = { uiAction(it) }))

        add(ToolSpec("security_report", "Scan the local subnet and produce a network security posture report (open risky ports per host).",
            Schema.obj(required = emptyList()), risk = { Risk.SENSITIVE }, exec = { securityReport() }))

        add(ToolSpec("app_permissions", "Inspect an installed app: its declared permissions and which are dangerous.",
            Schema.obj("package" to Schema.str("Package name"), required = listOf("package")),
            risk = { Risk.LOW }, exec = { appPermissions(it.getString("package")) }))

        add(ToolSpec("web_fetch", "Fetch a public web page and return its readable text (headless, on-device).",
            Schema.obj("url" to Schema.str("http(s) URL"), required = listOf("url")),
            risk = { Risk.SENSITIVE }, exec = { webFetch(it.getString("url")) }))

        // ---- Own-LAN defensive toolkit (RFC1918 / current-subnet only; public IPs refused) ----
        val defPorts = "21,22,23,25,53,80,139,443,445,554,1900,3389,5555,8008,8009,8080,8443"
        fun guard(h: String, sub: Boolean) = NetGuard.ensureLocal(h, Lan.localIpv4(), sub)
        fun ports(a: JSONObject) = a.str("ports").ifBlank { defPorts }.split(",").mapNotNull { it.trim().toIntOrNull() }.take(64)

        add(ToolSpec("net_discover", "Discover live hosts on YOUR current local subnet (ping sweep + ARP/MAC + names). Own network only.",
            Schema.obj(required = emptyList()), risk = { Risk.EFFECT }, exec = { Lan.hostDiscovery() }))
        add(ToolSpec("ping_sweep", "Ping sweep of YOUR current local subnet to list reachable hosts.",
            Schema.obj(required = emptyList()), risk = { Risk.EFFECT }, exec = { Lan.pingSweep() }))
        add(ToolSpec("tcp_scan", "TCP port scan of ONE host on your local/private subnet. Public IPs are refused.",
            Schema.obj("host" to Schema.str("IP/host on your LAN"), "ports" to Schema.str("Comma-separated ports"), required = listOf("host")),
            risk = { Risk.EFFECT }, exec = { guard(it.getString("host"), true) ?: Lan.tcpScan(it.getString("host"), ports(it)) }))
        add(ToolSpec("udp_scan", "UDP port probe of ONE host on your local/private subnet. Public IPs are refused.",
            Schema.obj("host" to Schema.str("IP/host on your LAN"), "ports" to Schema.str("Comma-separated ports"), required = listOf("host")),
            risk = { Risk.EFFECT }, exec = { guard(it.getString("host"), true) ?: Lan.udpScan(it.getString("host"), ports(it)) }))
        add(ToolSpec("banner_grab", "Read the service banner on ONE open port of a local/private host (read-only, no payloads).",
            Schema.obj("host" to Schema.str("IP/host on your LAN"), "port" to Schema.int("Port", 1, 65535), required = listOf("host", "port")),
            risk = { Risk.SENSITIVE }, exec = { guard(it.getString("host"), true) ?: Lan.bannerGrab(it.getString("host"), it.getInt("port")) }))
        add(ToolSpec("open_services", "Summarize open common services on ONE local/private host.",
            Schema.obj("host" to Schema.str("IP/host on your LAN"), required = listOf("host")),
            risk = { Risk.EFFECT }, exec = { guard(it.getString("host"), true) ?: Lan.openServices(it.getString("host")) }))
        add(ToolSpec("dns_lookup", "Resolve a hostname to IP address(es).",
            Schema.obj("host" to Schema.str("hostname"), required = listOf("host")),
            risk = { Risk.LOW }, exec = { Lan.dnsLookup(it.getString("host")) }))
        add(ToolSpec("reverse_dns", "Reverse-resolve an IP to a hostname.",
            Schema.obj("ip" to Schema.str("IP address"), required = listOf("ip")),
            risk = { Risk.LOW }, exec = { Lan.reverseDns(it.getString("ip")) }))
        add(ToolSpec("traceroute", "Trace the route / reachability to a host (read-only).",
            Schema.obj("host" to Schema.str("hostname or IP"), required = listOf("host")),
            risk = { Risk.SENSITIVE }, exec = { Lan.traceroute(it.getString("host")) }))
        add(ToolSpec("whois", "WHOIS lookup for a domain.",
            Schema.obj("domain" to Schema.str("domain name"), required = listOf("domain")),
            risk = { Risk.SENSITIVE }, exec = { Lan.whois(it.getString("domain")) }))
        add(ToolSpec("ssl_cert_info", "Read the TLS certificate details presented by a host:port (read-only).",
            Schema.obj("host" to Schema.str("hostname or IP"), "port" to Schema.int("TLS port, default 443", 1, 65535), required = listOf("host")),
            risk = { Risk.SENSITIVE }, exec = { Lan.sslCert(it.getString("host"), it.intOr("port", 443)) }))
        add(ToolSpec("packet_capture", "Local packet capture status/instructions. Capture needs VpnService consent (no root) or Shizuku/root.",
            Schema.obj(required = emptyList()), risk = { Risk.HIGH }, exec = {
                "Локальний перехоплювач пакетів потребує окремого дозволу:\n" +
                "• Без root: Android VpnService (система показує діалог згоди на VPN; трафік іде лише через локальний tun — зовнішні пристрої не чіпаються).\n" +
                "• З root/Shizuku: tcpdump на інтерфейсі wlan0.\n" +
                "Для безпеки капчур вимкнено за замовчуванням і запускається лише з явної згоди в системному діалозі. Інші функції (виявлення/скан) уже доступні."
            }))

        // ---- Local RAG (on-device embeddings, persists in Room) ----
        add(ToolSpec("rag_add", "Save a note/snippet to the local knowledge base (on-device embeddings).",
            Schema.obj("title" to Schema.str("short title"), "content" to Schema.str("text to remember"), required = listOf("title", "content")),
            risk = { Risk.LOW }, exec = {
                val c = it.getString("content")
                val id = db.dao().insert(RagDoc(title = it.getString("title"), content = c, embedding = Embeddings.encode(Embeddings.embed(it.getString("title") + " " + c))))
                "Збережено в базу знань (id=$id)."
            }))
        add(ToolSpec("rag_search", "Semantic search of the local knowledge base.",
            Schema.obj("query" to Schema.str("search query"), "top_k" to Schema.int("results, default 5", 1, 20), required = listOf("query")),
            risk = { Risk.LOW }, exec = {
                val hits = Embeddings.search(Embeddings.embed(it.getString("query")), db.dao().ragDocs(), it.intOr("top_k", 5))
                if (hits.isEmpty()) "База знань порожня." else hits.joinToString("\n\n") { h -> "[${"%.2f".format(h.score)}] ${h.title} (id=${h.id})\n${h.content.take(500)}" }
            }))
        add(ToolSpec("rag_list", "List saved knowledge-base entries.",
            Schema.obj(required = emptyList()), risk = { Risk.LOW }, exec = {
                val d = db.dao().ragDocs(); if (d.isEmpty()) "Порожньо." else d.joinToString("\n") { r -> "id=${r.id} · ${r.title}" }
            }))
        add(ToolSpec("rag_delete", "Delete a knowledge-base entry by id.",
            Schema.obj("id" to Schema.int("entry id"), required = listOf("id")),
            risk = { Risk.SENSITIVE }, exec = { db.dao().deleteRag(it.getInt("id").toLong()); "Видалено id=${it.getInt("id")}." }))

        // ---- Screen context via MediaProjection + OCR (user-granted capture only) ----
        add(ToolSpec("screen_ocr", "Return text recognized from the last user-approved screen capture. Start a capture from the Tools tab (MediaProjection consent required).",
            Schema.obj(required = emptyList()), risk = { Risk.HIGH }, exec = {
                val t = ScreenText.last
                if (t.isNullOrBlank()) "Немає захопленого екрана. Натисни «Захопити екран (OCR)» у вкладці Інструменти та дай системний дозвіл." else "Текст з екрана:\n" + t
            }))

        // ---- Agentic plan panel (visible multi-step planning) ----
        add(ToolSpec("set_plan", "Declare a short ordered plan of steps for a complex task; it is shown to the user in a plan panel. Pass a JSON array of step strings.",
            Schema.obj("steps" to Schema.str("JSON array of step descriptions, e.g. [\"scan LAN\",\"label TV\"]"), required = listOf("steps")),
            risk = { Risk.LOW }, exec = {
                val list = PlanCodec.fromStrings(it.getString("steps"))
                if (list.isEmpty()) "План порожній або некоректний JSON-масив." else { AgentPlan.set(list); "План на ${list.size} крок(ів) показано користувачу." }
            }))
        add(ToolSpec("complete_step", "Mark a plan step (0-based index) as done or failed; updates the plan panel.",
            Schema.obj("index" to Schema.int("0-based step index", 0, 99), "ok" to Schema.bool("true=done, false=failed"), required = listOf("index")),
            risk = { Risk.LOW }, exec = {
                AgentPlan.mark(it.getInt("index"), if (it.optBoolean("ok", true)) "done" else "failed"); "Крок ${it.getInt("index")} оновлено."
            }))

        // ---- LAN device inventory (labeled, persistent) ----
        add(ToolSpec("inventory_add", "Save/update a local-network device in the labeled inventory (private IPs only).",
            Schema.obj("ip" to Schema.str("device private IP"), "mac" to Schema.str("MAC (optional)"), "label" to Schema.str("friendly label (optional)"), required = listOf("ip")),
            risk = { Risk.SENSITIVE }, preview = { "inventory_add ${it.str("ip")} ${it.str("label")}" }, exec = {
                val ip = it.getString("ip").trim()
                if (!NetGuard.isPrivateV4(ip)) "Відхилено: ${ip} не є приватною (RFC1918) адресою. Інвентар лише для твоєї локальної мережі." else {
                    val existing = db.dao().deviceByIp(ip)
                    if (existing == null) { val id = db.dao().insert(DeviceEntity(ip = ip, mac = it.str("mac"), label = it.str("label"))); "Додано пристрій id=$id ($ip)." }
                    else { db.dao().update(existing.copy(mac = it.str("mac").ifBlank { existing.mac }, label = it.str("label").ifBlank { existing.label }, lastSeen = System.currentTimeMillis())); "Оновлено пристрій ($ip)." }
                }
            }))
        add(ToolSpec("inventory_list", "List saved local-network devices with labels.",
            Schema.obj(required = emptyList()), risk = { Risk.LOW }, exec = {
                val d = db.dao().devices(); if (d.isEmpty()) "Інвентар порожній." else d.joinToString("\n") { x -> "${x.ip}  ${x.mac}  ${x.label}" }
            }))
        add(ToolSpec("inventory_label", "Set the friendly label for a saved device by IP.",
            Schema.obj("ip" to Schema.str("device IP"), "label" to Schema.str("new label"), required = listOf("ip", "label")),
            risk = { Risk.SENSITIVE }, preview = { "label ${it.str("ip")} = ${it.str("label")}" }, exec = {
                val ex = db.dao().deviceByIp(it.getString("ip"))
                if (ex == null) "Пристрій ${it.getString("ip")} не знайдено в інвентарі."
                else { db.dao().update(ex.copy(label = it.getString("label"))); "Мітку оновлено для ${it.getString("ip")}." }
            }))
    }

    private val byName = specs.associateBy { it.name }
    fun openAiTools(): JSONArray = JSONArray().apply {
        specs.filter { it.name !in store.disabledTools }.forEach { put(it.toOpenAi()) }
    }
    fun get(name: String) = byName[name]?.takeIf { name !in store.disabledTools }  // fail-closed per permission profile
    /** Base risk of each tool (evaluated with empty args) — used by per-tool permission profiles UI. */
    fun toolRisks(): List<Pair<String, Risk>> = specs.map { it.name to runCatching { it.risk(JSONObject()) }.getOrDefault(Risk.SENSITIVE) }

    // ---- implementations ----

    @SuppressLint("MissingPermission") @Suppress("DEPRECATION")
    private suspend fun networkInfo(scan: Boolean): String = withContext(Dispatchers.IO) {
        val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val o = JSONObject().put("wifi_enabled", wm.isWifiEnabled)
        wm.connectionInfo?.let { ci -> o.put("ssid", ci.ssid).put("bssid", ci.bssid).put("rssi", ci.rssi).put("link_speed_mbps", ci.linkSpeed) }
        val ips = JSONArray()
        NetworkInterface.getNetworkInterfaces()?.toList()?.forEach { ni ->
            ni.inetAddresses.toList().filter { !it.isLoopbackAddress }.forEach { ips.put("${ni.name}: ${it.hostAddress}") }
        }
        o.put("local_ips", ips)
        if (scan) runCatching {
            wm.startScan()
            val arr = JSONArray()
            wm.scanResults.sortedByDescending { it.level }.take(25).forEach {
                arr.put(JSONObject().put("ssid", it.SSID).put("bssid", it.BSSID).put("level", it.level).put("freq", it.frequency))
            }
            o.put("scan_results", arr); o.put("scan_note", "results may be throttled/stale")
        }.onFailure { o.put("scan_error", it.message) }
        o.toString(2)
    }

    private suspend fun discoverDevices(): String {
        val out = JSONObject()
        out.put("ssdp", Upnp.discover(3000))
        out.put("mdns", nsdDiscover())
        return out.toString(2)
    }

    private suspend fun nsdDiscover(): JSONArray = withContext(Dispatchers.IO) {
        val nsd = ctx.getSystemService(Context.NSD_SERVICE) as NsdManager
        val types = listOf("_googlecast._tcp.", "_airplay._tcp.", "_spotify-connect._tcp.", "_http._tcp.", "_androidtvremote2._tcp.")
        val found = java.util.Collections.synchronizedList(mutableListOf<JSONObject>())
        for (type in types) {
            withTimeoutOrNull(2500) {
                suspendCancellableCoroutine<Unit> { cont ->
                    val listener = object : NsdManager.DiscoveryListener {
                        override fun onDiscoveryStarted(s: String) {}
                        override fun onServiceFound(info: NsdServiceInfo) {
                            found.add(JSONObject().put("type", type).put("name", info.serviceName))
                        }
                        override fun onServiceLost(info: NsdServiceInfo) {}
                        override fun onStartDiscoveryFailed(s: String, e: Int) { if (cont.isActive) cont.resume(Unit) }
                        override fun onStopDiscoveryFailed(s: String, e: Int) {}
                        override fun onDiscoveryStopped(s: String) { if (cont.isActive) cont.resume(Unit) }
                    }
                    runCatching { nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener) }
                    cont.invokeOnCancellation { runCatching { nsd.stopServiceDiscovery(listener) } }
                }
            }
        }
        JSONArray(found.distinctBy { it.toString() })
    }

    private suspend fun dlna(a: JSONObject): String = withContext(Dispatchers.IO) {
        when (a.getString("action")) {
            "play" -> Upnp.play(a.getString("avtransport_url"), a.getString("media_url"))
            "pause" -> Upnp.transport(a.getString("avtransport_url"), "Pause")
            "stop" -> Upnp.transport(a.getString("avtransport_url"), "Stop")
            "get_volume" -> Upnp.getVolume(a.getString("rendering_url"))
            "set_volume" -> Upnp.setVolume(a.getString("rendering_url"), a.getInt("volume"))
            else -> "unknown action"
        }
    }

    private suspend fun adbTv(a: JSONObject): String = withContext(Dispatchers.IO) {
        val host = a.getString("host"); val port = a.intOr("port", 5555)
        runCatching {
            dadb.Dadb.create(host, port).use { db ->
                when (a.getString("action")) {
                    "keyevent" -> {
                        val key = a.getString("key")
                        if (!Protocols.isValidKey(key)) return@use "Unknown key '$key'"
                        db.shell("input keyevent KEYCODE_${Protocols.normalizeKey(key)}").output
                    }
                    "launch" -> { val pkg = a.getString("command"); if (!Regex("^[A-Za-z][A-Za-z0-9_.]+$").matches(pkg)) return@use "Invalid package name"; db.shell("monkey -p $pkg -c android.intent.category.LAUNCHER 1").output }
                    "shell" -> db.shell(a.getString("command")).output
                    else -> "unknown action"
                }.ifBlank { "ok" }
            }
        }.getOrElse { "ADB failed (${it.message}). On Android 11+ enable Wireless debugging and pair first; then use port 5555 after 'adb connect'." }
    }

    private fun openPanel(action: String, note: String): String {
        ctx.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return note
    }

    @Suppress("DEPRECATION")
    private fun toggle(setting: String, value: String): String = when (setting) {
        "wifi" -> if (Build.VERSION.SDK_INT >= 29) openPanel(Settings.Panel.ACTION_WIFI, "Opened Wi-Fi panel (direct toggle disallowed on Android 10+).")
            else { (ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).isWifiEnabled = value == "on"; "Wi-Fi $value" }
        "bluetooth" -> openPanel(Settings.ACTION_BLUETOOTH_SETTINGS, "Opened Bluetooth settings.")
        "brightness" -> if (!Settings.System.canWrite(ctx)) {
            ctx.startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            "WRITE_SETTINGS not granted; opened grant screen. Ask user to allow and retry."
        } else {
            val pct = value.toIntOrNull()?.coerceIn(0, 100) ?: return "brightness needs 0-100"
            Settings.System.putInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, 0)
            Settings.System.putInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS, pct * 255 / 100); "Brightness $pct%"
        }
        "volume" -> {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val pct = value.toIntOrNull()?.coerceIn(0, 100) ?: return "volume needs 0-100"
            am.setStreamVolume(AudioManager.STREAM_MUSIC, pct * am.getStreamMaxVolume(AudioManager.STREAM_MUSIC) / 100, AudioManager.FLAG_SHOW_UI)
            "Media volume $pct%"
        }
        "dnd" -> {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (!nm.isNotificationPolicyAccessGranted) { openPanel(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS, "DND access not granted; opened settings.") }
            else { nm.setInterruptionFilter(if (value == "on") NotificationManager.INTERRUPTION_FILTER_PRIORITY else NotificationManager.INTERRUPTION_FILTER_ALL); "DND $value" }
        }
        "flashlight" -> runCatching {
            val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val id = cm.cameraIdList.first { cm.getCameraCharacteristics(it).get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
            cm.setTorchMode(id, value == "on"); "Flashlight $value"
        }.getOrElse { "Flashlight failed: ${it.message}" }
        else -> "Unsupported setting"
    }

    private fun safe(path: String): File? {
        val ws = Exec.workspace(ctx)
        val f = File(ws, path).canonicalFile
        return if (f.path == ws.canonicalPath || f.path.startsWith(ws.canonicalPath + File.separator)) f else null
    }
    private fun fileOp(a: JSONObject): String {
        val f = safe(a.getString("path")) ?: return "path escapes workspace"
        return when (a.getString("op")) {
            "list" -> (f.listFiles()?.joinToString("\n") { (if (it.isDirectory) "d " else "- ") + it.name + "  ${it.length()}b" } ?: "not a dir").ifBlank { "(empty)" }
            "read" -> if (f.exists()) f.readText().take(32000) else "not found"
            "write" -> { f.parentFile?.mkdirs(); f.writeText(a.str("content")); "wrote ${f.length()} bytes to ${a.getString("path")}" }
            "delete" -> if (f.deleteRecursively()) "deleted" else "delete failed"
            "mkdir" -> if (f.mkdirs()) "created" else "mkdir failed"
            else -> "unknown op"
        }
    }

    private fun listApps(filter: String): String {
        val pm = ctx.packageManager
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(main, 0).mapNotNull {
            val label = it.loadLabel(pm).toString(); val pkg = it.activityInfo.packageName
            if (filter.isBlank() || label.contains(filter, true) || pkg.contains(filter, true)) "$label  —  $pkg" else null
        }.distinct().sorted().joinToString("\n").ifBlank { "no matches" }.take(8000)
    }

    private fun launchApp(pkg: String): String {
        val i = ctx.packageManager.getLaunchIntentForPackage(pkg) ?: return "no launcher for $pkg"
        ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return "launched $pkg"
    }

    private fun clipboard(a: JSONObject): String {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        return if (a.getString("op") == "set") { cm.setPrimaryClip(ClipData.newPlainText("agent", a.str("text"))); "clipboard set" }
        else cm.primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString() ?: "(empty or unavailable in background)"
    }

    @SuppressLint("Range")
    private fun contacts(query: String): String {
        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val sel = if (query.isBlank()) null else "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
        val args = if (query.isBlank()) null else arrayOf("%$query%")
        return runCatching {
            ctx.contentResolver.query(uri, arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER), sel, args, null)?.use { c ->
                buildString { var n = 0; while (c.moveToNext() && n++ < 50) appendLine("${c.getString(0)}: ${c.getString(1)}") }.ifBlank { "no contacts" }
            } ?: "query failed"
        }.getOrElse { "contacts error (permission?): ${it.message}" }
    }

    private fun sendSms(number: String, msg: String): String {
        ctx.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$number")).putExtra("sms_body", msg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return "Opened SMS app to $number (user must press send)."
    }

    private fun termuxRun(interp: String, code: String): String {
        val dir = Exec.workspace(ctx); val script = File(dir, "_termux_run.${if (interp == "python") "py" else if (interp == "node") "js" else "sh"}")
        script.writeText(code)
        val exe = when (interp) { "python" -> "/data/data/com.termux/files/usr/bin/python"; "node" -> "/data/data/com.termux/files/usr/bin/node"; else -> "/data/data/com.termux/files/usr/bin/bash" }
        return runCatching {
            val i = Intent().setClassName("com.termux", "com.termux.app.RunCommandService").setAction("com.termux.RUN_COMMAND")
                .putExtra("com.termux.RUN_COMMAND_PATH", exe)
                .putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf(script.absolutePath))
                .putExtra("com.termux.RUN_COMMAND_BACKGROUND", false)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
            "Dispatched to Termux ($interp). Termux must be installed with allow-external-apps=true; output appears in Termux. For in-app output use the shell tool."
        }.getOrElse { "Termux not available (${it.message}). Install Termux + enable RUN_COMMAND, or use the shell tool (sandbox bash)." }
    }

    private fun uiAction(a: JSONObject): String {
        val svc = AgentAccessibilityService.instance ?: return "Accessibility service not enabled. Grant it in settings."
        return when (a.getString("action")) {
            "dump" -> svc.dump()
            "click" -> svc.click(a.str("text").ifBlank { null }, a.str("id").ifBlank { null })
            "set_text" -> svc.setText(a.str("text").ifBlank { null }, a.str("id").ifBlank { null }, a.str("value"))
            "tap" -> svc.tap(a.optInt("x").toFloat(), a.optInt("y").toFloat())
            "swipe" -> svc.swipe(a.optInt("x").toFloat(), a.optInt("y").toFloat(), a.optInt("x2").toFloat(), a.optInt("y2").toFloat())
            "global" -> svc.global(a.str("value"))
            else -> "unknown action"
        }
    }

    private suspend fun securityReport(): String {
        val risky = mapOf(21 to "FTP", 22 to "SSH", 23 to "Telnet(!)", 139 to "SMB", 445 to "SMB",
            3389 to "RDP", 5555 to "ADB(open!)", 8008 to "Cast", 1900 to "UPnP", 554 to "RTSP", 80 to "HTTP", 443 to "HTTPS")
        val scan = Net.portScan(risky.keys.toList())
        val sb = StringBuilder("Network security report (local subnet):\n")
        if (scan.length() == 0) return sb.append("No hosts with these ports responded.").toString()
        for (i in 0 until scan.length()) {
            val h = scan.getJSONObject(i)
            val ports = h.getJSONArray("open_ports")
            val notes = (0 until ports.length()).map { val p = ports.getInt(it); "$p/${risky[p] ?: "?"}" }
            sb.append("- ${h.getString("host")}: ${notes.joinToString(", ")}\n")
            if (notes.any { it.contains("!") }) sb.append("  ⚠ risky service exposed\n")
        }
        sb.append("\nReview Telnet/open-ADB/RDP exposure; restrict to trusted devices.")
        return sb.toString()
    }

    private fun appPermissions(pkg: String): String = runCatching {
        val pm = ctx.packageManager
        val info = pm.getPackageInfo(pkg, android.content.pm.PackageManager.GET_PERMISSIONS)
        val perms = info.requestedPermissions ?: arrayOf()
        if (perms.isEmpty()) return "No declared permissions for $pkg."
        val dangerous = perms.filter {
            runCatching { pm.getPermissionInfo(it, 0).protectionLevel and android.content.pm.PermissionInfo.PROTECTION_DANGEROUS != 0 }.getOrDefault(false)
        }
        buildString {
            append("$pkg\nTotal permissions: ${perms.size}\n")
            append("Dangerous (${dangerous.size}):\n")
            dangerous.forEach { append("  ! ${it.substringAfterLast('.')}\n") }
            append("Other: ${(perms.toList() - dangerous.toSet()).joinToString(", ") { it.substringAfterLast('.') }.take(1500)}")
        }
    }.getOrElse { "Cannot inspect $pkg: ${it.message}" }


    private suspend fun webFetch(url: String): String = withContext(Dispatchers.IO) {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return@withContext "URL must start with http(s)://"
        val targetHost = runCatching { java.net.URI(url).host }.getOrNull() ?: return@withContext "Invalid URL."
        runCatching {
            val addr = java.net.InetAddress.getByName(targetHost)
            if (addr.isLoopbackAddress || addr.isSiteLocalAddress || addr.isLinkLocalAddress || addr.isAnyLocalAddress)
                return@withContext "Відхилено: web_fetch не звертається до локальних/приватних адрес ($targetHost). Для сканування LAN використовуй інструменти мережі."
        }
        runCatching {
            val http = okhttp3.OkHttpClient.Builder().connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS).followRedirects(true).build()
            val req = okhttp3.Request.Builder().url(url).header("User-Agent", "Mozilla/5.0 DRON.exe").build()
            http.newCall(req).execute().use { r ->
                val body = r.body?.string() ?: ""
                if (!r.isSuccessful) return@use "HTTP ${r.code}"
                val text = body
                    .replace(Regex("(?is)<script.*?</script>"), " ")
                    .replace(Regex("(?is)<style.*?</style>"), " ")
                    .replace(Regex("(?is)<[^>]+>"), " ")
                    .replace(Regex("&nbsp;"), " ").replace(Regex("&amp;"), "&")
                    .replace(Regex("[ \t]+"), " ").replace(Regex("""\n\s*\n+"""), "\n").trim()
                "URL: $url\n\n" + text.take(12000)
            }
        }.getOrElse { "Fetch failed: ${Protocols.friendlyError(it.message)}" }
    }

}
