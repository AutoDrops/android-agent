package com.autodrops.agent

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
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
import kotlinx.coroutines.launch

private val MxGreen = Color(0xFF00FF66)
private val MxDim = Color(0xFF0A3A1A)
private val MxBg = Color(0xFF000000)
private val mono = FontFamily.Monospace

class MainActivity : ComponentActivity() {
    private val perms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runCatching { com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(applicationContext) }
        val shared = if (intent?.action == Intent.ACTION_SEND) intent.getStringExtra(Intent.EXTRA_TEXT) else null
        val tileAction = intent?.getStringExtra("tile_action")
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = MxGreen, secondary = MxGreen, background = MxBg,
                surface = Color(0xFF07140C), onPrimary = MxBg, onSurface = MxGreen, onBackground = MxGreen)) {
                Surface(Modifier.fillMaxSize(), color = MxBg) { Root(shared = shared, tileAction = tileAction) { askPerms() } }
            }
        }
    }
    fun askPerms() {
        val p = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.READ_CONTACTS, Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) p += Manifest.permission.NEARBY_WIFI_DEVICES
        perms.launch(p.toTypedArray())
    }
}

@Composable fun Empty(text: String) = Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
    Text(text, color = MxGreen.copy(alpha = 0.55f), fontSize = 14.sp, fontFamily = mono, modifier = Modifier.padding(32.dp))
}

@Composable
fun Root(vm: AgentViewModel = viewModel(), shared: String?, tileAction: String? = null, askPerms: () -> Unit) {
    var tab by remember { mutableStateOf(0) }
    var palette by remember { mutableStateOf(false) }
    val ctx = LocalContext.current
    LaunchedEffect(shared) { if (!shared.isNullOrBlank()) { vm.send(shared); tab = 0 } }
    LaunchedEffect(tileAction) {
        if (tileAction == "quick_screen") {
            tab = 0
            ctx.startActivity(Intent(ctx, ScreenCaptureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            vm.awaitScreenTextThenPrefill()
        }
    }
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
                    0 -> ChatTab(vm, onOpenModels = { tab = 1 }, onPalette = { palette = true }); 1 -> ModelsTab(vm) { tab = 0 }; 2 -> ToolsTab(vm, askPerms)
                    3 -> DevTab(vm); 4 -> NetworkTab(vm); else -> SettingsTab(vm)
                }
            }
        }
    }
    if (palette) CommandPalette(vm, onDismiss = { palette = false }, onNav = { tab = it; palette = false })
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
fun ChatTab(vm: AgentViewModel, onOpenModels: () -> Unit, onPalette: () -> Unit) {
    var input by remember { mutableStateOf("") }
    LaunchedEffect(vm.quickPrefill) { vm.quickPrefill?.let { input = it; vm.quickPrefill = null } }
    Column(Modifier.fillMaxSize().systemBarsPadding().imePadding().padding(horizontal = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(8.dp)) {
            IconButton(onClick = onPalette) { Icon(Icons.Default.Menu, "palette", tint = MxGreen) }
            Text("DRON.exe", Modifier.clickable { onOpenModels() }, color = MxGreen, fontFamily = mono, fontSize = 18.sp)
            Spacer(Modifier.weight(1f))
            if (vm.sessionUsage.totalTokens > 0) Text(vm.sessionUsage.label(), color = MxGreen.copy(alpha = 0.6f), fontFamily = mono, fontSize = 10.sp, modifier = Modifier.padding(end = 6.dp))
            Text("▾ ${vm.store.model.substringAfterLast('/')}", Modifier.clickable { onOpenModels() }, color = MxGreen.copy(alpha = 0.8f), fontFamily = mono, fontSize = 12.sp)
        }
        PlanPanel()
        if (vm.items.isEmpty()) Box(Modifier.weight(1f)) { Empty("> Запитай агента: переглянь телефон, скануй мережу, знайди ТВ або напиши й запусти код.") }
        else LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp), reverseLayout = true) {
            items(vm.items.reversed(), key = { it.id }) { m ->
                if (m.card != null) ToolCardView(m.card)
                else if (m.role == "error") ErrorCard(m.text)
                else {
                    val bg = when (m.role) { "user" -> Color(0xFF0B2A16); "info" -> Color(0xFF2A2A0E); else -> Color(0xFF07140C) }
                    Column {
                        SelectionContainer { Text(m.text.ifBlank { "…" }, Modifier.fillMaxWidth().background(bg, RoundedCornerShape(8.dp)).padding(10.dp), fontSize = 15.sp, color = MxGreen, fontFamily = mono) }
                        m.usage?.let { Text(it.label(), color = MxGreen.copy(alpha = 0.5f), fontFamily = mono, fontSize = 10.sp, modifier = Modifier.padding(start = 4.dp, top = 2.dp)) }
                    }
                }
            }
        }
        if (vm.busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = MxGreen)
        var listening by remember { mutableStateOf(false) }
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconToggleButton(checked = vm.ragEnabled, onCheckedChange = { vm.ragEnabled = it }) {
                Icon(Icons.Default.MenuBook, "chat with notes", tint = if (vm.ragEnabled) Color(0xFFFFC107) else MxGreen.copy(alpha = 0.6f))
            }
            IconButton(onClick = {
                if (listening) { vm.voice.stop(); listening = false }
                else { listening = true; vm.startVoice(onPartial = { input = it }, onFinal = { input = it; listening = false }, onError = { msg -> listening = false; vm.addInfo("Мікрофон: $msg") }) }
            }) { Icon(if (listening) Icons.Default.MicOff else Icons.Default.Mic, "voice", tint = if (listening) Color(0xFFFFC107) else MxGreen) }
            OutlinedTextField(input, { input = it }, Modifier.weight(1f), placeholder = { Text("> повідомлення…", fontFamily = mono) }, maxLines = 4,
                textStyle = androidx.compose.ui.text.TextStyle(fontFamily = mono, color = MxGreen))
            if (vm.localBusy) IconButton(onClick = { vm.stopLocal() }) { Icon(Icons.Default.Stop, "stopLocal", tint = Color(0xFFFFC107)) }
            else if (vm.localReady()) IconButton(onClick = { val q = input; input = ""; vm.askLocal(q) }) {
                Icon(Icons.Default.OfflineBolt, "offline", tint = MxGreen)
            }
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
fun ModelsTab(vm: AgentViewModel, onPicked: () -> Unit) {
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
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (vm.store.apiKey.isNotBlank()) Text("Ключ: збережено ✓", fontSize = 11.sp, color = MxGreen.copy(alpha = 0.7f), fontFamily = mono, modifier = Modifier.align(Alignment.CenterVertically))
            else Text("Ключ: відсутній", fontSize = 11.sp, color = Color(0xFFFFC107), fontFamily = mono, modifier = Modifier.align(Alignment.CenterVertically))
            TextButton(onClick = { vm.store.clearApiKey(); key = "" }) { Text("Очистити ключ", color = MxGreen, fontFamily = mono, fontSize = 12.sp) }
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
                // One tap: set active model and go to Chat. Long-press (info ▸) toggles the full description.
                Card(Modifier.fillMaxWidth().clickable { vm.store.model = m.id; onPicked() },
                    colors = CardDefaults.cardColors(containerColor = if (m.id == vm.store.model) Color(0xFF0B2A16) else Color(0xFF07140C))) {
                    Column(Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(m.id, Modifier.weight(1f), fontSize = 16.sp, color = MxGreen, fontFamily = mono)
                            TextButton(onClick = { expanded = if (expanded == m.id) null else m.id }) { Text(if (expanded == m.id) "▴" else "ℹ", color = MxGreen) }
                        }
                        Text("ctx ${m.ctx} · in ${m.inPrice} · out ${m.outPrice}", fontSize = 11.sp, color = MxGreen.copy(alpha = 0.6f), fontFamily = mono)
                        if (expanded == m.id) Text(ModelCatalog.describeUa(m), fontSize = 12.sp, color = MxGreen.copy(alpha = 0.85f), fontFamily = mono, modifier = Modifier.padding(top = 6.dp))
                        if (m.id == vm.store.model) Text("● активна — тап, щоб відкрити чат", fontSize = 11.sp, color = MxGreen, fontFamily = mono, modifier = Modifier.padding(top = 4.dp))
                        else Text("Тап = обрати і перейти в чат", fontSize = 10.sp, color = MxGreen.copy(alpha = 0.5f), fontFamily = mono, modifier = Modifier.padding(top = 4.dp))
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
        Divider(color = MxDim)
        Text("База знань (локальний RAG)", fontFamily = mono, color = MxGreen)
        var ragTitle by remember { mutableStateOf("") }
        var ragBody by remember { mutableStateOf("") }
        val ragDocs by vm.ragDocs().collectAsState(initial = emptyList())
        OutlinedTextField(ragTitle, { ragTitle = it }, Modifier.fillMaxWidth(), label = { Text("Назва", fontFamily = mono) }, singleLine = true,
            textStyle = androidx.compose.ui.text.TextStyle(fontFamily = mono, color = MxGreen))
        OutlinedTextField(ragBody, { ragBody = it }, Modifier.fillMaxWidth().heightIn(min = 60.dp), label = { Text("Текст нотатки/файлу", fontFamily = mono) },
            textStyle = androidx.compose.ui.text.TextStyle(fontFamily = mono, color = MxGreen))
        BigButton("Додати в базу знань", { if (ragBody.isNotBlank()) { vm.addRag(ragTitle, ragBody); ragTitle = ""; ragBody = "" } }, Modifier.fillMaxWidth())
        var ingestMsg by remember { mutableStateOf("") }
        val ingestScope = androidx.compose.runtime.rememberCoroutineScope()
        val fileIngest = rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) {
                ingestMsg = "Читаю файл…"
                ingestScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    runCatching {
                        val name = uri.lastPathSegment?.substringAfterLast('/') ?: "file"
                        val max = 5 * 1024 * 1024 // 5 MB cap to avoid ANR/OOM
                        val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readNBytes(max + 1) } ?: ByteArray(0)
                        if (bytes.size > max) throw IllegalArgumentException("Файл завеликий (>5 МБ).")
                        val text = PdfText.extractOrText(bytes, name)
                        if (text.isBlank()) throw IllegalArgumentException("Не вдалося прочитати текст із файлу.")
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                            vm.ingestText(name, text) { n -> ingestMsg = "Імпортовано «$name»: $n фрагмент(ів) у базу знань." }
                        }
                    }.onFailure { e -> ingestScope.launch(kotlinx.coroutines.Dispatchers.Main) { ingestMsg = "Помилка: ${e.message}" } }
                }
            }
        }
        BigButton("Імпортувати файл (txt/md/pdf)", { fileIngest.launch("*/*") }, Modifier.fillMaxWidth())
        if (ingestMsg.isNotBlank()) Text(ingestMsg, fontSize = 11.sp, color = MxGreen.copy(alpha = 0.75f), fontFamily = mono)
        if (ragDocs.isEmpty()) Text("Поки порожньо.", fontSize = 12.sp, color = MxGreen.copy(alpha = 0.6f), fontFamily = mono)
        ragDocs.take(20).forEach { d -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("• ${d.title}", Modifier.weight(1f), color = MxGreen.copy(alpha = 0.85f), fontSize = 12.sp, fontFamily = mono)
            TextButton(onClick = { vm.deleteRag(d.id) }) { Text("✕", color = MxGreen) }
        } }
        Divider(color = MxDim)
        Text("Контекст екрана (OCR)", fontFamily = mono, color = MxGreen)
        BigButton("Захопити екран (OCR)", { ctx.startActivity(Intent(ctx, ScreenCaptureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }, Modifier.fillMaxWidth())
        Text("Система попросить дозвіл на запис екрана. Захоплюється один кадр; текст доступний інструменту screen_ocr.", fontSize = 11.sp, color = MxGreen.copy(alpha = 0.6f), fontFamily = mono)
        Divider(color = MxDim)
        Text("Резервна копія (без ключів)", fontFamily = mono, color = MxGreen)
        var backupMsg by remember { mutableStateOf("") }
        val scope = androidx.compose.runtime.rememberCoroutineScope()
        val exporter = rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json")) { uri ->
            if (uri != null) scope.launch {
                runCatching {
                    val json = vm.exportBundleJson()
                    ctx.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) }
                    backupMsg = "Експортовано базу знань, правила та пристрої (ключ НЕ включено)."
                }.onFailure { backupMsg = "Помилка експорту: ${it.message}" }
            }
        }
        val importer = rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) runCatching {
                val json = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() } ?: ""
                vm.importBundleJson(json) { backupMsg = it }
            }.onFailure { backupMsg = "Помилка імпорту: ${it.message}" }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            BigButton("Експорт", { exporter.launch("dron-backup.json") }, Modifier.weight(1f))
            BigButton("Імпорт", { importer.launch("application/json") }, Modifier.weight(1f))
        }
        if (backupMsg.isNotBlank()) Text(backupMsg, fontSize = 11.sp, color = MxGreen.copy(alpha = 0.75f), fontFamily = mono)
        Divider(color = MxDim)
        Text("Агент-рецепти (спільний доступ)", fontFamily = mono, color = MxGreen)
        var recMsg by remember { mutableStateOf("") }
        val recExport = rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json")) { uri ->
            if (uri != null) scope.launch { runCatching {
                val json = vm.exportRecipesJson(); ctx.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) }
                recMsg = "Рецепти експортовано (без ключів)."
            }.onFailure { recMsg = "Помилка: ${it.message}" } }
        }
        val recImport = rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) runCatching {
                val json = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() } ?: ""
                vm.importRecipesJson(json) { recMsg = it }
            }.onFailure { recMsg = "Помилка: ${it.message}" }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            BigButton("Експорт рецептів", { recExport.launch("dron-recipes.json") }, Modifier.weight(1f))
            BigButton("Імпорт рецептів", { recImport.launch("application/json") }, Modifier.weight(1f))
        }
        if (recMsg.isNotBlank()) Text(recMsg, fontSize = 11.sp, color = MxGreen.copy(alpha = 0.75f), fontFamily = mono)
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
    var snipWarn by remember { mutableStateOf(false) }
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
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Термінал (${vm.store.shellMode})", Modifier.weight(1f), fontSize = 12.sp, color = MxGreen.copy(alpha = 0.6f), fontFamily = mono)
            vm.lastExitCode?.let { ec ->
                val okc = ec == 0
                Text("exit $ec", fontSize = 11.sp, fontFamily = mono, color = if (okc) MxGreen else Color(0xFFFF6E6E),
                    modifier = Modifier.background(Color(0xFF07140C)).padding(horizontal = 6.dp, vertical = 2.dp))
            }
        }
        // Saved snippets: tap to run, long-press ✕ to delete
        val snips = vm.snippets()
        if (snips.isNotEmpty()) LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 2.dp)) {
            items(snips, key = { it.first }) { (name, c) ->
                AssistChip(onClick = { cmd = c }, label = { Text(name, fontFamily = mono, fontSize = 11.sp) },
                    trailingIcon = { Text("✕", color = MxGreen.copy(alpha = 0.6f), modifier = Modifier.clickable { vm.deleteSnippet(name) }) },
                    colors = AssistChipDefaults.assistChipColors(containerColor = Color(0xFF07140C), labelColor = MxGreen))
            }
        }
        SelectionContainer { Text(term.ifBlank { "> вивід тут." }, Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).background(Color(0xFF02060300)).padding(6.dp),
            fontFamily = mono, fontSize = 11.sp, color = MxGreen.copy(alpha = 0.9f)) }
        if (snipWarn) Text("Не збережено: команда схожа на секрет (ключ/пароль/токен).", fontSize = 10.sp, color = Color(0xFFFFC107), fontFamily = mono)
        if (vm.termBusy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = MxGreen)
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(cmd, { cmd = it }, Modifier.weight(1f), placeholder = { Text("$ команда", fontFamily = mono) }, singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(fontFamily = mono, color = MxGreen))
            if (cmd.isNotBlank()) TextButton(onClick = { snipWarn = !vm.saveSnippet(cmd.take(16), cmd) }) { Text("＋сніпет", color = MxGreen, fontFamily = mono, fontSize = 11.sp) }
            if (vm.termBusy) Button(onClick = { vm.stopTerminal() }, modifier = Modifier.padding(start = 6.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFC107), contentColor = MxBg)) { Text("Stop", fontFamily = mono) }
            else Button(onClick = { vm.runTerminal(cmd, true) { term += "\n$ $cmd\n$it"; refresh() }; cmd = "" }, enabled = cmd.isNotBlank(), modifier = Modifier.padding(start = 6.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MxGreen, contentColor = MxBg)) { Text("Run", fontFamily = mono) }
        }
    }
}

@Composable
fun NetworkTab(vm: AgentViewModel) {
    Column(Modifier.fillMaxSize().systemBarsPadding().padding(12.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Head("Мережа / Пристрої")
        BigButton("Знайти пристрої (mDNS + SSDP)", { vm.send("Знайди пристрої в моїй локальній мережі (mDNS і SSDP) та покажи ТВ і колонки з control URL.") }, Modifier.fillMaxWidth())
        BigButton("Жива карта хостів (ping+ARP)", { vm.send("Запусти net_discover і покажи живі хости моєї підмережі з MAC та іменами.") }, Modifier.fillMaxWidth())
        BigButton("ARP-скан (хости + MAC + вендор)", { vm.send("Запусти arp_scan і покажи хости моєї підмережі з MAC-адресами та виробниками.") }, Modifier.fillMaxWidth())
        BigButton("Звіт безпеки мережі", { vm.send("Запусти security_report і поясни результати простими словами.") }, Modifier.fillMaxWidth())
        BigButton("Інфо Wi-Fi + локальні IP", { vm.send("Покажи network_info зі скануванням Wi-Fi.") }, Modifier.fillMaxWidth())
        Text("Діагностика хоста (лише приватні IP; кожен скан з підтвердженням)", fontSize = 10.sp, color = MxGreen.copy(alpha = 0.55f), fontFamily = mono)
        var diagIp by remember { mutableStateOf("") }
        var diagErr by remember { mutableStateOf(false) }
        OutlinedTextField(diagIp, { diagIp = it; diagErr = false }, Modifier.fillMaxWidth(), label = { Text("IP хоста у твоїй LAN (приватний)", fontFamily = mono) }, singleLine = true,
            textStyle = androidx.compose.ui.text.TextStyle(fontFamily = mono, color = MxGreen))
        if (diagErr) Text("Лише приватні адреси RFC1918 (10.x, 172.16-31.x, 192.168.x).", fontSize = 10.sp, color = Color(0xFFFFC107), fontFamily = mono)
        fun diag(prompt: String) { val ip = diagIp.trim(); if (NetGuard.isPrivateV4(ip)) vm.send(prompt) else diagErr = true }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            BigButton("TCP-скан", { diag("Зроби tcp_scan хоста ${diagIp.trim()} у моїй локальній мережі.") }, Modifier.weight(1f))
            BigButton("Сервіси", { diag("Покажи open_services для ${diagIp.trim()} у моїй LAN.") }, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            BigButton("Трасування", { diag("Зроби traceroute до ${diagIp.trim()}.") }, Modifier.weight(1f))
            BigButton("TLS-сертиф.", { diag("Покажи ssl_cert_info для ${diagIp.trim()}:443.") }, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            BigButton("ОС+сервіси (nmap -A)", { diag("Зроби os_detect для ${diagIp.trim()} у моїй LAN: вгадай ОС по TTL і визнач версії сервісів.") }, Modifier.weight(1f))
            BigButton("MAC → вендор", { vm.send("Запусти mac_vendor для MAC-адрес із мого ARP-скану і покажи виробників.") }, Modifier.weight(1f))
        }
        Divider(color = MxDim)
        Text("Інвентар пристроїв (збережені, з мітками)", fontFamily = mono, color = MxGreen)
        val devices by vm.devices().collectAsState(initial = emptyList())
        var dip by remember { mutableStateOf("") }
        var dlabel by remember { mutableStateOf("") }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(dip, { dip = it }, Modifier.weight(1f), label = { Text("IP (приватний)", fontFamily = mono) }, singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(fontFamily = mono, color = MxGreen))
            OutlinedTextField(dlabel, { dlabel = it }, Modifier.weight(1f), label = { Text("Мітка", fontFamily = mono) }, singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(fontFamily = mono, color = MxGreen))
        }
        BigButton("Зберегти пристрій", {
            if (NetGuard.isPrivateV4(dip.trim())) { vm.saveDevice(dip.trim(), label = dlabel.trim()); dip = ""; dlabel = "" }
        }, Modifier.fillMaxWidth())
        Text("Приймаються лише приватні адреси (RFC1918). Агент теж може наповнювати інвентар через inventory_add.", fontSize = 10.sp, color = MxGreen.copy(alpha = 0.55f), fontFamily = mono)
        if (devices.isEmpty()) Text("Інвентар порожній.", fontSize = 12.sp, color = MxGreen.copy(alpha = 0.5f), fontFamily = mono)
        devices.forEach { d -> Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFF07140C))) {
            Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(if (d.label.isBlank()) d.ip else "${d.label}", color = MxGreen, fontFamily = mono, fontSize = 14.sp)
                    Text("${d.ip}${if (d.mac.isNotBlank()) "  ·  " + d.mac else ""}", color = MxGreen.copy(alpha = 0.6f), fontFamily = mono, fontSize = 10.sp)
                }
                TextButton(onClick = { vm.deleteDevice(d.id) }) { Text("✕", color = MxGreen) }
            }
        } }
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
        Text("Проактивні правила (WorkManager)", fontFamily = mono, color = MxGreen)
        val rules by vm.rules().collectAsState(initial = emptyList())
        var rName by remember { mutableStateOf("") }
        var rTrigger by remember { mutableStateOf("charging") }
        var rParam by remember { mutableStateOf("") }
        OutlinedTextField(rName, { rName = it }, Modifier.fillMaxWidth(), label = { Text("Назва правила", fontFamily = mono) }, singleLine = true,
            textStyle = androidx.compose.ui.text.TextStyle(fontFamily = mono, color = MxGreen))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("charging" to "Заряджання", "wifi_ssid" to "Wi-Fi SSID", "interval" to "Періодично").forEach { (id, lbl) ->
                FilterChip(rTrigger == id, { rTrigger = id }, { Text(lbl, fontFamily = mono, fontSize = 11.sp) },
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = MxDim, selectedLabelColor = MxGreen, labelColor = MxGreen.copy(alpha = .6f)))
            }
        }
        if (rTrigger == "wifi_ssid") OutlinedTextField(rParam, { rParam = it }, Modifier.fillMaxWidth(), label = { Text("SSID містить…", fontFamily = mono) }, singleLine = true,
            textStyle = androidx.compose.ui.text.TextStyle(fontFamily = mono, color = MxGreen))
        BigButton("Додати правило", { if (rName.isNotBlank()) { vm.addRule(Rule(name = rName, trigger = rTrigger, param = rParam)); rName = ""; rParam = "" } }, Modifier.fillMaxWidth())
        Text("Правила працюють раз на ~15 хв (ліміт WorkManager), лише читають стан і шлють сповіщення. Дії-запити не виконуються у фоні без підтвердження у застосунку.", fontSize = 11.sp, color = MxGreen.copy(alpha = 0.6f), fontFamily = mono)
        if (rules.isEmpty()) Text("Немає правил.", fontSize = 12.sp, color = MxGreen.copy(alpha = 0.5f), fontFamily = mono)
        rules.forEach { r -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("• ${r.name} [${r.trigger}${if (r.param.isNotBlank()) ":" + r.param else ""}]", Modifier.weight(1f), color = MxGreen.copy(alpha = 0.85f), fontSize = 12.sp, fontFamily = mono)
            TextButton(onClick = { vm.deleteRule(r.id) }) { Text("✕", color = MxGreen) }
        } }
        Divider(color = MxDim)
        // ---- LAN background watch ----
        Text("Фоновий моніторинг мережі (LAN watch)", fontFamily = mono, color = MxGreen)
        var confirmLan by remember { mutableStateOf(false) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(vm.store.lanWatchEnabled, { on -> if (on) confirmLan = true else vm.setLanWatch(false) })
            Text("Перевіряти інвентар кожні ~15 хв і сповіщати про нові/зниклі пристрої", color = MxGreen, fontFamily = mono, fontSize = 12.sp)
        }
        Text("Пінгує ЛИШЕ збережені приватні IP (RFC1918). Результати НЕ надсилаються у хмарну модель — тільки локальні сповіщення.", fontSize = 10.sp, color = MxGreen.copy(alpha = 0.55f), fontFamily = mono)
        if (confirmLan) AlertDialog(onDismissRequest = { confirmLan = false }, containerColor = Color(0xFF07140C), textContentColor = MxGreen,
            title = { Text("Увімкнути LAN-моніторинг?", fontFamily = mono, color = MxGreen) },
            text = { Text("Фонове завдання періодично пінгуватиме твої збережені приватні IP і надсилатиме локальні сповіщення про зміни. Нічого не йде у хмару.", fontFamily = mono, fontSize = 12.sp) },
            confirmButton = { Button(onClick = { vm.setLanWatch(true); confirmLan = false }, colors = ButtonDefaults.buttonColors(containerColor = MxGreen, contentColor = MxBg)) { Text("Увімкнути") } },
            dismissButton = { TextButton(onClick = { confirmLan = false }) { Text("Скасувати", color = MxGreen) } })
        Divider(color = MxDim)
        // ---- Per-tool permission profiles ----
        Text("Профілі дозволів інструментів", fontFamily = mono, color = MxGreen)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ToolProfiles.PROFILES.forEach { p ->
                FilterChip(vm.store.toolProfile == p, { vm.applyToolProfile(p) }, { Text(ToolProfiles.label(p).substringBefore(" ("), fontFamily = mono, fontSize = 11.sp) },
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = MxDim, selectedLabelColor = MxGreen, labelColor = MxGreen.copy(alpha = .6f)))
            }
        }
        Text("Профіль: ${ToolProfiles.label(vm.store.toolProfile)}. Вимкнені інструменти агент не може викликати (fail-closed).", fontSize = 10.sp, color = MxGreen.copy(alpha = 0.55f), fontFamily = mono)
        var showTools by remember { mutableStateOf(false) }
        TextButton(onClick = { showTools = !showTools }) { Text(if (showTools) "Сховати список інструментів ▴" else "Показати всі інструменти ▾", color = MxGreen, fontFamily = mono, fontSize = 12.sp) }
        if (showTools) vm.toolRisks().forEach { (name, risk) ->
            val enabled = name !in vm.store.disabledTools
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text(name, color = MxGreen, fontFamily = mono, fontSize = 12.sp); Text(risk.name, color = MxGreen.copy(alpha = 0.5f), fontFamily = mono, fontSize = 9.sp) }
                Switch(enabled, { vm.toggleTool(name, it) })
            }
        }
        Divider(color = MxDim)
        // ---- Local SLM: real downloadable model manager ----
        Text("Локальна модель (офлайн)", fontFamily = mono, color = MxGreen)
        Text(if (vm.engineLinked) "✓ Рушій on-device (MediaPipe) вшито у збірку (native .so). Завантаж .task-модель → кнопка ⚡ у чаті для офлайн-відповіді. Примітка: перша генерація не перевірена на твоєму конкретному пристрої — помилки будуть показані, не приховані." else "Рушій on-device не злінкований у цій збірці.", fontSize = 11.sp, color = MxGreen.copy(alpha = 0.7f), fontFamily = mono)
        Text("MediaPipe .task-моделі виконуються на пристрої повністю офлайн. GGUF-записи поки лише завантажуються (llama.cpp — у розробці). Усе з перевіркою SHA-256.", fontSize = 10.sp, color = MxGreen.copy(alpha = 0.55f), fontFamily = mono)
        vm.localModels.forEach { m ->
            val have = vm.isModelDownloaded(m)
            Card(Modifier.fillMaxWidth().padding(vertical = 3.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF07140C))) {
                Column(Modifier.padding(10.dp)) {
                    Text(m.name, color = MxGreen, fontFamily = mono, fontSize = 13.sp)
                    Text("~${m.sizeMb} МБ · ${m.note}", color = MxGreen.copy(alpha = 0.6f), fontFamily = mono, fontSize = 10.sp)
                    if (vm.modelDownloading == m.id) { LinearProgressIndicator(progress = vm.modelProgress, modifier = Modifier.fillMaxWidth().padding(top = 4.dp), color = MxGreen) }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
                        if (!have) BigButton(if (vm.modelDownloading == m.id) "Завантаження…" else "Завантажити", { vm.downloadModel(m) }, enabled = vm.modelDownloading == null)
                        else { Text("✓ на диску (${vm.modelSizeMb(m)} МБ)", color = MxGreen, fontFamily = mono, fontSize = 11.sp, modifier = Modifier.align(Alignment.CenterVertically)); TextButton(onClick = { vm.deleteModel(m) }) { Text("Видалити", color = MxGreen, fontFamily = mono, fontSize = 12.sp) } }
                    }
                    if (have) Text(vm.localLlmStatus(m), color = MxGreen.copy(alpha = 0.6f), fontFamily = mono, fontSize = 10.sp)
                }
            }
        }
        if (vm.modelStatus.isNotBlank()) Text(vm.modelStatus, color = MxGreen.copy(alpha = 0.8f), fontFamily = mono, fontSize = 11.sp)
        // ---- v3.6: honest on-device self-check ----
        BigButton(if (vm.selfCheckBusy) "Діагностика…" else "Самодіагностика рушія (on-device)", { vm.runSelfCheck() }, enabled = !vm.selfCheckBusy)
        if (vm.selfCheckReport.isNotBlank()) Text(vm.selfCheckReport, color = MxGreen.copy(alpha = 0.75f), fontFamily = mono, fontSize = 10.sp)
        Text("Чому MediaPipe .task: Google підтримує готовий native-рушій (LLM Inference) з робочим generate() на Android. Самостійна крос-компіляція llama.cpp JNI у цьому середовищі не відтворювана, тож GGUF-шлях лишається документованим планом, а не фейковою генерацією.", fontSize = 10.sp, color = MxGreen.copy(alpha = 0.5f), fontFamily = mono)
        Divider(color = MxDim)
        // ---- v3.6: Vosk offline voice (download on demand, not bundled) ----
        Text("Офлайн-голос (Vosk)", fontFamily = mono, color = MxGreen)
        Text("Завантаж модель Vosk на вимогу — у APK вона не вшита. Push-to-talk використовує Vosk офлайн, коли модель є; інакше — системний розпізнавач. Активний: ${vm.voiceEngineLabel()}.", fontSize = 11.sp, color = MxGreen.copy(alpha = 0.7f), fontFamily = mono)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(vm.voskEnabled(), { vm.setVoskEnabled(it) })
            Text(if (vm.voskEnabled()) "Vosk увімкнено" else "Vosk вимкнено (системний STT)", color = MxGreen.copy(alpha = 0.8f), fontFamily = mono, fontSize = 12.sp, modifier = Modifier.padding(start = 6.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(vm.voskStrict(), { vm.setVoskStrict(it) })
            Text("Строгий офлайн (не передавати аудіо системному розпізнавачу)", color = MxGreen.copy(alpha = 0.8f), fontFamily = mono, fontSize = 11.sp, modifier = Modifier.padding(start = 6.dp))
        }
        vm.voskModels.forEach { m ->
            val have = vm.voskInstalled(m)
            val sel = vm.voskSelected() == m.id
            Card(Modifier.fillMaxWidth().padding(vertical = 3.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF07140C))) {
                Column(Modifier.padding(10.dp)) {
                    Text((if (sel) "● " else "○ ") + m.name, color = MxGreen, fontFamily = mono, fontSize = 13.sp)
                    Text("~${m.sizeMb} МБ · ${m.note}", color = MxGreen.copy(alpha = 0.6f), fontFamily = mono, fontSize = 10.sp)
                    if (vm.voskDownloading == m.id) LinearProgressIndicator(progress = vm.voskProgress, modifier = Modifier.fillMaxWidth().padding(top = 4.dp), color = MxGreen)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
                        if (!have) BigButton(if (vm.voskDownloading == m.id) "Завантаження…" else "Завантажити", { vm.downloadVosk(m) }, enabled = vm.voskDownloading == null)
                        else {
                            Text("✓ на диску", color = MxGreen, fontFamily = mono, fontSize = 11.sp, modifier = Modifier.align(Alignment.CenterVertically))
                            if (!sel) TextButton(onClick = { vm.selectVosk(m.id) }) { Text("Обрати", color = MxGreen, fontFamily = mono, fontSize = 12.sp) }
                            TextButton(onClick = { vm.deleteVosk(m) }) { Text("Видалити", color = MxGreen, fontFamily = mono, fontSize = 12.sp) }
                        }
                    }
                }
            }
        }
        if (vm.voskStatus.isNotBlank()) Text(vm.voskStatus, color = MxGreen.copy(alpha = 0.8f), fontFamily = mono, fontSize = 11.sp)
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

@Composable
fun PlanPanel() {
    val steps = AgentPlan.steps
    if (steps.isEmpty()) return
    Column(Modifier.fillMaxWidth().background(Color(0xFF07140C), RoundedCornerShape(8.dp)).padding(8.dp)) {
        Text("▸ План агента", color = MxGreen, fontFamily = mono, fontSize = 12.sp)
        steps.forEachIndexed { i, st ->
            val mark = when (st.status) { "done" -> "✓"; "running" -> "⏵"; "failed" -> "✗"; else -> "•" }
            val c = when (st.status) { "done" -> MxGreen; "failed" -> Color(0xFFFF6E6E); "running" -> Color(0xFFFFC107); else -> MxGreen.copy(alpha = 0.6f) }
            Text("$mark ${i + 1}. ${st.text}", color = c, fontFamily = mono, fontSize = 11.sp)
        }
    }
}

@Composable
fun ErrorCard(text: String) {
    var open by remember { mutableStateOf(false) }
    val ctx = LocalContext.current
    Column(Modifier.fillMaxWidth().background(Color(0xFF3A0E0E), RoundedCornerShape(8.dp)).clickable { open = !open }.padding(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Warning, null, tint = Color(0xFFFF6E6E), modifier = Modifier.size(16.dp))
            Text("  ${text.lineSequence().firstOrNull() ?: "Помилка"}", color = Color(0xFFFF9E9E), fontFamily = mono, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Text(if (open) "▴" else "▾", color = MxGreen)
        }
        if (open) {
            SelectionContainer { Text(text, color = MxGreen.copy(alpha = 0.85f), fontFamily = mono, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp)) }
            TextButton(onClick = {
                val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("error", text))
            }) { Text("Копіювати", color = MxGreen, fontFamily = mono, fontSize = 12.sp) }
        }
    }
}

@Composable
fun CommandPalette(vm: AgentViewModel, onDismiss: () -> Unit, onNav: (Int) -> Unit) {
    var q by remember { mutableStateOf("") }
    val actions = listOf<Pair<String, () -> Unit>>(
        "Новий чат" to { vm.startNew(); onNav(0) },
        "Відкрити моделі" to { onNav(1) },
        "Інструменти" to { onNav(2) },
        "Термінал / Код" to { onNav(3) },
        "Мережа / Пристрої" to { onNav(4) },
        "Налаштування" to { onNav(5) },
        "Сканувати LAN" to { vm.send("Запусти lan_scan і додай знайдені пристрої в інвентар через inventory_add."); onNav(0) },
        "Знайти пристрої (mDNS+SSDP)" to { vm.send("Знайди пристрої в локальній мережі та запропонуй зберегти їх у інвентар."); onNav(0) },
        "Звіт безпеки мережі" to { vm.send("Запусти security_report і поясни результати."); onNav(0) },
        "Увімкнути «чат з нотатками»" to { vm.ragEnabled = true; onNav(0) },
    )
    val filtered = actions.filter { it.first.contains(q, true) }
    AlertDialog(onDismissRequest = onDismiss, containerColor = Color(0xFF07140C), textContentColor = MxGreen,
        title = { Text("Командна палітра", fontFamily = mono, color = MxGreen) },
        text = { Column {
            OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text("фільтр…", fontFamily = mono) },
                textStyle = androidx.compose.ui.text.TextStyle(fontFamily = mono, color = MxGreen))
            LazyColumn(Modifier.heightIn(max = 300.dp)) {
                items(filtered) { (label, act) ->
                    Text(label, Modifier.fillMaxWidth().clickable { act() }.padding(vertical = 10.dp), color = MxGreen, fontFamily = mono, fontSize = 14.sp)
                }
            }
        } },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Закрити", color = MxGreen) } })
}
