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
import androidx.compose.foundation.clickable
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

class MainActivity : ComponentActivity() {
    private val perms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFF80CBC4), background = Color(0xFF0E0E10), surface = Color(0xFF17171C))) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { Root { askPerms() } }
        } }
    }
    fun askPerms() {
        val p = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.READ_CONTACTS)
        if (Build.VERSION.SDK_INT >= 33) p += Manifest.permission.NEARBY_WIFI_DEVICES
        perms.launch(p.toTypedArray())
    }
}

@Composable fun Empty(text: String) = Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
    Text(text, color = Color(0xFF6B6B70), fontSize = 14.sp, modifier = Modifier.padding(32.dp))
}

@Composable
fun Root(vm: AgentViewModel = viewModel(), askPerms: () -> Unit) {
    var tab by remember { mutableStateOf(0) }
    val tabs = listOf("Chat" to Icons.Default.Chat, "Dev" to Icons.Default.Code,
        "Review" to Icons.Default.RateReview, "Models" to Icons.Default.Memory, "More" to Icons.Default.MoreHoriz)
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
                0 -> ChatTab(vm); 1 -> DevTab(vm); 2 -> ReviewTab(vm); 3 -> ModelsTab(vm); else -> MoreTab(vm, askPerms)
            }
        }
    }
    vm.pending?.let { p ->
        AlertDialog(onDismissRequest = {}, icon = { Icon(Icons.Default.Warning, null) },
            title = { Text("Confirm ${p.name}  [${p.risk}]") },
            text = { Column {
                SelectionContainer { Text(sanitizeForDisplay(p.preview), fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                    modifier = Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState())) }
                if (p.risk != Risk.ELEVATED) Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(vm.autoApproveSession, { vm.autoApproveSession = it }); Text("Auto-approve non-elevated this session", fontSize = 12.sp)
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
        if (vm.items.isEmpty()) Box(Modifier.weight(1f)) { Empty("Ask the agent to inspect your phone, scan the network, find a TV, or write and run code.") }
        else LazyColumn(Modifier.weight(1f).padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp), reverseLayout = true) {
            items(vm.items.reversed(), key = { it.id }) { m ->
                if (m.card != null) ToolCardView(m.card) else {
                    val bg = when (m.role) { "user" -> Color(0xFF1E3A5F); "error" -> Color(0xFF5F1E1E); "info" -> Color(0xFF3A3A1E); else -> Color(0xFF1C2024) }
                    SelectionContainer { Text(m.text.ifBlank { "…" }, Modifier.fillMaxWidth().background(bg, RoundedCornerShape(10.dp)).padding(10.dp), fontSize = 15.sp) }
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
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (card.status == "running" || card.status == "awaiting approval") CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 2.dp)
            Text("  🛠 ${card.name}  ·  ${card.status}", fontSize = 13.sp, color = Color(0xFF9FD8CF))
        }
        if (card.preview.isNotBlank()) Text(sanitizeForDisplay(card.preview), fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = Color(0xFFBBBBBB))
        if (card.output.isNotBlank()) SelectionContainer { Text(sanitizeForDisplay(card.output.take(4000)), fontFamily = FontFamily.Monospace, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp)) }
    }
}

@Composable
fun DevTab(vm: AgentViewModel) {
    val ctx = LocalContext.current
    var files by remember { mutableStateOf(emptyList<String>()) }
    var selected by remember { mutableStateOf<String?>(null) }
    var content by remember { mutableStateOf("") }
    var dirty by remember { mutableStateOf(false) }
    var term by remember { mutableStateOf("") }
    var cmd by remember { mutableStateOf("") }
    fun refresh() { vm.seedSampleProject(); files = vm.workspaceFiles() }
    LaunchedEffect(Unit) { refresh() }
    Column(Modifier.fillMaxSize().systemBarsPadding().imePadding().padding(8.dp)) {
        Text("Workspace", style = MaterialTheme.typography.titleLarge)
        // file tree
        Text("Files", fontSize = 12.sp, color = Color.Gray)
        if (files.isEmpty()) Text("No files yet. Create one in the terminal, e.g. echo hi > a.txt", fontSize = 12.sp, color = Color(0xFF6B6B70))
        LazyColumn(Modifier.heightIn(max = 120.dp)) {
            items(files, key = { it }) { f ->
                Text(f, Modifier.fillMaxWidth().background(if (f == selected) Color(0xFF14331C) else Color.Transparent)
                    .clickable { selected = f; content = vm.readFile(f); dirty = false }.padding(6.dp),
                    fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            }
        }
        Divider(Modifier.padding(vertical = 4.dp))
        // editor
        if (selected == null) Text("Select a file to edit.", fontSize = 12.sp, color = Color(0xFF6B6B70))
        else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(selected!!, Modifier.weight(1f), fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = Color(0xFF80CBC4))
                if (dirty) Text("●", color = Color(0xFFFFB74D))
                TextButton(onClick = { vm.writeFile(selected!!, content); dirty = false }) { Text("Save") }
                TextButton(onClick = { vm.runTerminal("cd \"$(dirname ${selected})\"; " + runCommandFor(selected!!), true) { term = it } }) { Text("Run") }
            }
            OutlinedTextField(content, { content = it; dirty = true }, Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 240.dp),
                textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp), singleLine = false,
                visualTransformation = { androidx.compose.ui.text.input.TransformedText(Highlight.render(it.text), androidx.compose.ui.text.input.OffsetMapping.Identity) })
        }
        Divider(Modifier.padding(vertical = 4.dp))
        // terminal
        Text("Terminal (${vm.store.shellMode})", fontSize = 12.sp, color = Color.Gray)
        SelectionContainer { Text(term.ifBlank { "Output appears here." }, Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).background(Color(0xFF0B0B0D)).padding(6.dp),
            fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = Color(0xFFBBBBBB)) }
        if (vm.termBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(cmd, { cmd = it }, Modifier.weight(1f), placeholder = { Text("$ command") }, singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace))
            Button(onClick = { vm.runTerminal(cmd, true) { term += "\n$ $cmd\n$it"; refresh() }; cmd = "" }, enabled = !vm.termBusy, modifier = Modifier.padding(start = 6.dp)) { Text("Run") }
        }
    }
}

@Composable
fun ReviewTab(vm: AgentViewModel) {
    var files by remember { mutableStateOf(emptyList<String>()) }
    var sel by remember { mutableStateOf(setOf<String>()) }
    LaunchedEffect(Unit) { vm.seedSampleProject(); files = vm.workspaceFiles() }
    Column(Modifier.fillMaxSize().systemBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Code review", style = MaterialTheme.typography.titleLarge)
        Text("Reviewed by: ${vm.store.model} (change in Models). Select files from your workspace.", fontSize = 12.sp, color = Color.Gray)
        if (files.isEmpty()) Text("No files in workspace. Add some in the Dev tab.", fontSize = 12.sp, color = Color(0xFF6B6B70))
        LazyColumn(Modifier.heightIn(max = 160.dp)) {
            items(files, key = { it }) { f ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(f in sel, { sel = if (f in sel) sel - f else sel + f })
                    Text(f, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                }
            }
        }
        Button(onClick = { vm.runReview(sel.toList(), vm.store.model) }, enabled = !vm.reviewBusy, modifier = Modifier.fillMaxWidth()) {
            Text(if (vm.reviewBusy) "Reviewing…" else "Review ${sel.size} file(s)")
        }
        if (vm.reviewBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
        vm.reviewError?.let { Text(it, color = Color(0xFFFF8A80), fontSize = 13.sp) }
        if (vm.reviewResult.isBlank() && !vm.reviewBusy && vm.reviewError == null) Box(Modifier.weight(1f)) { Empty("Pick files and tap Review to get an LLM code review.") }
        else SelectionContainer { Text(vm.reviewResult, Modifier.weight(1f).verticalScroll(rememberScrollState()), fontSize = 13.sp) }
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
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { if (key.isNotBlank()) vm.store.apiKey = key; vm.store.baseUrl = base.ifBlank { SecureStore.DEFAULT_BASE }; vm.loadModels() }) { Text("Save & fetch") }
            Row(verticalAlignment = Alignment.CenterVertically) { Switch(vm.store.stream, { vm.store.stream = it }); Text("Stream", fontSize = 12.sp) }
        }
        Text("Current: ${vm.store.model}", fontSize = 12.sp, color = Color(0xFF80CBC4))
        vm.modelsError?.let { Text(it, color = Color(0xFFFF8A80), fontSize = 12.sp) }
        OutlinedTextField(search, { search = it }, label = { Text("Search models") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        val list = vm.models.filter { it.id.contains(search, true) }.sortedByDescending { it.id in fav }
        if (list.isEmpty()) Box(Modifier.weight(1f)) { Empty(if (vm.store.apiKey.isBlank()) "Enter your API key and tap Save & fetch." else "Tap Save & fetch to load models.") }
        else LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
fun MoreTab(vm: AgentViewModel, askPerms: () -> Unit) {
    val ctx = LocalContext.current
    val convs by vm.conversations().collectAsState(initial = emptyList())
    fun open(a: String) = ctx.startActivity(Intent(a).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    Column(Modifier.fillMaxSize().systemBarsPadding().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Permissions & access", style = MaterialTheme.typography.titleMedium)
        Button(onClick = askPerms, Modifier.fillMaxWidth()) { Text("Request runtime permissions") }
        OutlinedButton(onClick = { open(Settings.ACTION_ACCESSIBILITY_SETTINGS) }, Modifier.fillMaxWidth()) { Text("Enable Accessibility (UI actions)") }
        OutlinedButton(onClick = { open("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS") }, Modifier.fillMaxWidth()) { Text("Enable Notification Listener") }
        OutlinedButton(onClick = { open(Settings.ACTION_MANAGE_WRITE_SETTINGS) }, Modifier.fillMaxWidth()) { Text("Allow WRITE_SETTINGS (brightness)") }
        OutlinedButton(onClick = { open(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS) }, Modifier.fillMaxWidth()) { Text("Allow DND access") }
        Divider()
        Text("Shell backend", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("sandbox", "root").forEach { mode -> FilterChip(selected = vm.store.shellMode == mode, onClick = { vm.store.shellMode = mode }, label = { Text(mode) }) }
        }
        Text("Agent max steps: ${vm.maxSteps}", style = MaterialTheme.typography.titleMedium)
        Slider(vm.maxSteps.toFloat(), { vm.maxSteps = it.toInt() }, valueRange = 1f..30f)
        Divider()
        Text("Remote (DLNA / Android TV)", style = MaterialTheme.typography.titleMedium)
        Button(onClick = { vm.send("Discover devices on my local Wi-Fi (mDNS and SSDP) and list TVs and speakers with control URLs.") }, Modifier.fillMaxWidth()) { Text("Discover devices") }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("DPAD_UP" to "▲", "DPAD_DOWN" to "▼", "DPAD_CENTER" to "OK", "VOLUME_UP" to "V+", "VOLUME_DOWN" to "V-").forEach { (c, l) ->
                OutlinedButton(onClick = { vm.send("On the Android TV I discovered, send key $c via adbtv.") }) { Text(l) }
            }
        }
        Divider()
        Text("History", style = MaterialTheme.typography.titleMedium)
        Button(onClick = { vm.startNew() }) { Text("New chat") }
        if (convs.isEmpty()) Text("No saved chats yet.", fontSize = 12.sp, color = Color(0xFF6B6B70))
        convs.forEach { c -> Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(10.dp)) { Text(c.title, fontSize = 14.sp); Text(c.model, fontSize = 10.sp, color = Color.Gray) } } }
    }
}

private fun runCommandFor(path: String): String = when {
    path.endsWith(".py") -> "python \"${path.substringAfterLast('/')}\" 2>&1 || sh \"${path.substringAfterLast('/')}\""
    path.endsWith(".js") -> "node \"${path.substringAfterLast('/')}\""
    path.endsWith(".sh") -> "sh \"${path.substringAfterLast('/')}\""
    else -> "cat \"${path.substringAfterLast('/')}\""
}

