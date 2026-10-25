package com.autodrops.agent

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** v3.17: shown after a caught crash. Lets Bohdan read/copy the report and relaunch the app. */
class RecoveryActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val green = Color(0xFF00FF66); val bg = Color(0xFF000000); val mono = FontFamily.Monospace
        val report = CrashReport.last(this) ?: "Звіт про збій недоступний."
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = green, background = bg, surface = Color(0xFF07140C), onPrimary = bg, onSurface = green, onBackground = green)) {
                Surface(Modifier.fillMaxSize(), color = bg) {
                    Column(Modifier.fillMaxSize().systemBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("DRON.exe — відновлення", color = green, fontFamily = mono, fontSize = 20.sp)
                        Text("Застосунок перехопив збій і не впав повністю. Звіт збережено локально (ключі не містяться).",
                            color = green.copy(alpha = 0.7f), fontFamily = mono, fontSize = 12.sp)
                        Text("⚠ ${CrashReport.shortHeadline(report)}", color = Color(0xFFFFC107), fontFamily = mono, fontSize = 13.sp)
                        SelectionContainer(Modifier.weight(1f)) {
                            Text(report, Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).background(Color(0xFF07140C)).padding(10.dp),
                                color = green.copy(alpha = 0.9f), fontFamily = mono, fontSize = 11.sp)
                        }
                        val ctx = LocalContext.current
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = {
                                val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                cm.setPrimaryClip(android.content.ClipData.newPlainText("crash", report))
                            }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0A3A1A), contentColor = green)) { Text("Копіювати", fontFamily = mono) }
                            Button(onClick = { CrashReport.clear(ctx) }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0A3A1A), contentColor = green)) { Text("Очистити", fontFamily = mono) }
                            Button(onClick = {
                                startActivity(Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                                finish()
                            }, colors = ButtonDefaults.buttonColors(containerColor = green, contentColor = bg)) { Text("Перезапустити", fontFamily = mono) }
                        }
                    }
                }
            }
        }
    }
}
