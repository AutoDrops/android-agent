package com.autodrops.agent

/**
 * Pure, testable policy for proactive WorkManager rules.
 * No Android imports here so it is unit-testable without Robolectric.
 * The actual notification / scheduling lives in Rules.kt (RuleWorker/RuleScheduler).
 */
object RulePolicy {
    /** Supported triggers (id -> UA label). */
    val TRIGGERS: List<Pair<String, String>> = listOf(
        "charging" to "Заряджання",
        "wifi_ssid" to "Wi-Fi SSID",
        "interval" to "Періодично",
        "battery_low" to "Низький заряд"
    )

    /** Supported actions (id -> UA label). */
    val ACTIONS: List<Pair<String, String>> = listOf(
        "notify" to "Сповіщення",
        "run_prompt" to "Запит агенту"
    )

    /** WorkManager minimum period in minutes. */
    const val MIN_INTERVAL = 15

    /**
     * Deterministic risk of a rule's action — drives in-app confirmation, never the model.
     * A background `run_prompt` can drive tools, so it is never silently autonomous: HIGH.
     * A plain notification only reads state and posts locally: LOW.
     */
    fun risk(action: String): Risk = when (action) {
        "run_prompt" -> Risk.HIGH
        else -> Risk.LOW
    }

    /** A run_prompt rule is only ever queued for in-app confirmation, never executed in the background. */
    fun runsInBackground(action: String): Boolean = action == "notify"

    /** Interval rules fire at most once per intervalMinutes window, regardless of the 15-min tick. */
    fun isDue(lastRun: Long, intervalMinutes: Int, now: Long): Boolean {
        if (lastRun <= 0L) return true
        val periodMs = intervalMinutes.coerceAtLeast(MIN_INTERVAL) * 60_000L
        return now - lastRun >= periodMs
    }

    fun nextDueMillis(lastRun: Long, intervalMinutes: Int): Long {
        val periodMs = intervalMinutes.coerceAtLeast(MIN_INTERVAL) * 60_000L
        return if (lastRun <= 0L) 0L else lastRun + periodMs
    }

    /** True when the current battery level is at or below the configured threshold. */
    fun batteryLowTriggered(levelPercent: Int, thresholdPercent: Int): Boolean {
        if (levelPercent < 0) return false
        val t = if (thresholdPercent in 1..100) thresholdPercent else 20
        return levelPercent <= t
    }

    /** Parse a battery threshold from a rule param, defaulting to 20%. */
    fun batteryThreshold(param: String): Int = param.trim().toIntOrNull()?.coerceIn(1, 100) ?: 20

    /** Validate a rule before persisting; returns an error message or null when OK. */
    fun validate(name: String, trigger: String, action: String, intervalMinutes: Int, param: String): String? {
        if (name.isBlank()) return "Назва правила обовʼязкова."
        if (TRIGGERS.none { it.first == trigger }) return "Невідомий тригер '$trigger'."
        if (ACTIONS.none { it.first == action }) return "Невідома дія '$action'."
        if (trigger == "interval" && intervalMinutes < MIN_INTERVAL) return "Інтервал мінімум $MIN_INTERVAL хв."
        if (trigger == "wifi_ssid" && param.isBlank()) return "Вкажи частину SSID."
        if (trigger == "battery_low" && param.isNotBlank() && param.trim().toIntOrNull() == null) return "Поріг заряду має бути числом 1-100."
        return null
    }
}
