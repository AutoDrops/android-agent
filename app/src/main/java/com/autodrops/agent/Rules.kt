package com.autodrops.agent

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import androidx.work.*
import java.util.concurrent.TimeUnit

/** Schedules the periodic rules tick. WorkManager minimum period is 15 minutes. */
object RuleScheduler {
    private const val WORK = "dron_rules_tick"
    fun ensureScheduled(ctx: Context) {
        val req = PeriodicWorkRequestBuilder<RuleWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().build()).build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, req)
    }
    fun cancel(ctx: Context) { WorkManager.getInstance(ctx).cancelUniqueWork(WORK) }
}

/**
 * Evaluates enabled rules. Rules only ever READ device state and post a NOTIFICATION here.
 * A rule whose action is run_prompt is NOT executed autonomously — it is queued and still passes the
 * in-app risk-confirmation policy next time the app is opened (no silent tool execution in background).
 */
class RuleWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val db = AppDb.get(applicationContext)
        val now = System.currentTimeMillis()
        val rules = runCatching { db.dao().enabledRules() }.getOrDefault(emptyList())
        for (r in rules) {
            // interval rules fire at most once per their own window, not every 15-min tick
            if (r.trigger == "interval" && !RulePolicy.isDue(r.lastRun, r.intervalMinutes, now)) continue
            val msg = evaluate(r) ?: continue
            notify(r.name, msg)
            runCatching { db.dao().update(r.copy(lastRun = now)) }
        }
        return Result.success()
    }

    private fun evaluate(r: Rule): String? = when (r.trigger) {
        "charging" -> if (isCharging()) "Пристрій заряджається." else null
        "wifi_ssid" -> currentSsid()?.let { if (r.param.isBlank() || it.contains(r.param, true)) "Підключено до Wi-Fi: $it" else null }
        "interval" -> "Періодична перевірка (кожні ${r.intervalMinutes} хв): ${currentSsid() ?: "немає Wi-Fi"}"
        "battery_low" -> { val t = RulePolicy.batteryThreshold(r.param); val lvl = batteryLevel(); if (RulePolicy.batteryLowTriggered(lvl, t)) "Низький заряд: $lvl% (поріг $t%)." else null }
        else -> null
    } ?.let { base ->
        // run_prompt never executes silently in the background — it is queued for in-app confirmation (risk-gated)
        if (r.action == "run_prompt" && r.allowedPrompt.isNotBlank())
            "$base\nЗапланований запит (ризик ${RulePolicy.risk(r.action)}) очікує підтвердження у застосунку." else base
    }

    private fun batteryLevel(): Int = runCatching {
        val bm = applicationContext.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }.getOrDefault(-1)

    private fun isCharging(): Boolean {
        val bm = applicationContext.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        return bm.isCharging
    }
    @Suppress("DEPRECATION")
    private fun currentSsid(): String? = runCatching {
        val wm = applicationContext.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wm.connectionInfo?.ssid?.trim('"')?.takeIf { it.isNotBlank() && it != "<unknown ssid>" }
    }.getOrNull()

    private fun notify(title: String, text: String) {
        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val ch = "dron_rules"
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(ch, "Правила DRON", NotificationManager.IMPORTANCE_DEFAULT))
        val pub = android.app.Notification.Builder(applicationContext, ch)
            .setContentTitle("DRON.exe").setContentText("Нове сповіщення правила").setSmallIcon(android.R.drawable.ic_dialog_info)
            .setVisibility(android.app.Notification.VISIBILITY_PUBLIC).build()
        val n = android.app.Notification.Builder(applicationContext, ch)
            .setContentTitle("DRON.exe · $title").setContentText(text).setSmallIcon(android.R.drawable.ic_dialog_info)
            .setVisibility(android.app.Notification.VISIBILITY_PRIVATE).setPublicVersion(pub).build()
        nm.notify(title.hashCode(), n)
    }
}
