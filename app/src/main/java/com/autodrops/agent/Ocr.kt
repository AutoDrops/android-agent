package com.autodrops.agent

/** OCR text post-processing + agent presentation. Pure & testable (the ML Kit call lives in ScreenCapture.kt). */
object Ocr {
    /** Normalize recognized text: strip control chars, collapse whitespace, drop empty/noise lines. */
    fun cleanup(raw: String): String {
        return raw
            .replace(Regex("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F\\u007F]"), "")
            .lines()
            .map { it.replace(Regex("[ \\t]+"), " ").trim() }
            .filter { it.isNotBlank() && it.any { ch -> ch.isLetterOrDigit() } }
            .joinToString("\n")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
    }

    /** Age of a capture in whole seconds (>=0), or null when never captured. */
    fun ageSeconds(capturedAt: Long?, now: Long): Long? =
        capturedAt?.takeIf { it > 0 }?.let { ((now - it).coerceAtLeast(0L)) / 1000L }

    /** A capture older than maxAgeSec is stale and should be re-taken. */
    fun isStale(capturedAt: Long?, now: Long, maxAgeSec: Int): Boolean {
        val age = ageSeconds(capturedAt, now) ?: return true
        return age > maxAgeSec
    }

    private fun ageLabel(sec: Long): String = when {
        sec < 60 -> "$sec с тому"
        sec < 3600 -> "${sec / 60} хв тому"
        else -> "${sec / 3600} год тому"
    }

    /**
     * Format OCR text for the agent/model, with capture age, staleness warning and length cap.
     * Pure: caller supplies text, capture time, now, limits. Never fabricates text.
     */
    fun formatForAgent(
        text: String?,
        capturedAt: Long?,
        now: Long,
        maxAgeSec: Int = 300,
        maxChars: Int = 8000
    ): String {
        if (text.isNullOrBlank()) {
            return "Немає захопленого екрана. Натисни «Захопити екран (OCR)» у вкладці Інструменти та дай системний дозвіл."
        }
        val age = ageSeconds(capturedAt, now)
        val header = StringBuilder("Текст з екрана")
        if (age != null) header.append(" (захоплено ${ageLabel(age)})")
        if (isStale(capturedAt, now, maxAgeSec)) {
            header.append("\n⚠️ Знімок застарів (>${maxAgeSec / 60} хв) — зроби новий захват для свіжих даних.")
        }
        val body = if (text.length > maxChars) text.take(maxChars) + "\n…(обрізано, ${text.length} символів)" else text
        return "$header:\n$body"
    }
}
