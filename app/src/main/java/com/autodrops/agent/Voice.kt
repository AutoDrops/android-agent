package com.autodrops.agent

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import org.json.JSONObject

/**
 * Push-to-talk speech-to-text. Prefers Vosk OFFLINE recognition when a Vosk model is installed and
 * enabled; otherwise falls back to the system SpeechRecognizer (online/Google, device-dependent).
 * Vosk path is fully on-device (no audio leaves the phone).
 */
class VoiceInput(private val ctx: Context) {
    private var sr: SpeechRecognizer? = null
    // Vosk handles (typed; native lib loads lazily on first use).
    private var voskModel: org.vosk.Model? = null
    private var voskService: org.vosk.android.SpeechService? = null
    @Volatile var lastEngine: String = "system"; private set
    // v3.18: VAD auto-stop state (pure detector). Null when VAD disabled for this session.
    private var vad: SilenceDetector? = null

    val systemAvailable: Boolean get() = SpeechRecognizer.isRecognitionAvailable(ctx)

    /** True when a Vosk model directory is present so offline STT can be used. */
    fun voskAvailable(store: SecureStore, mgr: VoskManager): Boolean {
        if (!store.voskEnabled) return false
        val m = VoskCatalog.byId(store.voskModelId) ?: return false
        return mgr.isInstalled(m)
    }

    fun start(store: SecureStore, mgr: VoskManager,
              onPartial: (String) -> Unit, onFinal: (String) -> Unit, onError: (String) -> Unit) {
        if (voskAvailable(store, mgr)) {
            val m = VoskCatalog.byId(store.voskModelId)!!
            val path = mgr.resolvedModelPath(m)
            if (path != null && startVosk(path.absolutePath, onPartial, onFinal, onError)) { lastEngine = "vosk"; return }
        }
        if (store.voskStrict) { onError("Строгий офлайн-режим: немає моделі Vosk. Завантаж модель у налаштуваннях."); return }
        lastEngine = "system"
        val lang = VoskCatalog.byId(store.voskModelId)?.lang?.let { when (it) { "uk" -> "uk-UA"; "ru" -> "ru-RU"; "en" -> "en-US"; else -> "uk-UA" } } ?: "uk-UA"
        startSystem(lang, onPartial, onFinal, onError, store.vadAutoStop)
    }

    private fun startVosk(modelPath: String, onPartial: (String) -> Unit, onFinal: (String) -> Unit, onError: (String) -> Unit): Boolean {
        return try {
            stop()
            val model = org.vosk.Model(modelPath)
            val rec = org.vosk.Recognizer(model, 16000.0f)
            val svc = org.vosk.android.SpeechService(rec, 16000.0f)
            svc.startListening(object : org.vosk.android.RecognitionListener {
                override fun onPartialResult(hypothesis: String?) {
                    runCatching { JSONObject(hypothesis ?: "{}").optString("partial") }.getOrNull()?.takeIf { it.isNotBlank() }?.let(onPartial)
                }
                override fun onResult(hypothesis: String?) {
                    runCatching { JSONObject(hypothesis ?: "{}").optString("text") }.getOrNull()?.takeIf { it.isNotBlank() }?.let(onFinal)
                }
                override fun onFinalResult(hypothesis: String?) {
                    runCatching { JSONObject(hypothesis ?: "{}").optString("text") }.getOrNull()?.takeIf { it.isNotBlank() }?.let(onFinal)
                    stop()
                }
                override fun onError(e: Exception?) { onError("Vosk: " + Protocols.friendlyError(e?.message)); stop() }
                override fun onTimeout() { stop() }
            })
            voskModel = model; voskService = svc; true
        } catch (t: Throwable) { onError("Vosk недоступний: " + Protocols.friendlyError(t.message)); stopVosk(); false }
    }

    private fun startSystem(lang: String = "uk-UA", onPartial: (String) -> Unit, onFinal: (String) -> Unit, onError: (String) -> Unit, vadAutoStop: Boolean = false) {
        if (!systemAvailable) { onError("Розпізнавання мови недоступне на цьому пристрої."); return }
        stop()
        vad = if (vadAutoStop) SilenceDetector().also { it.start(System.currentTimeMillis()) } else null
        try {
        sr = SpeechRecognizer.createSpeechRecognizer(ctx).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onResults(b: Bundle?) {
                    val t = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty(); onFinal(t); stop()
                }
                override fun onPartialResults(b: Bundle?) {
                    b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let(onPartial)
                }
                override fun onError(error: Int) { onError("Помилка розпізнавання ($error)"); stop() }
                override fun onReadyForSpeech(p: Bundle?) { vad?.start(System.currentTimeMillis()) }
                override fun onBeginningOfSpeech() { vad?.onActivity(System.currentTimeMillis()) }
                override fun onRmsChanged(v: Float) {
                    val d = vad ?: return
                    val now = System.currentTimeMillis()
                    d.onRms(v, now)
                    // VAD auto-stop: end listening on a sustained silence after speech; stopListening
                    // flushes the final result through onResults (no audio ever leaves on the Vosk path).
                    if (d.shouldStop(now)) { vad = null; runCatching { sr?.stopListening() } }
                }
                override fun onBufferReceived(b: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onEvent(e: Int, b: Bundle?) {}
            })
        }
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
        sr?.startListening(i)
        } catch (t: Throwable) { onError("Мікрофон недоступний: " + Protocols.friendlyError(t.message)); stop() }
    }

    private fun stopVosk() {
        runCatching { voskService?.stop(); voskService?.shutdown() }; voskService = null
        runCatching { voskModel?.close() }; voskModel = null
    }
    fun stop() {
        vad = null
        runCatching { sr?.stopListening(); sr?.destroy() }; sr = null
        stopVosk()
    }

    // Backward-compatible overload (system-only), kept so older call sites still compile.
    fun start(onPartial: (String) -> Unit, onFinal: (String) -> Unit, onError: (String) -> Unit) =
        startSystem("uk-UA", onPartial, onFinal, onError)
}
