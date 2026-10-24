package com.autodrops.agent

import android.Manifest
import android.content.Intent
import android.net.Uri
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

private val MxGreen = Color(0xFF00FF66)
private val MxDim = Color(0xFF0A3A1A)
private val MxBg = Color(0xFF000000)
private val mono = FontFamily.Monospace

class MainActivity : ComponentActivity() {
    private val perms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val shared = if (intent?.action == Intent.ACTION_SEND) intent.getStringExtra(Intent.EXTRA_TEXT) else null
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = MxGreen, secondary = MxGreen, background = MxBg,
                surface = Color(0xFF07140C), onPrimary = MxBg, onSurface = MxGreen, onBackground = MxGreen)) {
                Surface(Modifier.fillMaxSize(), color = MxBg) { Root(shared = shared) { askPerms() } }
            }
        }
    }
    fun askPerms() {
        val p = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.READ_CONTACTS)
        if (Build.VERSION.SDK_INT >= 33) p += Manifest.permission.NEARBY_WIFI_DEVICES
        perms.launch(p.toTypedArray())
    }
}

@Composable fun Empty(text: String) = Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
    Text(text, color = MxGreen.copy(alpha = 0.55f), fontSize = 14.sp, fontFamily = mono, modifier = Modifier.padding(32.dp))
}

@Composable
fun Root(vm: AgentViewModel = viewModel(), shared: String?, askPerms: () -> Unit) {
    var tab by remember { mutableStateOf(0) }
    LaunchedEffect(shared) { if (!shared.isNullOrBlank()) { vm.send(shared); tab = 0 } }
    LaunchedEffect(Unit) { vm.loadCatalog() }
    val tabs = listOf("Чат" to Icons.Default.Chat, "Моделі" to Icons.Default.Memory, "Інструменти" to Icons.Default.Build,
        "Термінал/Код" to Icons.Default.Code, "Мережа" to Icons.Default.Wifi, "Налашт." to Icons.Default.Settings)
    Box(Modifier.fillMaxSize()) {
        MatrixRain(vm.matrixRain, Modifier.fillMaxSize())
        Scaffold(containerColor = Color.Transparent, bottomBar = {
            NavigationBar(containerColor = Color(0xFF04120A)) {
                tabs.forEachIndexed { i, (label, icon) ->
                    NavigationBarItem(selected = tab == i, onClick = { tab = i },
                        icon = { Icon(icon, label, tint = if (tab == i) MxGreen else MxGreen.copy(alpha = 0.5f)) },
                        label = { Text(label, fontSize = 9.sp, fontFamily = mono) },
                        colors = NavigationBarItemDefaults.colors(indicatorColor = MxDim))
                }
            }
        }) { pad ->
            Box(Modifier.padding(pad)) {
                when (tab) {
                    0 -> ChatTab(vm); 1 -> ModelsTab(vm); 2 -> ToolsTab(vm, askPerms)
                    3 -> DevTab(vm); 4 -> NetworkTab(vm); else -> SettingsTab(vm)
                }
            }
        }
    }
    vm.pending?.let { p ->
        AlertDialog(onDismissRequest = {}, containerColor = Color(0xFF07140C), titleContentColor = MxGreen, textContentColor = MxGreen,
            icon = { Icon(Icons.Default.Warning, null, tint = MxGreen) },
            title = { Text("Підтвердити: ${p.name}  [${p.risk}]", fontFamily = mono) },
            text = { Column {
                Text("Хост провайдера: ${Security.checkHost(vm.store.baseUrl).host ?: "?"}", fontSize = 11.sp, color = MxGreen.copy(alpha = 0.7f))
                SelectionContainer { Text(sanitizeForDisplay(p.preview), fontFamily = mono, fontSize = 12.sp,
                    modifier = Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState())) }
                if (p.risk != Risk.ELEVATED) Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(vm.autoApproveSession, { vm.autoApproveSession = it }); Text("Авто-дозвіл чутливих читань цю сесію", fontSize = 12.sp, fontFamily = mono)
                }
            } },
            confirmButton = { Button(onClick = { vm.resolvePending(true) }, colors = ButtonDefaults.buttonColors(containerColor = MxGreen, contentColor = MxBg)) { Text("Дозволити") } },
            dismissButton = { TextButton(onClick = { vm.resolvePending(false) }) { Text("Відхилити", color = MxGreen) } })
    }
}

@Composable fun BigButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) =
    Button(onClick, modifier.heightIn(min = 52.dp), enabled = enabled,
        colors = ButtonDefaults.buttonColors(containerColor = MxDim, contentColor = MxGreen),
        shape = RoundedCornerShape(8.dp)) { Text(text, fontFamily = mono, fontSize = 15.sp) }

@Composable fun Head(t: String) = Text(t, color = MxGreen, fontFamily = mono, fontSize = 20.sp, modifier = Modifier.padding(vertical = 6.dp))

@Composable
fun ChatTab(vm: AgentViewModel) {
    var input by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().systemBarsPadding().imePadding().padding(horizontal = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) { Text("DRON.exe", color = MxGreen, fontFamily = mono, fontSize = 18.sp, modifier = Modifier.weight(1f).padding(8.dp)) }
        if (vm.items.isEmpty()) Box(Modifier.weight(1f)) { Empty("> Запитай агента: переглянь телефон, скануй мережу, знайди ТВ або напиши й запусти код.") }
        else LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp), reverseLayout = true) {
            items(vm.items.reversed(), key = { it.id }) { m ->
                if (m.card != null) ToolCardView(m.card) else {
                    val bg = when (m.role) { "user" -> Color(0xFF0B2A16); "error" -> Color(0xFF3A0E0E); "info" -> Color(0xFF2A2A0E); else -> Color(0xFF07140C) }
                    SelectionContainer { Text(m.text.ifBlank { "…" }, Modifier.fillMaxWidth().background(bg, RoundedCornerShape(8.dp)).padding(10.dp), fontSize = 15.sp, color = MxGreen, fontFamily = mono) }
                }
            }
        }
        if (vm.busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = MxGreen)
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(input, { input = it }, Modifier.weight(1f), placeholder = { Text("> повідомлення…", fontFamily = mono) }, maxLines = 4,
                textStyle = androidx.compose.ui.text.TextStyle(fontFamily = mono, color = MxGreen))
            if (vm.busy) IconButton(onClick = { vm.stop() }) { Icon(Icons.Default.Stop, "stop", tint = MxGreen) }
            else Button(onClick = { vm.send(input); input = "" }, Modifier.padding(start = 6.dp), colors = ButtonDefaults.buttonColors(containerColor = MxGreen, contentColor = MxBg)) { Icon(Icons.Default.Send, "send") }
        }
    }
}

@Composable
fun ToolCardView(card: ToolCard) {
    val color = when (card.status) { "done" -> Color(0xFF0B2A16); "denied" -> Color(0xFF3A0E0E); "awaiting approval" -> Color(0xFF2A2A0E); else -> Color(0xFF07140C) }
    Column(Modifier.fillMaxWidth().background(color, RoundedCornerShape(8.dp)).padding(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (card.status == "running" || card.status == "awaiting approval") CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 2.dp, color = MxGreen)
            Text("  🛠 ${card.name} · ${card.status}", fontSize = 13.sp, color = MxGreen, fontFamily = mono)
        }
        if (card.preview.isNotBlank()) Text(sanitizeForDisplay(card.preview), fontFamily = mono, fontSize = 11.sp, color = MxGreen.copy(alpha = 0.75f))
        if (card.output.isNotBlank()) SelectionContainer { Text(sanitizeForDisplay(card.output.take(4000)), fontFamily = mono, fontSize = 11.sp, color = MxGreen.copy(alpha = 0.9f), modifier = Modifier.padding(top = 4.dp)) }
    }
}

@Composable
fun ModelsTab(vm: AgentViewModel) {
    var key by remember { mutableStateOf("") }
    var base by remember { mutableStateOf(vm.store.baseUrl) }
    var cat by remember { mutableStateOf("top") }
    var search by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf<String?>(null) }
    val hv = Security.checkHost(base)
    Column(Modifier.fillMaxSize().systemBarsPadding().padding(12.dp)) {
        Head("Моделі")
        OutlinedTextField(base, { base = it }, label = { Text("Base URL (https)", fontFamily = mono) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            textStyle = androidx.compose.ui.text.TextStyle(fontFamily = mono, color = MxGreen))
        hv.warning?.let { Text("⚠ $it", color = Color(0xFFFFC107), fontSize = 11.sp, fontFamily = mono) }
        OutlinedTextField(key, { key = it }, label = { Text(if (vm.store.apiKey.isBlank()) "API ключ" else "API ключ (збережено; порожнє = без змін)", fontFamily = mono) },
            visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth(),
            textStyle = androidx.compose.ui.text.TextStyle(fontFamily = mono, color = MxGreen))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BigButton("Зберегти", { if (key.isNotBlank()) vm.store.apiKey = key; vm.store.baseUrl = base.ifBlank { SecureStore.DEFAULT_BASE }; vm.loadModels() })
            BigButton(stringRes(vm, "Оновити"), { vm.loadCatalog(forceLive = true) })
        }
        Text("Поточна: ${vm.store.model}", fontSize = 12.sp, color = MxGreen, fontFamily = mono)
        if (vm.catalogBusy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = MxGreen)
        // category chips
        LazyRowChips(ModelCatalog.CATEGORIES.map { it.id to it.uaTitle }, cat) { cat = it }
        Text(ModelCatalog.CATEGORIES.first { it.id == cat }.uaHint, fontSize = 11.sp, color = MxGreen.copy(alpha = 0.6f), fontFamily = mono)
        OutlinedTextField(search, { search = it }, label = { Text("Пошук", fontFamily = mono) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            textStyle = androidx.compose.ui.text.TextStyle(fontFamily = mono, color = MxGreen))
        val list = ModelCatalog.categorize(vm.catalog, cat).filter { it.id.contains(search, true) || it.name.contains(search, true) }
        if (list.isEmpty()) Box(Modifier.weight(1f)) { Empty("Немає моделей у цій категорії. Натисни «Оновити».") }
        else LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(list, key = { it.id }) { m ->
                Card(Modifier.fillMaxWidth().clickable { expanded = if (expanded == m.id) null else m.id },
                    colors = CardDefaults.cardColors(containerColor = if (m.id == vm.store.model) Color(0xFF0B2A16) else Color(0xFF07140C))) {
                    Column(Modifier.padding(10.dp)) {
                        Text(m.id, fontSize = 13.sp, color = MxGreen, fontFamily = mono)
                        if (expanded == m.id) {
                            Text(ModelCatalog.describeUa(m), fontSize = 12.sp, color = MxGreen.copy(alpha = 0.85f), fontFamily = mono, modifier = Modifier.padding(top = 6.dp))
                            BigButton("Обрати цю модель", { vm.store.model = m.id }, Modifier.padding(top = 6.dp))
                        } else Text("ctx ${m.ctx} · in ${m.inPrice} · out ${m.outPrice}", fontSize = 10.sp, color = MxGreen.copy(alpha = 0.6f), fontFamily = mono)
                    }
                }
            }
        }
        vm.modelsError?.let { Text(it, color = Color(0xFFFF6E6E), fontSize = 12.sp, fontFamily = mono) }
    }
}

@Composable fun stringRes(vm: AgentViewModel, s: String) = s

@Composable
fun LazyRowChips(items: List<Pair<String, String>>, selected: String, onSel: (String) -> Unit) {
    androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 6.dp)) {
        items(items) { (id, label) ->
            FilterChip(selected = selected == id, onClick = { onSel(id) }, label = { Text(label, fontFamily = mono, fontSize = 12.sp) },
                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = MxDim, selectedLabelColor = MxGreen, labelColor = MxGreen.copy(alpha = 0.6f)))
        }
    }
}

@Composable
fun ToolsTab(vm: AgentViewModel, askPerms: () -> Unit) {
    val ctx = LocalContext.current
    var reviewMode by remember { mutableStateOf(false) }
    val recipes by vm.recipes().collectAsState(initial = emptyList())
    fun open(a: String) = ctx.startActivity(Intent(a).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    fun web(url: String) = ctx.startActivity(Intent(ctx, WebChatActivity::class.java).putExtra("url", url))
    if (reviewMode) { ReviewContent(vm) { reviewMode = false }; return }
    Column(Modifier.fillMaxSize().systemBarsPadding().padding(12.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Head("Інструменти")
        Text("Веб-вхід (офіційні сайти у WebView, без скрапінгу токенів)", fontFamily = mono, fontSize = 12.sp, color = MxGreen.copy(alpha = 0.7f))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            BigButton("ChatGPT", { web("https://chatgpt.com") }, Modifier.weight(1f))
            BigButton("Grok", { web("https://grok.com") }, Modifier.weight(1f))
            BigButton("Gemini", { web("https://gemini.google.com") }, Modifier.weight(1f))
        }
        Divider(color = MxDim)
        BigButton("Код-рев'ю (огляд коду)", { reviewMode = true }, Modifier.fillMaxWidth())
        Divider(color = MxDim)
        Text("Дозволи та доступи", fontFamily = mono, color = MxGreen)
        BigButton("Запросити runtime-дозволи", askPerms, Modifier.fillMaxWidth())
        BigButton("Accessibility (дії в UI)", { open(Settings.ACTION_ACCESSIBILITY_SETTINGS) }, Modifier.fillMaxWidth())
        BigButton("Слухач сповіщень", { open("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS") }, Modifier.fillMaxWidth())
        BigButton("WRITE_SETTINGS (яскравість)", { open(Settings.ACTION_MANAGE_WRITE_SETTINGS) }, Modifier.fillMaxWidth())
        BigButton("Доступ до DND", { open(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS) }, Modifier.fillMaxWidth())
        Divider(color = MxDim)
        Text("Рецепти (збережені запити)", fontFamily = mono, color = MxGreen)
        if (recipes.isEmpty()) Text("Поки немає. Збережи запит із чату.", fontSize = 12.sp, color = MxGreen.copy(alpha = 0.6f), fontFamily = mono)
        recipes.forEach { r -> Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFF07140C))) {
            Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text(r.name, color = MxGreen, fontFamily = mono, fontSize = 13.sp); Text(r.prompt.take(60), color = MxGreen.copy(alpha = 0.6f), fontSize = 10.sp, fontFamily = mono) }
                TextButton(onClick = { vm.send(r.prompt) }) { Text("▶", color = MxGreen) }
                TextButton(onClick = { vm.deleteRecipe(r.id) }) { Text("✕", color = MxGreen) }
            }
        } }
    }
}

@Composable
fun ReviewContent(vm: AgentViewModel, onBack: () -> Unit) {
    var files by remember { mutableStateOf(emptyList<String>()) }
    var sel by remember { mutableStateOf(setOf<String>()) }
    LaunchedEffect(Unit) { vm.seedSampleProject(); files = vm.workspaceFiles() }
    Column(Modifier.fillMaxSize().systemBarsPadding().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) { TextButton(onClick = onBack) { Text("‹ назад", color = MxGreen, fontFamily = mono) }; Head("Код-рев'ю") }
        Text("Рев'юер: ${vm.store.model}", fontSize = 12.sp, color = MxGreen.copy(alpha = 0.7f), fontFamily = mono)
        if (files.isEmpty()) Text("Немає файлів у робочій теці.", fontSize = 12.sp, color = MxGreen.copy(alpha = 0.6f), fontFamily = mono)
        LazyColumn(Modifier.heightIn(max = 160.dp)) {
            items(files, key = { it }) { f -> Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(f in sel, { sel = if (f in sel) sel - f else sel + f }); Text(f, fontFamily = mono, fontSize = 12.sp, color = MxGreen) } }
        }
        BigButton(if (vm.reviewBusy) "Аналізую…" else "Переглянути ${sel.size} файл(ів)", { vm.runReview(sel.toList(), vm.store.model) }, Modifier.fillMaxWidth(), enabled = !vm.reviewBusy)
        if (vm.reviewBusy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = MxGreen)
        vm.reviewError?.let { Text(it, color = Color(0xFFFF6E6E), fontSize = 13.sp, fontFamily = mono) }
        if (vm.reviewResult.isBlank() && !vm.reviewBusy && vm.reviewError == null) Box(Modifier.weight(1f)) { Empty("Обери файли й натисни Переглянути.") }
        else SelectionContainer { Text(vm.reviewResult, Modifier.weight(1f).verticalScroll(rememberScrollState()), fontSize = 13.sp, color = MxGreen, fontFamily = mono) }
    }
}

@Composable
fun DevTab(vm: AgentViewModel) {
    var files by remember { mutableStateOf(emptyList<String>()) }
    var selected by remember { mutableStateOf<String?>(null) }
    var content by remember { mutableStateOf("") }
    var dirty by remember { mutableStateOf(false) }
    var term by remember { mutableStateOf("") }
    var cmd by remember { mutableStateOf("") }
    fun refresh() { vm.seedSampleProject(); files = vm.workspaceFiles() }
    LaunchedEffect(Unit) { refresh() }
    Column(Modifier.fillMaxSize().systemBarsPadding().imePadding().padding(8.dp)) {
        Head("Термінал / Код")
        Text("Файли", fontSize = 12.sp, color = MxGreen.copy(alpha = 0.6f), fontFamily = mono)
        if (files.isEmpty()) Text("Порожньо. Створи файл у терміналі: echo hi > a.txt", fontSize = 12.sp, color = MxGreen.copy(alpha = 0.5f), fontFamily = mono)
        LazyColumn(Modifier.heightIn(max = 110.dp)) {
            items(files, key = { it }) { f -> Text(f, Modifier.fillMaxWidth().background(if (f == selected) Color(0xFF0B2A16) else Color.Transparent)
                .clickable { selected = f; content = vm.readFile(f); dirty = false }.padding(6.dp), fontFamily = mono, fontSize = 12.sp, color = MxGreen) }
        }
        Divider(Modifier.padding(vertical = 4.dp), color = MxDim)
        if (selected == null) Text("Обери файл для редагування.", fontSize = 12.sp, color = MxGreen.copy(alpha = 0.5f), fontFamily = mono)
        else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(selected!!, Modifier.weight(1f), fontFamily = mono, fontSize = 12.sp, color = MxGreen)
                if (dirty) Text("●", color = Color(0xFFFFC107))
                TextButton(onClick = { vm.writeFile(selected!!, content); dirty = false }) { Text("Зберегти", color = MxGreen, fontFamily = mono) }
                TextButton(onClick = { vm.runTerminal(runCommandFor(selected!!), true) { term = it } }) { Text("Запуск", color = MxGreen, fontFamily = mono) }
            }
            OutlinedTextField(content, { content = it; dirty = true }, Modifier.fillMaxWidth().heightIn(min = 100.dp, max = 220.dp),
                textStyle = androidx.compose.ui.text.TextStyle(fontFamily = mono, fontSize = 12.sp, color = MxGreen),
                visualTransformation = { androidx.compose.ui.text.input.TransformedText(Highlight.render(it.text), androidx.compose.ui.text.input.OffsetMapping.Identity) })
        }
        Divider(Modifier.padding(vertical = 4.dp), color = MxDim)
        Text("Термінал (${vm.store.shellMode})", fontSize = 12.sp, color = MxGreen.copy(alpha = 0.6f), fontFamily = mono)
        SelectionContainer { Text(term.ifBlank { "> вивід тут." }, Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).background(Color(0xFF02060300)).padding(6.dp),
            fontFamily = mono, fontSize = 11.sp, color = MxGreen.copy(alpha = 0.9f)) }
        if (vm.termBusy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = MxGreen)
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(cmd, { cmd = it }, Modifier.weight(1f), placeholder = { Text("$ команда", fontFamily = mono) }, singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(fontFamily = mono, color = MxGreen))
            Button(onClick = { vm.runTerminal(cmd, true) { term += "\n$ $cmd\n$it"; refresh() }; cmd = "" }, enabled = !vm.termBusy, modifier = Modifier.padding(start = 6.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MxGreen, contentColor = MxBg)) { Text("Run", fontFamily = mono) }
        }
    }
}

@Composable
fun NetworkTab(vm: AgentViewModel) {
    Column(Modifier.fillMaxSize().systemBarsPadding().padding(12.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Head("Мережа / Пристрої")
        BigButton("Знайти пристрої (mDNS + SSDP)", { vm.send("Знайди пристрої в моїй локальній мережі (mDNS і SSDP) та покажи ТВ і колонки з control URL.") }, Modifier.fillMaxWidth())
        BigButton("Звіт безпеки мережі", { vm.send("Запусти security_report і поясни результати простими словами.") }, Modifier.fillMaxWidth())
        BigButton("Інфо Wi-Fi + локальні IP", { vm.send("Покажи network_info зі скануванням Wi-Fi.") }, Modifier.fillMaxWidth())
        Divider(color = MxDim)
        Text("Пульт Android TV (ADB по мережі)", fontFamily = mono, color = MxGreen)
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            BigButton("▲", { vm.send("На Android TV, який я знайшов, надішли клавішу DPAD_UP через adbtv.") }, Modifier.width(90.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                BigButton("◀", { vm.send("adbtv DPAD_LEFT на моєму ТВ.") }, Modifier.width(70.dp))
                BigButton("OK", { vm.send("adbtv DPAD_CENTER на моєму ТВ.") }, Modifier.width(70.dp))
                BigButton("▶", { vm.send("adbtv DPAD_RIGHT на моєму ТВ.") }, Modifier.width(70.dp))
            }
            BigButton("▼", { vm.send("adbtv DPAD_DOWN на моєму ТВ.") }, Modifier.width(90.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                BigButton("Гучн.+", { vm.send("adbtv VOLUME_UP на моєму ТВ.") })
                BigButton("Гучн.-", { vm.send("adbtv VOLUME_DOWN на моєму ТВ.") })
                BigButton("Home", { vm.send("adbtv HOME на моєму ТВ.") })
            }
        }
    }
}

@Composable
fun SettingsTab(vm: AgentViewModel) {
    val convs by vm.conversations().collectAsState(initial = emptyList())
    Column(Modifier.fillMaxSize().systemBarsPadding().padding(12.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Head("Налаштування")
        Row(verticalAlignment = Alignment.CenterVertically) { Switch(vm.matrixRain, { vm.toggleMatrixRain(it) }); Text("Matrix «цифровий дощ»", color = MxGreen, fontFamily = mono) }
        Row(verticalAlignment = Alignment.CenterVertically) { Switch(vm.store.stream, { vm.store.stream = it }); Text("Потокові відповіді (stream)", color = MxGreen, fontFamily = mono) }
        Text("Мова: українська (типово), англійська доступна у системних налаштуваннях.", fontSize = 12.sp, color = MxGreen.copy(alpha = 0.6f), fontFamily = mono)
        Divider(color = MxDim)
        Text("Виконання shell", fontFamily = mono, color = MxGreen)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("sandbox", "root").forEach { mode -> FilterChip(selected = vm.store.shellMode == mode, onClick = { vm.store.shellMode = mode }, label = { Text(mode, fontFamily = mono) },
                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = MxDim, selectedLabelColor = MxGreen, labelColor = MxGreen.copy(alpha = 0.6f))) }
        }
        Text("Кроків агента: ${vm.maxSteps}", fontFamily = mono, color = MxGreen)
        Slider(vm.maxSteps.toFloat(), { vm.maxSteps = it.toInt() }, valueRange = 1f..30f, colors = SliderDefaults.colors(thumbColor = MxGreen, activeTrackColor = MxGreen))
        Divider(color = MxDim)
        Text("Безпека: ключ шифрується (Keystore), надсилається лише на HTTPS; незнайомий хост попереджає. Вивід інструментів фільтрується від секретів.", fontSize = 11.sp, color = MxGreen.copy(alpha = 0.6f), fontFamily = mono)
        Divider(color = MxDim)
        Text("Історія", fontFamily = mono, color = MxGreen)
        BigButton("Новий чат", { vm.startNew() })
        if (convs.isEmpty()) Text("Немає збережених чатів.", fontSize = 12.sp, color = MxGreen.copy(alpha = 0.5f), fontFamily = mono)
        convs.take(20).forEach { c -> Text("• ${c.title}", color = MxGreen.copy(alpha = 0.8f), fontSize = 12.sp, fontFamily = mono) }
    }
}

private fun runCommandFor(path: String): String = when {
    path.endsWith(".py") -> "python \"${path.substringAfterLast('/')}\" 2>&1 || sh \"${path.substringAfterLast('/')}\""
    path.endsWith(".js") -> "node \"${path.substringAfterLast('/')}\""
    path.endsWith(".sh") -> "sh \"${path.substringAfterLast('/')}\""
    else -> "cat \"${path.substringAfterLast('/')}\""
}
