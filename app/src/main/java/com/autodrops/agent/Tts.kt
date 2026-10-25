package com.autodrops.agent

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/** PURE, unit-testable TTS policy: locale preference (Ukrainian first) and spoken-text shaping. */
object TtsPolicy {
    /** Preference order of language tags; first available wins. Ukrainian preferred. */
    val PREFERRED = listOf("uk", "uk-UA", "en", "en-US")

    /** Pick the best language tag from what the engine reports as available. Null = engine default. */
    fun pickLanguage(available: Set<String>): String? {
        for (p in PREFERRED) {
            val pl = p.lowercase()
            available.firstOrNull {
                val a = it.lowercase()
                a == pl || a.startsWith("$pl-") || a.startsWith("${pl}_")
            }?.let { return it }
        }
        return null
    }

    /** Strip markdown/code noise and cap length so spoken replies stay sane. */
    fun forSpeech(text: String, maxChars: Int = 2000): String {
        val cleaned = text
            .replace(Regex("```[\\s\\S]*?```"), " фрагмент коду ")
            .replace(Regex("[`*_#>|]"), "")
            .replace(Regex("https?://\\S+"), " посилання ")
            .replace(Regex("\\s+"), " ")
            .trim()
        return if (cleaned.length > maxChars) cleaned.take(maxChars) + "…" else cleaned
    }

    fun shouldSpeak(enabled: Boolean, text: String): Boolean =
        enabled && forSpeech(text).isNotBlank()
}

/** Android TextToSpeech wrapper. On-device system TTS; prefers Ukrainian voice when installed. */
class Speaker(context: Context) {
    @Volatile private var ready = false
    @Volatile var currentLang: String = "default"; private set
    private lateinit var engine: TextToSpeech

    init {
        engine = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                applyLocale()
                ready = true
            }
        }
    }

    private fun applyLocale() {
        // Prefer Ukrainian when the engine has it; otherwise fall back to device default.
        val candidates = listOf(Locale("uk", "UA"), Locale("uk"), Locale.US, Locale.getDefault())
        for (loc in candidates) {
            val r = runCatching { engine.isLanguageAvailable(loc) }.getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)
            if (r >= TextToSpeech.LANG_AVAILABLE) {
                engine.language = loc
                currentLang = loc.toLanguageTag()
                return
            }
        }
        engine.language = Locale.getDefault()
        currentLang = Locale.getDefault().toLanguageTag()
    }

    fun speak(text: String): String {
        val say = TtsPolicy.forSpeech(text)
        if (say.isBlank()) return "Нічого озвучувати."
        if (!ready) return "TTS ще ініціалізується — спробуй ще раз за мить."
        engine.speak(say, TextToSpeech.QUEUE_FLUSH, null, "dron-" + System.currentTimeMillis())
        return "Озвучую (${currentLang}): ${say.take(80)}"
    }

    fun stop() { if (::engine.isInitialized) runCatching { engine.stop() } }
    fun shutdown() { if (::engine.isInitialized) runCatching { engine.stop(); engine.shutdown() } }
}
