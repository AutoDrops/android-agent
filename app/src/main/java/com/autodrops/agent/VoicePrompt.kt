package com.autodrops.agent

/**
 * v3.18: proactive offline-voice model-download prompt (previously DEFERRED).
 *
 * Pure decision used by the mic button: before starting push-to-talk, decide whether to proactively
 * offer downloading a Vosk offline model instead of silently using (or failing on) the system
 * recognizer. Pure JVM logic, unit-tested.
 */
object VoicePrompt {
    /**
     * Offer a download when the user wants offline voice (Vosk enabled and/or strict-offline) but no
     * Vosk model is installed yet. In strict-offline mode we must offer (system STT is forbidden),
     * even if the Vosk toggle itself is off.
     */
    fun shouldOfferDownload(voskEnabled: Boolean, strictOffline: Boolean, modelInstalled: Boolean): Boolean {
        if (modelInstalled) return false
        return voskEnabled || strictOffline
    }

    fun offerMessage(modelName: String): String =
        "Для офлайн-голосу потрібна модель Vosk «$modelName». Завантажити зараз? " +
        "(Без неї" + " буде використано системний розпізнавач, якщо не ввімкнено строгий офлайн.)"
}
