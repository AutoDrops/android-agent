package com.autodrops.agent

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import org.junit.Rule
import org.junit.Test

class ScreenshotTest {
    private val G = Color(0xFF00FF66); private val DIM = Color(0xFF0A3A1A); private val BG = Color(0xFF000000)
    private val mono = FontFamily.Monospace

    @get:Rule val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_5)

    @Composable private fun Frame(title: String, body: @Composable ColumnScope.() -> Unit) {
        MaterialTheme(colorScheme = darkColorScheme(primary = G, background = BG, surface = Color(0xFF07140C), onSurface = G, onBackground = G)) {
            Surface(Modifier.fillMaxSize(), color = BG) {
                Column(Modifier.fillMaxSize().padding(14.dp)) {
                    Text(title, color = G, fontFamily = mono, fontSize = 22.sp, modifier = Modifier.padding(bottom = 10.dp))
                    body()
                }
            }
        }
    }
    @Composable private fun Btn(t: String, m: Modifier = Modifier) = Button({}, m.heightIn(min = 52.dp),
        colors = ButtonDefaults.buttonColors(containerColor = DIM, contentColor = G), shape = RoundedCornerShape(8.dp)) { Text(t, fontFamily = mono, fontSize = 15.sp) }

    @Test fun chat() = paparazzi.snapshot {
        Frame("DRON.exe · Чат") {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Скануй мою мережу і знайди ТВ", Modifier.fillMaxWidth().background(Color(0xFF0B2A16), RoundedCornerShape(8.dp)).padding(10.dp), color = G, fontFamily = mono, fontSize = 15.sp)
                Text("🛠 network_scan · done", Modifier.fillMaxWidth().background(Color(0xFF07140C), RoundedCornerShape(8.dp)).padding(10.dp), color = G, fontFamily = mono, fontSize = 13.sp)
                Text("Знайшов Samsung TV (192.168.1.44) та колонку Sonos. Увімкнути ТВ?", Modifier.fillMaxWidth().background(Color(0xFF07140C), RoundedCornerShape(8.dp)).padding(10.dp), color = G, fontFamily = mono, fontSize = 15.sp)
            }
            OutlinedTextField("", {}, Modifier.fillMaxWidth(), placeholder = { Text("> повідомлення…", fontFamily = mono) })
        }
    }
    @Test fun models() = paparazzi.snapshot {
        Frame("Моделі") {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                items(listOf("Топ", "Код", "Мислення", "Швидкі", "Безкоштовні", "Зір")) { c ->
                    FilterChip(c == "Топ", {}, { Text(c, fontFamily = mono, fontSize = 12.sp) },
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = DIM, selectedLabelColor = G, labelColor = G.copy(alpha = .6f)))
                }
            }
            listOf("openai/gpt-5.3-codex" to "Топовий код · ctx 400k · in \$1.50 · out \$10", "google/gemini-3.1-pro-preview" to "Мислення+зір · ctx 1M", "deepseek/deepseek-r1" to "Сильне мислення · дешево").forEach { (id, d) ->
                Card(Modifier.fillMaxWidth().padding(vertical = 3.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF07140C))) {
                    Column(Modifier.padding(10.dp)) { Text(id, color = G, fontFamily = mono, fontSize = 13.sp); Text(d, color = G.copy(alpha = .6f), fontFamily = mono, fontSize = 10.sp) }
                }
            }
            Btn("Оновити", Modifier.fillMaxWidth().padding(top = 8.dp))
        }
    }
    @Test fun tools() = paparazzi.snapshot {
        Frame("Інструменти") {
            Text("Веб-вхід (офіційні сайти, без скрапінгу токенів)", color = G.copy(alpha = .7f), fontFamily = mono, fontSize = 12.sp)
            Row(Modifier.padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Btn("ChatGPT", Modifier.weight(1f)); Btn("Grok", Modifier.weight(1f)); Btn("Gemini", Modifier.weight(1f))
            }
            Btn("Код-рев'ю (огляд коду)", Modifier.fillMaxWidth()); Spacer(Modifier.height(6.dp))
            Btn("Accessibility (дії в UI)", Modifier.fillMaxWidth()); Spacer(Modifier.height(6.dp))
            Btn("WRITE_SETTINGS (яскравість)", Modifier.fillMaxWidth())
        }
    }
    @Test fun network() = paparazzi.snapshot {
        Frame("Мережа / Пристрої") {
            Btn("Знайти пристрої (mDNS + SSDP)", Modifier.fillMaxWidth()); Spacer(Modifier.height(6.dp))
            Btn("Звіт безпеки мережі", Modifier.fillMaxWidth()); Spacer(Modifier.height(10.dp))
            Text("Пульт Android TV (ADB)", color = G, fontFamily = mono)
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Btn("▲", Modifier.width(90.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { Btn("◀", Modifier.width(70.dp)); Btn("OK", Modifier.width(70.dp)); Btn("▶", Modifier.width(70.dp)) }
                Btn("▼", Modifier.width(90.dp))
            }
        }
    }
    @Test fun settings() = paparazzi.snapshot {
        Frame("Налаштування") {
            Row(verticalAlignment = Alignment.CenterVertically) { Switch(true, {}); Text("Matrix «цифровий дощ»", color = G, fontFamily = mono) }
            Row(verticalAlignment = Alignment.CenterVertically) { Switch(true, {}); Text("Потокові відповіді", color = G, fontFamily = mono) }
            Text("Мова: українська (типово)", color = G.copy(alpha = .6f), fontFamily = mono, fontSize = 12.sp)
            Text("Безпека: ключ шифрується, лише HTTPS; вивід фільтрується від секретів.", color = G.copy(alpha = .6f), fontFamily = mono, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp))
        }
    }
    @Test fun agenticPlan() = paparazzi.snapshot {
        Frame("DRON.exe · Агентний план") {
            Column(Modifier.fillMaxWidth().background(Color(0xFF07140C), RoundedCornerShape(8.dp)).padding(8.dp)) {
                Text("▸ План агента", color = G, fontFamily = mono, fontSize = 12.sp)
                Text("✓ 1. Сканувати локальну мережу", color = G, fontFamily = mono, fontSize = 11.sp)
                Text("⏵ 2. Додати ТВ у інвентар з міткою", color = Color(0xFFFFC107), fontFamily = mono, fontSize = 11.sp)
                Text("• 3. Сформувати звіт", color = G.copy(alpha = .6f), fontFamily = mono, fontSize = 11.sp)
            }
            Spacer(Modifier.height(8.dp))
            Text("Увімкни ТВ у вітальні", Modifier.fillMaxWidth().background(Color(0xFF0B2A16), RoundedCornerShape(8.dp)).padding(10.dp), color = G, fontFamily = mono, fontSize = 15.sp)
            Text("Готово. ТВ увімкнено.", Modifier.fillMaxWidth().background(Color(0xFF07140C), RoundedCornerShape(8.dp)).padding(10.dp), color = G, fontFamily = mono, fontSize = 15.sp)
            Text("⛁ 1240→320 tok · \$0.00180", color = G.copy(alpha = .5f), fontFamily = mono, fontSize = 10.sp, modifier = Modifier.padding(start = 4.dp, top = 2.dp))
        }
    }
    @Test fun inventory() = paparazzi.snapshot {
        Frame("Мережа · Інвентар") {
            Text("Інвентар пристроїв (з мітками)", color = G, fontFamily = mono, fontSize = 13.sp)
            listOf("Samsung TV" to "192.168.1.44  ·  a4:xx", "Sonos колонка" to "192.168.1.51", "NAS сервер" to "192.168.1.10").forEach { (lbl, ip) ->
                Card(Modifier.fillMaxWidth().padding(vertical = 3.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF07140C))) {
                    Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) { Text(lbl, color = G, fontFamily = mono, fontSize = 14.sp); Text(ip, color = G.copy(alpha = .6f), fontFamily = mono, fontSize = 10.sp) }
                        Text("✕", color = G)
                    }
                }
            }
            Spacer(Modifier.height(6.dp)); Btn("Зберегти пристрій", Modifier.fillMaxWidth())
        }
    }
    @Test fun matrixRain() = paparazzi.snapshot {
        Surface(Modifier.fillMaxSize(), color = BG) { MatrixRain(true, Modifier.fillMaxSize()) }
    }
    @Test fun localModelV34() = paparazzi.snapshot {
        Frame("Налаштування · v3.4") {
            Text("Профілі дозволів інструментів", color = G, fontFamily = mono, fontSize = 15.sp)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 6.dp)) {
                items(listOf("Повний", "Безпечний", "Лише читання")) { c ->
                    FilterChip(c == "Безпечний", {}, { Text(c, fontFamily = mono, fontSize = 11.sp) },
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = DIM, selectedLabelColor = G, labelColor = G.copy(alpha = .6f)))
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) { Switch(true, {}); Text("  Фоновий LAN-моніторинг (приватні IP, лише сповіщення)", color = G, fontFamily = mono, fontSize = 12.sp) }
            Divider(color = DIM, modifier = Modifier.padding(vertical = 8.dp))
            Text("Локальна модель (офлайн)", color = G, fontFamily = mono, fontSize = 15.sp)
            listOf("Qwen2.5-0.5B-Instruct (Q4)" to "~400 МБ · SHA-256 перевірка", "SmolLM2-360M-Instruct (Q4)" to "~270 МБ · офлайн").forEach { (n, d) ->
                Card(Modifier.fillMaxWidth().padding(vertical = 3.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF07140C))) {
                    Column(Modifier.padding(10.dp)) {
                        Text(n, color = G, fontFamily = mono, fontSize = 13.sp)
                        Text(d, color = G.copy(alpha = .6f), fontFamily = mono, fontSize = 10.sp)
                        Btn("Завантажити", Modifier.padding(top = 4.dp))
                    }
                }
            }
            Text("Рушій інференсу (llama.cpp JNI) ще не вшито — резерв OpenRouter.", color = G.copy(alpha = .6f), fontFamily = mono, fontSize = 10.sp)
        }
    }
}