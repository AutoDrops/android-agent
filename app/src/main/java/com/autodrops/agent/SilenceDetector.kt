package com.autodrops.agent

/**
 * v3.18: VAD (voice-activity-detection) auto-stop for voice input (previously DEFERRED).
 *
 * Pure, testable endpointing state machine. The audio layer feeds it RMS/dB samples (system
 * SpeechRecognizer.onRmsChanged) or partial-result activity (Vosk). Once speech has been detected,
 * a sustained drop below the speech threshold for [silenceMs] signals "user finished" so the caller
 * can stop listening automatically. If speech never starts, [preSpeechTimeoutMs] bounds the wait.
 *
 * HONESTY: this is pure decision logic (unit-tested). Real microphone RMS calibration on a physical
 * device is not verified in this sandbox; thresholds are conservative and configurable.
 */
class SilenceDetector(
    private val speechRms: Float = 2.0f,     // SpeechRecognizer RMS is roughly -2..10+ dB
    private val silenceMs: Long = 1500L,     // stop this long after speech stops
    private val preSpeechTimeoutMs: Long = 8000L, // give up if no speech ever starts
    private val minSpeechMs: Long = 300L     // require this much speech before auto-stopping
) {
    private var startedAt: Long = -1L
    private var speechStartedAt: Long = -1L
    private var lastSpeechAt: Long = -1L
    var speechDetected: Boolean = false; private set

    fun start(now: Long) { startedAt = now; speechStartedAt = -1L; lastSpeechAt = -1L; speechDetected = false }

    /** Feed an RMS/dB sample. */
    fun onRms(rms: Float, now: Long) {
        if (startedAt < 0) start(now)
        if (rms >= speechRms) markActivity(now)
    }

    /** Feed non-empty partial text activity (e.g. Vosk partial). */
    fun onActivity(now: Long) { if (startedAt < 0) start(now); markActivity(now) }

    private fun markActivity(now: Long) {
        if (!speechDetected) { speechDetected = true; speechStartedAt = now }
        lastSpeechAt = now
    }

    /** True when the caller should auto-stop recognition now. */
    fun shouldStop(now: Long): Boolean {
        if (startedAt < 0) return false
        if (speechDetected) {
            val spokenLong = now - speechStartedAt >= minSpeechMs
            val silentLong = lastSpeechAt >= 0 && now - lastSpeechAt >= silenceMs
            return spokenLong && silentLong
        }
        return now - startedAt >= preSpeechTimeoutMs
    }

    fun reset() { startedAt = -1L; speechStartedAt = -1L; lastSpeechAt = -1L; speechDetected = false }
}
