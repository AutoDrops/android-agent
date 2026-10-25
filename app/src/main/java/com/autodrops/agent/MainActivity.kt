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
import androidx.compose.foundation.border
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
import androidx.compose.runtime.saveable.rememberSaveable
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
private val MxBg = Color(0xFF050A07)        // solid near-black app background
private val MxCard = Color(0xFF0C1E12)      // opaque card surface (high enough contrast vs bg)
private val MxBorder = Color(0xFF1F6B3A)    // visible card/control border
private val mono = FontFamily.Monospace

/** High-contrast green on/off switch used across all screens. */
@Composable
fun mxSwitchColors() = SwitchDefaults.colors(
    checkedThumbColor = MxBg, checkedTrackColor = MxGreen, checkedBorderColor = MxGreen,
    uncheckedThumbColor = MxGreen, uncheckedTrackColor = MxCard, uncheckedBorderColor = MxGreen.copy(alpha = 0.6f))

/** Opaque, bordered card so content never blends into the background. */
@Composable
fun MxCardColors() = CardDefaults.cardColors(containerColor = MxCard)
val MxCardBorder @Composable get() = androidx.compose.foundation.BorderStroke(1.dp, MxBorder)

class MainActivity : ComponentActivity() {
    private val perms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runCatching { com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(applicationContext) }
        val shared = if (intent?.action == Intent.ACTION_SEND) intent.getStringExtra(Intent.EXTRA_TEXT) else null
        val tileAction = intent?.getStringExtra("tile_action")
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = MxGreen, secondary = MxGreen, background = MxBg,
                surface = MxCard, onPrimary = MxBg, onSurface = MxGreen, onBackground = MxGreen)) {
                Surface(Modifier.fillMaxSize(), color = MxBg) { Root(shared = shared, tileAction = tileAction) { askPerms() } }
            }
        }
    }
    fun askPerms() {
        val p = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.READ_CONTACTS, Manifest.permission.RECORD_AUDIO, Manifest.permission.READ_SMS, Manifest.permission.SEND_SMS)
        if (Build.VERSION.SDK_INT >= 33) { p += Manifest.permission.NEARBY_WIFI_DEVICES; p += Manifest.permission.POST_NOTIFICATIONS }
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
    var booting by rememberSaveable { mutableStateOf(vm.matrixRain) }
    if (booting) { BootScreen(vm) { booting = false }; return }
    val tabs = listOf("Чат" to Icons.Default.Chat, "Моделі" to Icons.Default.Memory, "Засоби" to Icons.Default.Build,
        "Код" to Icons.Default.Code, "Мережа" to Icons.Default.Wifi, "Налашт." to Icons.Default.Settings)
    Box(Modifier.fillMaxSize().background(MxBg)) {
        Scaffold(containerColor = MxBg, bottomBar = { BottomNavBar(tabs, tab) { tab = it } }) { pad ->
            Box(Modifier.padding(pad).fillMaxSize().background(MxBg)) {
                when (tab) {
                    0 -> ChatTab(vm, onOpenModels = { tab = 1 }, onPalette = { palette = true }); 1 -> ModelsTab(vm) { tab = 0 }; 2 -> ToolsTab(vm, askPerms)
                    3 -> DevTab(vm); 4 -> NetworkTab(vm); else -> SettingsTab(vm, askPerms)
                }
            }
        }
    }
    var showOnboarding by remember { mutableStateOf(!vm.onboardingDone()) }
    if (showOnboarding) OnboardingScreen(vm, askPerms, onDone = { vm.markOnboardingDone(); showOnboarding = false })
    if (palette) CommandPalette(vm, onDismiss = { palette = false }, onNav = { tab = it; palette = false })
    vm.pending?.let { p ->
        AlertDialog(onDismissRequest = {}, containerColor = MxCard, titleContentColor = MxGreen, textContentColor = MxGreen,
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

@Composable
fun BottomNavBar(tabs: List<Pair<String, androidx.compose.ui.graphics.vector.ImageVector>>, selected: Int, onSelect: (Int) -> Unit) {
    NavigationBar(containerColor = Color(0xFF04120A), tonalElevation = 0.dp) {
        tabs.forEachIndexed { i, (label, icon) ->
            NavigationBarItem(selected = selected == i, onClick = { onSelect(i) }, alwaysShowLabel = true,
                icon = { Icon(icon, label, tint = if (selected == i) MxGreen else MxGreen.copy(alpha = 0.6f)) },
                label = { Text(label, fontSize = 10.sp, fontFamily = mono, maxLines = 1) },
                colors = NavigationBarItemDefaults.colors(
                    indicatorColor = MxDim,
                    selectedIconColor = MxGreen, unselectedIconColor = MxGreen.copy(alpha = 0.6f),
                    selectedTextColor = MxGreen, unselectedTextColor = MxGreen.copy(alpha = 0.7f)))
        }
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
            Text("▾ ${vm.store.model.substringAfterLast('/')}", Modifier.clickable { onOpenModels() }.widthIn(max = 180.dp), color = MxGreen.copy(alpha = 0.8f), fontFamily = mono, fontSize = 12.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        }
        PlanPanel()
        if (!vm.hasKey && !(ChatGptOAuth.isChatGptModel(vm.store.model) && vm.cgSignedIn)) Card(Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { onOpenModels() },
            colors = CardDefaults.cardColors(containerColor = Color(0xFF2A0E0E)), border = MxCardBorder) {
            Column(Modifier.padding(14.dp)) {
                Text("⚠ Введіть ключ OpenRouter", color = Color(0xFFFF6E6E), fontFamily = mono, fontSize = 17.sp)
                Text("Без ключа чат не працює. Тап → вкладка «Моделі» → «API ключ» → «Зберегти». Ключ зберігається лише в Android Keystore.", color = MxGreen, fontFamily = mono, fontSize = 12.sp)
            }
        }
        if (vm.items.isEmpty()) Box(Modifier.weight(1f).fillMaxWidth()) {
            Empty("> Запитай агента: переглянь телефон, скануй мережу, знайди ТВ або напиши й запусти код.")
        }
        else LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp), reverseLayout = true) {
            items(vm.items.reversed(), key = { it.id }) { m ->
                if (m.card != null) ToolCardView(m.card)
                else if (m.role == "error") ErrorCard(m.text)
                else {
                    val bg = when (m.role) { "user" -> Color(0xFF0B2A16); "info" -> Color(0xFF2A2A0E); else -> MxCard }
                    Column {
                        SelectionContainer { Text(m.text.ifBlank { "…" }, Modifier.fillMaxWidth().background(bg, RoundedCornerShape(8.dp)).padding(10.dp), fontSize = 15.sp, color = MxGreen, fontFamily = mono) }
                        m.usage?.let { Text(it.label(), color = MxGreen.copy(alpha = 0.5f), fontFamily = mono, fontSize = 10.sp, modifier = Modifier.padding(start = 4.dp, top = 2.dp)) }
                    }
                }
            }
        }
        if (vm.busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = MxGreen)
        var listening by remember { mutableStateOf(false) }
        var offerVoiceDl by remember { mutableStateOf(false) }
        val selVosk = VoskCatalog.byId(vm.voskSelected())
        val voskInstalled = selVosk != null && vm.voskInstalled(selVosk)
        fun beginListening() {
            listening = true
            vm.startVoice(onPartial = { input = it }, onFinal = { input = it; listening = false }, onError = { msg -> listening = false; vm.addInfo("Мікрофон: $msg") })
        }
        if (offerVoiceDl && selVosk != null) AlertDialog(onDismissRequest = { offerVoiceDl = false }, containerColor = MxCard, textContentColor = MxGreen,
            title = { Text("Офлайн-голос", fontFamily = mono, color = MxGreen) },
            text = { Text(VoicePrompt.offerMessage(selVosk.name), fontFamily = mono, fontSize = 12.sp) },
            confirmButton = { Button(onClick = { offerVoiceDl = false; vm.downloadVosk(selVosk) }, colors = ButtonDefaults.buttonColors(containerColor = MxGreen, contentColor = MxBg)) { Text("Завантажити") } },
            dismissButton = { TextButton(onClick = { offerVoiceDl = false; if (!vm.voskStrict()) beginListening() }) { Text(if (vm.voskStrict()) "Скасувати" else "Системний", color = MxGreen) } })
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconToggleButton(checked = vm.ragEnabled, onCheckedChange = { vm.ragEnabled = it }) {
                Icon(Icons.Default.MenuBook, "chat with notes", tint = if (vm.ragEnabled) Color(0xFFFFC107) else MxGreen.copy(alpha = 0.6f))
            }
            IconButton(onClick = {
                if (listening) { vm.voice.stop(); listening = false }
                else if (vm.voskDownloading == null && VoicePrompt.shouldOfferDownload(vm.voskEnabled(), vm.voskStrict(), voskInstalled)) offerVoiceDl = true
                else beginListening()
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
    val color = when (card.status) { "done" -> Color(0xFF0B2A16); "denied" -> Color(0xFF3A0E0E); "awaiting approval" -> Color(0xFF2A2A0E); else -> MxCard }
    Column(Modifier.fillMaxWidth().background(color, RoundedCornerShape(8.dp)).padding(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (card.status == "running" || card.status == "awaiting approval") CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 2.dp, color = MxGreen)
            Text("  🛠 ${card.name} · ${card.status}", fontSize = 13.sp, color = MxGreen, fontFamily = mono)
        }
        if (card.preview.isNotBlank()) Text("args: " + sanitizeForDisplay(card.preview), fontFamily = mono, fontSize = 11.sp, color = MxGreen.copy(alpha = 0.75f))
        if (card.output.isNotBlank()) {
            val head = card.output.lineSequence().firstOrNull().orEmpty()
            if (head.startsWith("exit=")) Text(head, fontFamily = mono, fontSize = 11.sp, color = Color(0xFFFFC107))
            SelectionContainer { Text(sanitizeForDisplay(card.output.take(4000)), fontFamily = mono, fontSize = 11.sp, color = MxGreen.copy(alpha = 0.9f), modifier = Modifier.padding(top = 2.dp)) }
        }
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
            BigButton("Зберегти", { vm.store.baseUrl = base.ifBlank { SecureStore.DEFAULT_BASE }; vm.saveKey(key); key = ""; vm.loadModels() })
            BigButton(stringRes(vm, "Оновити"), { vm.loadCatalog(forceLive = true) })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (vm.hasKey) Text("Ключ: збережено ✓", fontSize = 11.sp, color = MxGreen.copy(alpha = 0.7f), fontFamily = mono, modifier = Modifier.align(Alignment.CenterVertically))
            else Text("Ключ: відсутній", fontSize = 11.sp, color = Color(0xFFFFC107), fontFamily = mono, modifier = Modifier.align(Alignment.CenterVertically))
            TextButton(onClick = { vm.clearKey(); key = "" }) { Text("Очистити ключ", color = MxGreen, fontFamily = mono, fontSize = 12.sp) }
        }
        BigButton(if (vm.connBusy) "Перевірка…" else "Перевірити з'єднання", { vm.testConnection() }, enabled = !vm.connBusy)
        if (vm.connStatus.isNotBlank()) Text(vm.connStatus, fontSize = 13.sp, fontFamily = mono, color = if (vm.connStatus.startsWith("ПОМИЛКА")) Color(0xFFFF6E6E) else MxGreen)
        ChatGptSection(vm, onPicked)
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
                    colors = CardDefaults.cardColors(containerColor = if (m.id == vm.store.model) Color(0xFF0B2A16) else MxCard), border = MxCardBorder) {
                    Column(Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(m.id, Modifier.weight(1f), fontSize = 14.sp, color = MxGreen, fontFamily = mono, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
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
            BigButton("ChatGPT (сайт)", { web("https://chatgpt.com") }, Modifier.weight(1f))
            BigButton("Grok", { web("https://grok.com") }, Modifier.weight(1f))
            BigButton("Gemini", { web("https://gemini.google.com") }, Modifier.weight(1f))
        }
        Divider(color = MxDim)
        BigButton("Код-рев'ю (огляд коду)", { reviewMode = true }, Modifier.fillMaxWidth())
        Divider(color = MxDim)
        Divider(color = MxDim)
        Text("База знань (локальний RAG)", fontFamily = mono, color = MxGreen)
        var embRemote by remember { mutableStateOf(vm.store.embeddingRemote) }
        var embModel by remember { mutableStateOf(vm.store.embeddingModel) }
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(embRemote, { embRemote = it; vm.store.embeddingRemote = it })
            Text("Віддалені embeddings (OpenRouter / OpenAI-сумісні, твій ключ)", fontSize = 12.sp, fontFamily = mono, color = MxGreen)
        }
        if (embRemote) {
            OutlinedTextField(embModel, { embModel = it; vm.store.embeddingModel = it }, Modifier.fillMaxWidth(),
                label = { Text("Модель embeddings", fontFamily = mono) }, singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(fontFamily = mono, color = MxGreen))
            Text("Якщо віддалений виклик впаде — автоматично локальний hashing-fallback.", fontSize = 11.sp, color = MxGreen.copy(alpha = 0.6f), fontFamily = mono)
        }
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
        recipes.forEach { r -> Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MxCard), border = MxCardBorder) {
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
        val tree = DevTree.build(files)
        LazyColumn(Modifier.heightIn(max = 140.dp)) {
            items(tree, key = { it.path }) { row ->
                val isSel = !row.isDir && row.path == selected
                Text((if (row.isDir) "📁 " else "• ") + row.name,
                    Modifier.fillMaxWidth().background(if (isSel) Color(0xFF0B2A16) else Color.Transparent)
                        .then(if (row.isDir) Modifier else Modifier.clickable { selected = row.path; content = vm.readFile(row.path); dirty = false })
                        .padding(start = (6 + row.depth * 14).dp, top = 5.dp, bottom = 5.dp, end = 6.dp),
                    fontFamily = mono, fontSize = 12.sp,
                    color = if (row.isDir) MxGreen.copy(alpha = 0.6f) else MxGreen)
            }
        }
        Divider(Modifier.padding(vertical = 4.dp), color = MxDim)
        if (selected == null) Text("Обери файл для редагування.", fontSize = 12.sp, color = MxGreen.copy(alpha = 0.5f), fontFamily = mono)
        else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(selected!!, Modifier.weight(1f), fontFamily = mono, fontSize = 12.sp, color = MxGreen)
                if (dirty) Text("●", color = Color(0xFFFFC107))
                TextButton(onClick = { vm.writeFile(selected!!, content); dirty = false }) { Text("Зберегти", color = MxGreen, fontFamily = mono) }
                TextButton(onClick = { vm.runTerminal(runCommandFor(selected!!), true) { term = it } }) { Text("Запуск\u2192чат", color = MxGreen, fontFamily = mono) }
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
                    modifier = Modifier.background(MxCard).padding(horizontal = 6.dp, vertical = 2.dp))
            }
        }
        // Saved snippets: tap to run, long-press ✕ to delete
        val snips = vm.snippets()
        if (snips.isNotEmpty()) LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 2.dp)) {
            items(snips, key = { it.first }) { (name, c) ->
                AssistChip(onClick = { cmd = c }, label = { Text(name, fontFamily = mono, fontSize = 11.sp) },
                    trailingIcon = { Text("✕", color = MxGreen.copy(alpha = 0.6f), modifier = Modifier.clickable { vm.deleteSnippet(name) }) },
                    colors = AssistChipDefaults.assistChipColors(containerColor = MxCard, labelColor = MxGreen))
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
        devices.forEach { d -> Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MxCard), border = MxCardBorder) {
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
fun SettingsTab(vm: AgentViewModel, askPerms: () -> Unit) {
    @Suppress("UNUSED_VARIABLE") val toolsRev = vm.toolsVersion // recompose profile chips + tool switches
    var lanOn by remember { mutableStateOf(vm.store.lanWatchEnabled) }
    var voskOn by remember { mutableStateOf(vm.voskEnabled()) }
    var strictOn by remember { mutableStateOf(vm.voskStrict()) }
    var shellMode by remember { mutableStateOf(vm.store.shellMode) }
    val convs by vm.conversations().collectAsState(initial = emptyList())
    Column(Modifier.fillMaxSize().systemBarsPadding().padding(12.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Head("Налаштування")
        var sub by remember { mutableStateOf("") }
        var showOb by remember { mutableStateOf(false) }
        if (showOb) { OnboardingScreen(vm, askPerms, onDone = { vm.markOnboardingDone(); showOb = false }); return }
        if (sub == "caps") { CapabilitiesScreen(vm) { sub = "" }; return }
        if (sub == "selftest") { DeviceSelfTestScreen(vm) { sub = "" }; return }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BigButton("Можливості (довідка)", { sub = "caps" }, Modifier.weight(1f))
            BigButton("Самотест пристрою", { sub = "selftest" }, Modifier.weight(1f))
        }
        BigButton("Екран дозволів (онбординг)", { showOb = true }, Modifier.fillMaxWidth())
        Row(verticalAlignment = Alignment.CenterVertically) { Switch(vm.matrixRain, { vm.toggleMatrixRain(it) }, colors = mxSwitchColors()); Text("Boot-екран при старті (Parrot-style лог)", color = MxGreen, fontFamily = mono) }
        Row(verticalAlignment = Alignment.CenterVertically) { var streamOn by remember { mutableStateOf(vm.store.stream) }; Switch(streamOn, { streamOn = it; vm.store.stream = it }, colors = mxSwitchColors()); Text("Потокові відповіді (stream)", color = MxGreen, fontFamily = mono) }
        Row(verticalAlignment = Alignment.CenterVertically) { var speakOn by remember { mutableStateOf(vm.store.speakReplies) }; Switch(speakOn, { speakOn = it; vm.store.speakReplies = it }, colors = mxSwitchColors()); Text("Озвучувати відповіді (TTS, укр.)", color = MxGreen, fontFamily = mono) }
        Text("Мова: українська (типово), англійська доступна у системних налаштуваннях.", fontSize = 12.sp, color = MxGreen.copy(alpha = 0.6f), fontFamily = mono)
        Divider(color = MxDim)
        Text("Виконання shell", fontFamily = mono, color = MxGreen)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("sandbox", "root").forEach { mode -> FilterChip(selected = shellMode == mode, onClick = { shellMode = mode; vm.store.shellMode = mode }, label = { Text(mode, fontFamily = mono) },
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
        var rAction by remember { mutableStateOf("notify") }
        var rPrompt by remember { mutableStateOf("") }
        var rInterval by remember { mutableStateOf("60") }
        var rErr by remember { mutableStateOf<String?>(null) }
        OutlinedTextField(rName, { rName = it }, Modifier.fillMaxWidth(), label = { Text("Назва правила", fontFamily = mono) }, singleLine = true,
            textStyle = androidx.compose.ui.text.TextStyle(fontFamily = mono, color = MxGreen))
        Text("Тригер", fontFamily = mono, color = MxGreen.copy(alpha = .7f), fontSize = 11.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            RulePolicy.TRIGGERS.forEach { (id, lbl) ->
                FilterChip(rTrigger == id, { rTrigger = id }, { Text(lbl, fontFamily = mono, fontSize = 11.sp) },
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = MxDim, selectedLabelColor = MxGreen, labelColor = MxGreen.copy(alpha = .6f)))
            }
        }
        if (rTrigger == "wifi_ssid") OutlinedTextField(rParam, { rParam = it }, Modifier.fillMaxWidth(), label = { Text("SSID містить…", fontFamily = mono) }, singleLine = true,
            textStyle = androidx.compose.ui.text.TextStyle(fontFamily = mono, color = MxGreen))
        if (rTrigger == "battery_low") OutlinedTextField(rParam, { rParam = it }, Modifier.fillMaxWidth(), label = { Text("Поріг заряду %, напр. 20", fontFamily = mono) }, singleLine = true,
            textStyle = androidx.compose.ui.text.TextStyle(fontFamily = mono, color = MxGreen))
        if (rTrigger == "interval") OutlinedTextField(rInterval, { rInterval = it }, Modifier.fillMaxWidth(), label = { Text("Інтервал, хв (мін 15)", fontFamily = mono) }, singleLine = true,
            textStyle = androidx.compose.ui.text.TextStyle(fontFamily = mono, color = MxGreen))
        Text("Дія", fontFamily = mono, color = MxGreen.copy(alpha = .7f), fontSize = 11.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            RulePolicy.ACTIONS.forEach { (id, lbl) ->
                FilterChip(rAction == id, { rAction = id }, { Text(lbl, fontFamily = mono, fontSize = 11.sp) },
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = MxDim, selectedLabelColor = MxGreen, labelColor = MxGreen.copy(alpha = .6f)))
            }
        }
        if (rAction == "run_prompt") OutlinedTextField(rPrompt, { rPrompt = it }, Modifier.fillMaxWidth(), label = { Text("Запит агенту (виконається лише після підтвердження у застосунку)", fontFamily = mono) },
            textStyle = androidx.compose.ui.text.TextStyle(fontFamily = mono, color = MxGreen))
        rErr?.let { Text(it, color = Color(0xFFFF6B6B), fontSize = 11.sp, fontFamily = mono) }
        BigButton("Додати правило", {
            val iv = rInterval.toIntOrNull() ?: 60
            val err = RulePolicy.validate(rName, rTrigger, rAction, iv, rParam)
            if (err != null) rErr = err else {
                vm.addRule(Rule(name = rName, trigger = rTrigger, param = rParam, intervalMinutes = iv, action = rAction, allowedPrompt = rPrompt))
                rName = ""; rParam = ""; rPrompt = ""; rErr = null
            }
        }, Modifier.fillMaxWidth())
        Text("Правила працюють раз на ~15 хв (ліміт WorkManager), лише читають стан і шлють сповіщення. Дія «Запит агенту» НЕ виконується у фоні — вона ставиться в чергу й проходить підтвердження у застосунку (ризик HIGH).", fontSize = 11.sp, color = MxGreen.copy(alpha = 0.6f), fontFamily = mono)
        if (rules.isEmpty()) Text("Немає правил.", fontSize = 12.sp, color = MxGreen.copy(alpha = 0.5f), fontFamily = mono)
        rules.forEach { r -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("• ${r.name} [${r.trigger}${if (r.param.isNotBlank()) ":" + r.param else ""}→${r.action}]", Modifier.weight(1f), color = MxGreen.copy(alpha = if (r.enabled) 0.85f else 0.4f), fontSize = 12.sp, fontFamily = mono)
            Switch(r.enabled, { on -> vm.setRuleEnabled(r.id, on) }, colors = mxSwitchColors())
            TextButton(onClick = { vm.deleteRule(r.id) }) { Text("✕", color = MxGreen) }
        } }
        Divider(color = MxDim)
        // ---- LAN background watch ----
        Text("Фоновий моніторинг мережі (LAN watch)", fontFamily = mono, color = MxGreen)
        var confirmLan by remember { mutableStateOf(false) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(lanOn, { on -> if (on) confirmLan = true else { vm.setLanWatch(false); lanOn = false } }, colors = mxSwitchColors())
            Text("Перевіряти інвентар кожні ~15 хв і сповіщати про нові/зниклі пристрої", color = MxGreen, fontFamily = mono, fontSize = 12.sp)
        }
        Text("Пінгує ЛИШЕ збережені приватні IP (RFC1918). Результати НЕ надсилаються у хмарну модель — тільки локальні сповіщення.", fontSize = 10.sp, color = MxGreen.copy(alpha = 0.55f), fontFamily = mono)
        if (confirmLan) AlertDialog(onDismissRequest = { confirmLan = false }, containerColor = MxCard, textContentColor = MxGreen,
            title = { Text("Увімкнути LAN-моніторинг?", fontFamily = mono, color = MxGreen) },
            text = { Text("Фонове завдання періодично пінгуватиме твої збережені приватні IP і надсилатиме локальні сповіщення про зміни. Нічого не йде у хмару.", fontFamily = mono, fontSize = 12.sp) },
            confirmButton = { Button(onClick = { vm.setLanWatch(true); lanOn = true; confirmLan = false }, colors = ButtonDefaults.buttonColors(containerColor = MxGreen, contentColor = MxBg)) { Text("Увімкнути") } },
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
                Switch(enabled, { vm.toggleTool(name, it) }, colors = mxSwitchColors())
            }
        }
        Divider(color = MxDim)
        // ---- v3.18: prompt-length model routing ----
        Text("Маршрутизація моделей за довжиною промпта", fontFamily = mono, color = MxGreen)
        var routeOn by remember { mutableStateOf(vm.store.routingEnabled) }
        var smallM by remember { mutableStateOf(vm.store.routingSmallModel) }
        var largeM by remember { mutableStateOf(vm.store.routingLargeModel) }
        var threshStr by remember { mutableStateOf(vm.store.routingThresholdChars.toString()) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(routeOn, { routeOn = it; vm.store.routingEnabled = it }, colors = mxSwitchColors())
            Text(if (routeOn) "Увімкнено: короткі → мала модель, довгі → сильна" else "Вимкнено (завжди обрана модель)", color = MxGreen.copy(alpha = 0.8f), fontFamily = mono, fontSize = 11.sp, modifier = Modifier.padding(start = 6.dp))
        }
        if (routeOn) {
            OutlinedTextField(smallM, { smallM = it; vm.store.routingSmallModel = it }, Modifier.fillMaxWidth(), label = { Text("Мала модель (slug, порожньо = обрана)", fontFamily = mono, fontSize = 11.sp) }, singleLine = true, textStyle = androidx.compose.ui.text.TextStyle(fontFamily = mono, color = MxGreen))
            OutlinedTextField(largeM, { largeM = it; vm.store.routingLargeModel = it }, Modifier.fillMaxWidth(), label = { Text("Сильна модель (slug, порожньо = обрана)", fontFamily = mono, fontSize = 11.sp) }, singleLine = true, textStyle = androidx.compose.ui.text.TextStyle(fontFamily = mono, color = MxGreen))
            OutlinedTextField(threshStr, { v -> threshStr = v; v.toIntOrNull()?.let { vm.store.routingThresholdChars = it } }, Modifier.fillMaxWidth(), label = { Text("Поріг довжини (символів)", fontFamily = mono, fontSize = 11.sp) }, singleLine = true, textStyle = androidx.compose.ui.text.TextStyle(fontFamily = mono, color = MxGreen))
            Text("Промпт ≥ порога → сильна модель; інакше → мала. Порожнє поле = поточна обрана модель.", fontSize = 10.sp, color = MxGreen.copy(alpha = 0.55f), fontFamily = mono)
        }
        Divider(color = MxDim)
        // ---- Local SLM: real downloadable model manager ----
        Text("Локальна модель (офлайн)", fontFamily = mono, color = MxGreen)
        Text(when {
                vm.mediapipeLinked && vm.ggufLinked -> "✓ Рушії вшито: MediaPipe (.task) + llama.cpp/GGUF (arm64-v8a). Завантаж модель → ⚡ у чаті. Перша генерація на твоєму пристрої не перевірена в середовищі збірки — помилки будуть показані."
                vm.mediapipeLinked -> "✓ Рушій MediaPipe вшито. Завантаж .task-модель → ⚡ у чаті. Перша генерація не перевірена на твоєму пристрої."
                vm.ggufLinked -> "✓ Рушій llama.cpp (GGUF) вшито (arm64-v8a). Завантаж GGUF → ⚡ у чаті."
                else -> "Рушій on-device не злінкований у цій збірці."
            }, fontSize = 11.sp, color = MxGreen.copy(alpha = 0.7f), fontFamily = mono)
        Text("MediaPipe .task і GGUF (llama.cpp) виконуються офлайн на пристрої. Закріплений SHA-256 обовʼязковий для авто-вибору; записи без хеша — лише ручне завантаження (TOFU). GGUF natives: лише arm64-v8a.", fontSize = 10.sp, color = MxGreen.copy(alpha = 0.55f), fontFamily = mono)
        vm.localModels.forEach { m ->
            val have = vm.isModelDownloaded(m)
            Card(Modifier.fillMaxWidth().padding(vertical = 3.dp), colors = CardDefaults.cardColors(containerColor = MxCard), border = MxCardBorder) {
                Column(Modifier.padding(10.dp)) {
                    Text(m.name, color = MxGreen, fontFamily = mono, fontSize = 13.sp)
                    Text("~${m.sizeMb} МБ · ${m.note}", color = MxGreen.copy(alpha = 0.6f), fontFamily = mono, fontSize = 10.sp)
                    if (vm.modelDownloading == m.id) {
                        LinearProgressIndicator(progress = vm.modelProgress.coerceIn(0f, 1f), modifier = Modifier.fillMaxWidth().height(10.dp).padding(top = 4.dp), color = MxGreen, trackColor = MxDim)
                        Text("${(vm.modelProgress * 100).toInt().coerceIn(0, 100)}% · ${vm.modelBytes / 1_048_576} / ${vm.modelTotal / 1_048_576} МБ" + (if (vm.modelSpeed.isNotBlank()) " · ${vm.modelSpeed}" else ""), color = MxGreen, fontFamily = mono, fontSize = 13.sp)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
                        if (!have) BigButton(if (vm.modelDownloading == m.id) "Завантаження…" else "Завантажити", { vm.downloadModel(m) }, enabled = vm.modelDownloading == null)
                        else { Text("✓ на диску (${vm.modelSizeMb(m)} МБ)", color = MxGreen, fontFamily = mono, fontSize = 11.sp, modifier = Modifier.align(Alignment.CenterVertically)); BigButton(if (vm.localBusy) "Запуск…" else "Запустити", { vm.startLocalModel(m) }, enabled = !vm.localBusy); TextButton(onClick = { vm.deleteModel(m) }) { Text("Видалити", color = MxGreen, fontFamily = mono, fontSize = 12.sp) } }
                    }
                    if (have) Text(vm.localLlmStatus(m), color = MxGreen.copy(alpha = 0.6f), fontFamily = mono, fontSize = 10.sp)
                }
            }
        }
        if (vm.localRunStatus.isNotBlank()) Text(vm.localRunStatus, color = if (vm.localRunStatus.startsWith("ПОМИЛКА")) Color(0xFFFF6E6E) else MxGreen, fontFamily = mono, fontSize = 12.sp)
        if (vm.modelStatus.isNotBlank()) Text(vm.modelStatus, color = MxGreen.copy(alpha = 0.8f), fontFamily = mono, fontSize = 11.sp)
        // ---- v3.6: honest on-device self-check ----
        BigButton(if (vm.selfCheckBusy) "Діагностика…" else "Самодіагностика рушія (on-device)", { vm.runSelfCheck() }, enabled = !vm.selfCheckBusy)
        if (vm.selfCheckReport.isNotBlank()) Text(vm.selfCheckReport, color = MxGreen.copy(alpha = 0.75f), fontFamily = mono, fontSize = 10.sp)
        Text("v3.19: GGUF увімкнено через готовий Maven AAR (dev.ffmpegkit-maintained:llama-android) — без власної NDK-збірки. MediaPipe .task лишається основним шляхом для .task-бандлів. Обидва рушії злінковані; нативний інференс у середовищі збірки не ганявся (немає KVM/емулятора).", fontSize = 10.sp, color = MxGreen.copy(alpha = 0.5f), fontFamily = mono)
        Divider(color = MxDim)
        // ---- v3.6: Vosk offline voice (download on demand, not bundled) ----
        Text("Офлайн-голос (Vosk)", fontFamily = mono, color = MxGreen)
        Text("Завантаж модель Vosk на вимогу — у APK вона не вшита. Push-to-talk використовує Vosk офлайн, коли модель є; інакше — системний розпізнавач. Активний: ${vm.voiceEngineLabel()}.", fontSize = 11.sp, color = MxGreen.copy(alpha = 0.7f), fontFamily = mono)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(voskOn, { voskOn = it; vm.setVoskEnabled(it) }, colors = mxSwitchColors())
            Text(if (vm.voskEnabled()) "Vosk увімкнено" else "Vosk вимкнено (системний STT)", color = MxGreen.copy(alpha = 0.8f), fontFamily = mono, fontSize = 12.sp, modifier = Modifier.padding(start = 6.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(strictOn, { strictOn = it; vm.setVoskStrict(it) }, colors = mxSwitchColors())
            Text("Строгий офлайн (не передавати аудіо системному розпізнавачу)", color = MxGreen.copy(alpha = 0.8f), fontFamily = mono, fontSize = 11.sp, modifier = Modifier.padding(start = 6.dp))
        }
        var vadOn by remember { mutableStateOf(vm.store.vadAutoStop) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(vadOn, { vadOn = it; vm.store.vadAutoStop = it }, colors = mxSwitchColors())
            Text("Авто-стоп за тишею (VAD) — завершувати запис після паузи", color = MxGreen.copy(alpha = 0.8f), fontFamily = mono, fontSize = 11.sp, modifier = Modifier.padding(start = 6.dp))
        }
        vm.voskModels.forEach { m ->
            val have = vm.voskInstalled(m)
            val sel = vm.voskSelected() == m.id
            Card(Modifier.fillMaxWidth().padding(vertical = 3.dp), colors = CardDefaults.cardColors(containerColor = MxCard), border = MxCardBorder) {
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
    Column(Modifier.fillMaxWidth().background(MxCard, RoundedCornerShape(8.dp)).padding(8.dp)) {
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
    AlertDialog(onDismissRequest = onDismiss, containerColor = MxCard, textContentColor = MxGreen,
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

@Composable
fun OnboardingScreen(vm: AgentViewModel, askPerms: () -> Unit, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val perms = PermissionCatalog.forThisDevice().map { PermRow(it.uaName, it.rationale, Perms.has(ctx, it.permission)) }
    OnboardingContent(perms, askPerms, onDone)
}

/** Stateless onboarding row model so the screen can be rendered in screenshot tests. */
data class PermRow(val name: String, val rationale: String, val granted: Boolean)

@Composable
fun OnboardingContent(perms: List<PermRow>, askPerms: () -> Unit, onDone: () -> Unit) {
    // Opaque background: nothing (Matrix rain / chat content) bleeds through the onboarding overlay.
    Column(Modifier.fillMaxSize().background(MxBg).systemBarsPadding().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Head("Ласкаво просимо до DRON.exe")
        Text("Щоб інструменти працювали на твоєму телефоні, надай дозволи нижче. Усе небезпечне все одно проходить підтвердження в застосунку. Нічого не надсилається, окрім твого API-провайдера.",
            color = MxGreen.copy(alpha = 0.85f), fontFamily = mono, fontSize = 12.sp)
        perms.forEach { p ->
            Card(Modifier.fillMaxWidth(), colors = MxCardColors(), border = MxCardBorder) {
                Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(p.name, Modifier.weight(1f), color = MxGreen, fontFamily = mono, fontSize = 15.sp)
                        // Clear, high-contrast status pill instead of a faint glyph.
                        val pillBg = if (p.granted) MxGreen else Color(0xFF2A2A0E)
                        val pillFg = if (p.granted) MxBg else Color(0xFFFFC107)
                        Text(if (p.granted) "✓ НАДАНО" else "○ НІ",
                            color = pillFg, fontFamily = mono, fontSize = 12.sp,
                            modifier = Modifier.background(pillBg, RoundedCornerShape(6.dp))
                                .border(1.dp, if (p.granted) MxGreen else Color(0xFFFFC107), RoundedCornerShape(6.dp))
                                .padding(horizontal = 10.dp, vertical = 4.dp))
                    }
                    Text(p.rationale, color = MxGreen.copy(alpha = 0.75f), fontFamily = mono, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
                }
            }
        }
        Text("Деякі можливості потребують спеціального доступу (Accessibility, Слухач сповіщень, Запис екрана) — їх можна увімкнути на вкладці «Інструменти».",
            color = MxGreen.copy(alpha = 0.7f), fontFamily = mono, fontSize = 11.sp)
        BigButton("Надати runtime-дозволи", askPerms, Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BigButton("Пропустити", onDone, Modifier.weight(1f))
            BigButton("Готово", onDone, Modifier.weight(1f))
        }
    }
}

@Composable
fun CapabilitiesScreen(vm: AgentViewModel, onBack: () -> Unit) {
    val caps = remember { vm.capabilities() }
    var q by remember { mutableStateOf("") }
    val filtered = caps.filter { it.name.contains(q, true) || it.description.contains(q, true) }
    Column(Modifier.fillMaxSize().systemBarsPadding().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) { TextButton(onClick = onBack) { Text("‹ назад", color = MxGreen, fontFamily = mono) }; Head("Можливості") }
        Text("Усі ${caps.size} інструментів агента (джерело: ToolRegistry). Ризик і рівень підтвердження вказано для кожного.",
            fontSize = 11.sp, color = MxGreen.copy(alpha = 0.65f), fontFamily = mono)
        OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text("пошук…", fontFamily = mono) },
            textStyle = androidx.compose.ui.text.TextStyle(fontFamily = mono, color = MxGreen))
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(filtered, key = { it.name }) { c ->
                val disabled = c.name in vm.store.disabledTools
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MxCard), border = MxCardBorder) {
                    Column(Modifier.padding(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(c.name, Modifier.weight(1f), color = if (disabled) MxGreen.copy(alpha = 0.4f) else MxGreen, fontFamily = mono, fontSize = 14.sp)
                            Text(Capabilities.riskUa(c.risk), color = MxGreen.copy(alpha = 0.6f), fontFamily = mono, fontSize = 10.sp)
                        }
                        Text(c.description, color = MxGreen.copy(alpha = 0.8f), fontFamily = mono, fontSize = 11.sp)
                        Text((if (disabled) "⛔ вимкнено профілем · " else "") + c.gating, color = MxGreen.copy(alpha = 0.55f), fontFamily = mono, fontSize = 10.sp)
                    }
                }
            }
        }
    }
}

@Composable
fun DeviceSelfTestScreen(vm: AgentViewModel, onBack: () -> Unit) {
    val results = vm.deviceTestResults
    Column(Modifier.fillMaxSize().systemBarsPadding().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) { TextButton(onClick = onBack) { Text("‹ назад", color = MxGreen, fontFamily = mono) }; Head("Самотест пристрою") }
        Text("Справжні перевірки на цьому телефоні (лише читання / безпечні): доступ до API, офлайн-модель, дозволи, LAN, TTS. Результати не підроблюються.",
            fontSize = 11.sp, color = MxGreen.copy(alpha = 0.65f), fontFamily = mono)
        BigButton(if (vm.deviceTestBusy) "Виконую перевірки…" else "Запустити самотест", { vm.runDeviceSelfTest() }, Modifier.fillMaxWidth(), enabled = !vm.deviceTestBusy)
        if (vm.deviceTestBusy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = MxGreen)
        if (results.isNotEmpty()) Text("Підсумок: ${SelfTest.summary(results)}", color = MxGreen, fontFamily = mono, fontSize = 13.sp)
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(results) { r ->
                val c = when (r.status) { CheckStatus.PASS -> MxGreen; CheckStatus.FAIL -> Color(0xFFFF6E6E); CheckStatus.WARN -> Color(0xFFFFC107); CheckStatus.SKIP -> MxGreen.copy(alpha = 0.5f) }
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MxCard), border = MxCardBorder) {
                    Column(Modifier.padding(10.dp)) {
                        Text("${SelfTest.icon(r.status)} ${r.name}", color = c, fontFamily = mono, fontSize = 13.sp)
                        if (r.detail.isNotBlank()) Text(r.detail, color = MxGreen.copy(alpha = 0.75f), fontFamily = mono, fontSize = 11.sp)
                    }
                }
            }
        }
        if (results.isEmpty() && !vm.deviceTestBusy) Box(Modifier.weight(1f)) { Empty("Натисни «Запустити самотест».") }
    }
}


/** v3.21: Parrot-Security-style boot log: black screen, green lines scrolling ~2.5s, tap to skip. */
object BootLog {
    fun lines(hasKey: Boolean, model: String, version: String): List<String> = listOf(
        "[  0.000000] DRON.exe $version booting…",
        "[  0.041872] kernel: Kotlin/Compose runtime ............ [ OK ]",
        "[  0.118230] keystore: AES-256-GCM master key .......... [ OK ]",
        "[  0.203117] net: TLS 1.3, cleartext disabled .......... [ OK ]",
        "[  0.311004] firewall: RFC1918-only LAN tools .......... [ OK ]",
        "[  0.402551] tools: shell / files / LAN / TV / a11y .... [ OK ]",
        "[  0.517333] llm: engines MediaPipe + llama.cpp ........ [ OK ]",
        "[  0.620981] openrouter: api key ....................... " + if (hasKey) "[ OK ]" else "[FAIL] Введіть ключ OpenRouter",
        "[  0.733410] model: $model",
        "[  0.845120] ui: kali-dark high-contrast ............... [ OK ]",
        "[  0.951777] Starting agent shell…",
        "",
        "root@dron:~# _")
}

@Composable
fun BootScreen(vm: AgentViewModel, onDone: () -> Unit) {
    val all = remember { BootLog.lines(vm.hasKey, vm.store.model, BuildConfig.VERSION_NAME) }
    var shown by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        while (shown < all.size) { kotlinx.coroutines.delay(180); shown++ }
        kotlinx.coroutines.delay(400); onDone()
    }
    Column(Modifier.fillMaxSize().background(Color.Black).clickable { onDone() }.systemBarsPadding().padding(12.dp)) {
        all.take(shown).forEach { l ->
            Text(l, color = if (l.contains("[FAIL]")) Color(0xFFFF6E6E) else MxGreen, fontFamily = mono, fontSize = 12.sp)
        }
        Spacer(Modifier.weight(1f))
        Text("тап — пропустити", color = MxGreen.copy(alpha = 0.6f), fontFamily = mono, fontSize = 11.sp)
    }
}

/** v3.22: ChatGPT subscription as an in-app model (Codex-CLI device-code OAuth). */
@Composable
fun ChatGptSection(vm: AgentViewModel, onPicked: () -> Unit) {
    val ctx = LocalContext.current
    var open by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp), colors = CardDefaults.cardColors(containerColor = MxCard), border = MxCardBorder) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { open = !open }) {
                Text("ChatGPT (підписка) " + if (vm.cgSignedIn) "✓ ${vm.cgEmail}" else "— не виконано вхід", Modifier.weight(1f), color = MxGreen, fontFamily = mono, fontSize = 14.sp)
                Text(if (open) "▴" else "▾", color = MxGreen)
            }
            if (!vm.cgSignedIn) {
                if (vm.cgBusy) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BigButton("Відкрити сторінку входу", { ctx.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(ChatGptOAuth.DEVICE_PAGE)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) })
                    TextButton(onClick = { vm.cancelChatGptLogin() }) { Text("Скасувати", color = MxGreen, fontFamily = mono) }
                } else Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    BigButton("Увійти через ChatGPT", { vm.chatGptLogin { url ->
                        ctx.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }, Modifier.fillMaxWidth())
                    TextButton(onClick = { vm.chatGptLoginBrowser { url ->
                        ctx.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }) {
                        Text("Або вхід через браузер (PKCE / 127.0.0.1:1455)", color = MxGreen, fontFamily = mono, fontSize = 11.sp) }
                }
                if (vm.cgUserCode.isNotBlank()) SelectionContainer { Text("Код: ${vm.cgUserCode}", color = Color(0xFFFFC107), fontFamily = mono, fontSize = 22.sp) }
            } else {
                LazyRowChips(vm.cgModels.map { it to it }, ChatGptOAuth.backendModel(vm.store.model)) { vm.selectChatGptModel(it); onPicked() }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { vm.chatGptRefresh() }) { Text("Оновити токен", color = MxGreen, fontFamily = mono, fontSize = 12.sp) }
                    TextButton(onClick = { vm.chatGptLogout() }) { Text("Вийти", color = Color(0xFFFF6E6E), fontFamily = mono, fontSize = 12.sp) }
                }
            }
            if (vm.cgStatus.isNotBlank()) Text(vm.cgStatus, fontSize = 12.sp, fontFamily = mono, color = if (vm.cgStatus.startsWith("ПОМИЛКА")) Color(0xFFFF6E6E) else MxGreen)
            val log = vm.oauthLog(); if (log.isNotEmpty()) Text(log.takeLast(6).joinToString("\n"), fontSize = 10.sp, fontFamily = mono, color = MxGreen.copy(alpha = 0.55f))
        }
    }
}
