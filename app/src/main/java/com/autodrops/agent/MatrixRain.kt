package com.autodrops.agent

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.random.Random

/** Subtle Matrix digital-rain background. Can be disabled. */
@Composable
fun MatrixRain(enabled: Boolean, modifier: Modifier = Modifier) {
    if (!enabled) return
    val glyphs = "ﾊﾐﾋｰｳｼﾅﾓﾆｻﾜｶ01ﾘﾎﾃﾏ日ﾖﾈﾇﾃ0123456789Z:.=*+-<>".toCharArray()
    var frame by remember { mutableStateOf(0) }
    val density = LocalDensity.current
    val colSpacingPx = with(density) { 14.dp.toPx() }
    val cellPx = with(density) { 16.dp.toPx() }
    val drops = remember { mutableStateMapOf<Int, Int>() }
    LaunchedEffect(Unit) { while (true) { delay(90); frame++ } }
    Canvas(modifier.fillMaxSize()) {
        val cols = (size.width / colSpacingPx).toInt().coerceAtLeast(1)
        val rows = (size.height / cellPx).toInt().coerceAtLeast(1)
        frame // observe
        drawIntoCanvas { c ->
            val paint = android.graphics.Paint().apply {
                textSize = cellPx * 0.9f; isAntiAlias = true
                typeface = android.graphics.Typeface.MONOSPACE
            }
            for (col in 0 until cols) {
                val head = drops.getOrPut(col) { Random.nextInt(0, rows) }
                for (t in 0 until 10) {
                    val row = head - t
                    if (row < 0 || row > rows) continue
                    val alpha = (255 * (1f - t / 10f) * 0.5f).toInt().coerceIn(0, 140)
                    paint.color = (if (t == 0) Color(0xFFB9FFCB) else Color(0xFF00FF66)).copy(alpha = alpha / 255f).toArgb()
                    val ch = glyphs[Random.nextInt(glyphs.size)]
                    c.nativeCanvas.drawText(ch.toString(), col * colSpacingPx, row * cellPx, paint)
                }
                drops[col] = if (head > rows + Random.nextInt(0, 8)) 0 else head + 1
            }
        }
    }
}
