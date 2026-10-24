package com.autodrops.agent

/** OCR text post-processing. Pure & testable (the ML Kit call lives in ScreenCaptureActivity). */
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
}
