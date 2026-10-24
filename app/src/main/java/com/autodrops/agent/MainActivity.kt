package com.autodrops.agent

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.runtime.collectAsState

class MainActivity : ComponentActivity() {
    private val perms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFF80CBC4), background = Color(0xFF0E0E10))) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { Root { askPerms() } }
        } }
    }
    fun askPerms() {
        val p = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.READ_CONTACTS)
        if (Build.VERSION.SDK_INT >= 33) p += Manifest.permission.NEARBY_WIFI_DEVICES
        perms.launch(p.toTypedArray())
    }
}

@Composable
fun Root(vm: AgentViewModel = viewModel(), askPerms: () -> Unit) {
    var tab by remember { mutableStateOf(0) }
    val tabs = listOf("Chat" to Icons.Default.Chat, "History" to Icons.Default.History,
        "Tools" to Icons.Default.Build, "Models" to Icons.Default.Memory, "Remote" to Icons.Default.Tv)
    Scaffold(bottomBar = {
        NavigationBar {
            tabs.forEachIndexed { i, (label, icon) ->
                NavigationBarItem(selected = tab == i, onClick = { tab = i },
                    icon = { Icon(icon, label) }, label = { Text(label, fontSize = 11.sp) })
            }
        }
    }) { pad ->
        Box(Modifier.padding(pad)) {
            when (tab) {
                0 -> ChatTab(vm)
                1 -> HistoryTab(vm)
                2 -> ToolsTab(vm, askPerms)
                3 -> ModelsTab(vm)
                else -> RemoteTab(vm)
            }
        }
    }
    vm.pending?.let { p ->
        AlertDialog(onDismissRequest = {},
            icon = { Icon(Icons.Default.Warning, null) },
            title = { Text("Confirm ${p.name}  [${p.risk}]") },
            text = { Column {
                SelectionContainer { Text(sanitizeForDisplay(p.preview), fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                    modifier = Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState())) }
                if (p.risk != Risk.ELEVATED) Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(vm.autoApproveSession, { vm.autoApproveSession = it })
                    Text("Auto-approve non-elevated for this session", fontSize = 12.sp)
                }
            } },
            confirmButton = { Button(onClick = { vm.resolvePending(true) }) { Text("Allow") } },
            dismissButton = { TextButton(onClick = { vm.resolvePending(false) }) { Text("Deny") } })
    }
}

@Composable
fun ChatTab(vm: AgentViewModel) {
    var input by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
        LazyColumn(Modifier.weight(1f).padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp), reverseLayout = true) {
            items(vm.items.reversed(), key = { it.id }) { m ->
                if (m.card != null) ToolCardView(m.card) else {
                    val bg = when (m.role) { "user" -> Color(0xFF1E3A5F); "error" -> Color(0xFF5F1E1E); "info" -> Color(0xFF3A3A1E); else -> Color(0xFF1C2024) }
                    SelectionContainer { Text(m.text, Modifier.fillMaxWidth().background(bg, RoundedCornerShape(10.dp)).padding(10.dp), fontSize = 15.sp) }
                }
            }
        }
        if (vm.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(input, { input = it }, Modifier.weight(1f), placeholder = { Text("Ask the agent…") }, maxLines = 4)
            if (vm.busy) IconButton(onClick = { vm.stop() }) { Icon(Icons.Default.Stop, "stop") }
            else Button(onClick = { vm.send(input); input = "" }, Modifier.padding(start = 6.dp)) { Icon(Icons.Default.Send, "send") }
        }
    }
}

@Composable
fun ToolCardView(card: ToolCard) {
    val color = when (card.status) { "done" -> Color(0xFF14331C); "denied" -> Color(0xFF3A1E1E); "awaiting approval" -> Color(0xFF3A331E); else -> Color(0xFF1C1C22) }
    Column(Modifier.fillMaxWidth().background(color, RoundedCornerShape(10.dp)).padding(10.dp)) {
        Text("🛠 ${card.name}  ·  ${card.status}", fontSize = 13.sp, color = Color(0xFF9FD8CF))
        if (card.preview.isNotBlank()) Text(sanitizeForDisplay(card.preview), fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = Color(0xFFBBBBBB))
        if (card.output.isNotBlank()) SelectionContainer { Text(sanitizeForDisplay(card.output.take(4000)), fontFamily = FontFamily.Monospace, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp)) }
    }
}

@Composable
fun HistoryTab(vm: AgentViewModel) {
    val convs by vm.conversations().collectAsState(initial = emptyList())
    Column(Modifier.fillMaxSize().systemBarsPadding().padding(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("History", style = MaterialTheme.typography.titleLarge)
            Button(onClick = { vm.startNew() }) { Text("New") }
        }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(convs, key = { it.id }) { c ->
                Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
                    Text(c.title, fontSize = 15.sp); Text(c.model, fontSize = 11.sp, color = Color.Gray)
                } }
            }
        }
    }
}

@Composable
fun ToolsTab(vm: AgentViewModel, askPerms: () -> Unit) {
    val ctx = LocalContext.current
    Column(Modifier.fillMaxSize().systemBarsPadding().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Permissions & special access", style = MaterialTheme.typography.titleMedium)
        Button(onClick = askPerms, Modifier.fillMaxWidth()) { Text("Request runtime permissions") }
        fun open(a: String) = ctx.startActivity(Intent(a).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        OutlinedButton(onClick = { open(Settings.ACTION_ACCESSIBILITY_SETTINGS) }, Modifier.fillMaxWidth()) { Text("Enable Accessibility (UI actions)") }
        OutlinedButton(onClick = { open("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS") }, Modifier.fillMaxWidth()) { Text("Enable Notification Listener") }
        OutlinedButton(onClick = { open(Settings.ACTION_MANAGE_WRITE_SETTINGS) }, Modifier.fillMaxWidth()) { Text("Allow WRITE_SETTINGS (brightness)") }
        OutlinedButton(onClick = { open(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS) }, Modifier.fillMaxWidth()) { Text("Allow DND access") }
        Divider()
        Text("Shell backend", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("sandbox", "root").forEach { mode ->
                FilterChip(selected = vm.store.shellMode == mode, onClick = { vm.store.shellMode = mode }, label = { Text(mode) })
            }
        }
        Text("sandbox = app UID bash. root = su if device is rooted. Termux used for python/node via run_script.", fontSize = 11.sp, color = Color.Gray)
        Divider()
        Text("Agent max steps: ${vm.maxSteps}", style = MaterialTheme.typography.titleMedium)
        Slider(vm.maxSteps.toFloat(), { vm.maxSteps = it.toInt() }, valueRange = 1f..30f)
        Text("Available tools:", style = MaterialTheme.typography.titleMedium)
        Text("network_info, lan_scan, ping, dns_lookup, discover_devices, dlna_control, adbtv, toggle_settings, file_op, shell, run_script, list_apps, launch_app, notifications, clipboard, contacts, send_sms, ui_action", fontSize = 12.sp, color = Color.Gray)
    }
}

@Composable
fun ModelsTab(vm: AgentViewModel) {
    var key by remember { mutableStateOf("") }
    var base by remember { mutableStateOf(vm.store.baseUrl) }
    var search by remember { mutableStateOf("") }
    var fav by remember { mutableStateOf(vm.store.favorites) }
    Column(Modifier.fillMaxSize().systemBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Provider & model", style = MaterialTheme.typography.titleLarge)
        OutlinedTextField(base, { base = it }, label = { Text("Base URL (https)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(key, { key = it }, label = { Text(if (vm.store.apiKey.isBlank()) "API key" else "API key (saved; blank keeps)") },
            visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { if (key.isNotBlank()) vm.store.apiKey = key; vm.store.baseUrl = base.ifBlank { SecureStore.DEFAULT_BASE }; vm.loadModels() }) { Text("Save & fetch") }
            Row(verticalAlignment = Alignment.CenterVertically) { Switch(vm.store.stream, { vm.store.stream = it }); Text("Stream", fontSize = 12.sp) }
        }
        Text("Current: ${vm.store.model}", fontSize = 12.sp, color = Color(0xFF80CBC4))
        vm.modelsError?.let { Text("Error: $it", color = Color(0xFFFF8A80), fontSize = 12.sp) }
        OutlinedTextField(search, { search = it }, label = { Text("Search models") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        val list = vm.models.filter { it.id.contains(search, true) }.sortedByDescending { it.id in fav }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(list, key = { it.id }) { m ->
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = if (m.id == vm.store.model) Color(0xFF14331C) else Color(0xFF17171C))) {
                    Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(m.id, fontSize = 13.sp)
                            Text("ctx ${m.context} · in ${m.promptPrice} · out ${m.completionPrice}${if (m.tools) " · tools" else ""}", fontSize = 10.sp, color = Color.Gray)
                        }
                        IconButton(onClick = { fav = if (m.id in fav) fav - m.id else fav + m.id; vm.store.favorites = fav }) {
                            Icon(if (m.id in fav) Icons.Default.Star else Icons.Default.StarBorder, "fav")
                        }
                        Button(onClick = { vm.store.model = m.id }) { Text("Use") }
                    }
                }
            }
        }
    }
}

@Composable
fun RemoteTab(vm: AgentViewModel) {
    Column(Modifier.fillMaxSize().systemBarsPadding().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Remote (DLNA / Android TV)", style = MaterialTheme.typography.titleLarge)
        Text("Ask in Chat: \"discover devices on my wifi and control my TV\". The agent uses discover_devices, dlna_control and adbtv tools. Buttons below are shortcuts that prompt the agent.", fontSize = 12.sp, color = Color.Gray)
        Button(onClick = { vm.send("Discover devices on my local Wi-Fi (mDNS and SSDP) and list TVs and speakers with their control URLs.") }, Modifier.fillMaxWidth()) { Text("Discover devices") }
        Divider()
        Text("D-pad", style = MaterialTheme.typography.titleMedium)
        @Composable fun k(label: String, code: String) = OutlinedButton(onClick = { vm.send("On the Android TV I discovered, send key $code via adbtv.") }) { Text(label) }
        k("▲ Up", "DPAD_UP")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { k("◀", "DPAD_LEFT"); k("OK", "DPAD_CENTER"); k("▶", "DPAD_RIGHT") }
        k("▼ Down", "DPAD_DOWN")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { k("Vol+", "VOLUME_UP"); k("Vol-", "VOLUME_DOWN"); k("Home", "HOME"); k("Back", "BACK") }
    }
}
