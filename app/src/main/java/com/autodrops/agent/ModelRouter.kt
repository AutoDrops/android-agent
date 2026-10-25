package com.autodrops.agent

/**
 * v3.18: heuristic prompt-length-based model routing (previously DEFERRED).
 *
 * Pure, deterministic routing: when enabled, short prompts go to a cheaper/faster model and long
 * prompts go to a stronger/long-context model. When disabled (default), the user's chosen model is
 * always returned — fully backward-compatible. The model/history are never mutated; this only picks
 * the slug passed to the API for a given turn.
 *
 * HONESTY: this is pure JVM decision logic (unit-tested). It changes which model slug is requested;
 * actual latency/cost behavior on a real phone is not verified in this sandbox.
 */
object ModelRouter {
    /** Threshold is clamped to a sane range so a bad setting can never route pathologically. */
    const val MIN_THRESHOLD = 200
    const val MAX_THRESHOLD = 200_000
    const val DEFAULT_THRESHOLD = 4000

    /**
     * @param enabled        routing on/off (off => [defaultModel])
     * @param smallModel     slug for short prompts (blank => [defaultModel])
     * @param largeModel     slug for long prompts  (blank => [defaultModel])
     * @param thresholdChars prompt length (chars) at/above which [largeModel] is used
     * @param defaultModel   the user's currently-selected model (fallback for every blank/off case)
     * @param promptChars    length of the current user prompt in characters
     */
    fun choose(
        enabled: Boolean,
        smallModel: String,
        largeModel: String,
        thresholdChars: Int,
        defaultModel: String,
        promptChars: Int
    ): String {
        if (!enabled) return defaultModel
        val t = thresholdChars.coerceIn(MIN_THRESHOLD, MAX_THRESHOLD)
        val picked = if (promptChars >= t) largeModel else smallModel
        return picked.trim().ifBlank { defaultModel }
    }

    /** Human-readable, Ukrainian explanation of a routing decision (for the chat info line). */
    fun explain(chosen: String, defaultModel: String, promptChars: Int, thresholdChars: Int): String =
        if (chosen == defaultModel) ""
        else "Маршрутизація за довжиною: $promptChars символів " +
            (if (promptChars >= thresholdChars) "≥" else "<") + " $thresholdChars → $chosen"
}
