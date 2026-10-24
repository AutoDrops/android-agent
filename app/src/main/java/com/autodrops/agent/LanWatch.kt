package com.autodrops.agent

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.work.*
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/**
 * Optional, user-enabled background worker that watches the SAVED private-IP device inventory and notifies
 * on new/absent devices. SECURITY: results NEVER go to the cloud LLM — the worker only pings private IPs
 * (NetGuard) and posts a local notification. Disabled by default; one switch turns it off.
 */
object LanWatchScheduler {
    private const val WORK = "dron_lan_watch"
    fun enable(ctx: Context) {
        val req = PeriodicWorkRequestBuilder<LanWatchWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build()).build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.UPDATE, req)
    }
    fun disable(ctx: Context) { WorkManager.getInstance(ctx).cancelUniqueWork(WORK) }
}

class LanWatchWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val db = AppDb.get(applicationContext)
        val devices = runCatching { db.dao().devices() }.getOrDefault(emptyList())
            .filter { NetGuard.isPrivateV4(it.ip) }                   // enforce private-IP-only
        if (devices.isEmpty()) return Result.success()
        val prefs = SecureStore.encrypted(applicationContext, "lan_watch")
        val prevOnline = prefs.getStringSet("online", emptySet()) ?: emptySet()
        val nowOnline = HashSet<String>()
        for (d in devices) {
            val up = runCatching { InetAddress.getByName(d.ip).isReachable(600) }.getOrDefault(false)
            if (up) nowOnline.add(d.ip)
        }
        val appeared = nowOnline - prevOnline
        val disappeared = prevOnline - nowOnline.toSet()
        fun name(ip: String) = devices.firstOrNull { it.ip == ip }?.let { it.label.ifBlank { it.ip } } ?: ip
        if (appeared.isNotEmpty()) notify("Нові пристрої у мережі", appeared.joinToString(", ") { name(it) })
        if (disappeared.isNotEmpty()) notify("Пристрої зникли з мережі", disappeared.joinToString(", ") { name(it) })
        prefs.edit().putStringSet("online", nowOnline).apply()
        return Result.success()
    }

    private fun notify(title: String, text: String) {
        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val ch = "dron_lanwatch"
        if (android.os.Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(ch, "LAN-моніторинг DRON", NotificationManager.IMPORTANCE_DEFAULT))
        val pub = android.app.Notification.Builder(applicationContext, ch)
            .setContentTitle("DRON.exe").setContentText("Зміни у локальній мережі").setSmallIcon(android.R.drawable.ic_dialog_info)
            .setVisibility(android.app.Notification.VISIBILITY_PUBLIC).build()
        val n = android.app.Notification.Builder(applicationContext, ch)
            .setContentTitle("DRON.exe · $title").setContentText(text).setSmallIcon(android.R.drawable.ic_dialog_info)
            .setVisibility(android.app.Notification.VISIBILITY_PRIVATE).setPublicVersion(pub).build()
        nm.notify(("lanwatch_$title").hashCode(), n)
    }
}
