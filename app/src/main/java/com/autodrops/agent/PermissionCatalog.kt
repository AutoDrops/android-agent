package com.autodrops.agent

import android.Manifest
import android.os.Build

/**
 * v3.17 permission onboarding model. Pure list + SDK filtering + status mapping, unit-testable.
 * Rationale strings explain in Ukrainian why each runtime permission is requested.
 */
data class PermSpec(
    val permission: String,
    val uaName: String,
    val rationale: String,
    val minSdk: Int = 0,
    val maxSdkExclusive: Int = Int.MAX_VALUE
)

object PermissionCatalog {
    val ALL: List<PermSpec> = listOf(
        PermSpec(Manifest.permission.ACCESS_FINE_LOCATION, "Місцезнаходження",
            "Потрібне для сканування Wi-Fi та виявлення пристроїв у твоїй локальній мережі."),
        PermSpec(Manifest.permission.READ_CONTACTS, "Контакти",
            "Щоб інструмент contacts міг шукати контакти за іменем на твій запит."),
        PermSpec(Manifest.permission.RECORD_AUDIO, "Мікрофон",
            "Для голосового вводу (push-to-talk), у т.ч. офлайн через Vosk."),
        PermSpec(Manifest.permission.READ_SMS, "Читання SMS",
            "Щоб інструмент sms_read міг читати вхідні SMS на твій запит."),
        PermSpec(Manifest.permission.SEND_SMS, "Надсилання SMS",
            "Для прямого надсилання SMS (sms_send) — завжди за підтвердженням."),
        PermSpec(Manifest.permission.NEARBY_WIFI_DEVICES, "Пристрої поруч (Wi-Fi)",
            "Android 13+: сканування мережі без доступу до геолокації.", minSdk = 33),
        PermSpec(Manifest.permission.POST_NOTIFICATIONS, "Сповіщення",
            "Android 13+: показ локальних сповіщень від агента та правил.", minSdk = 33)
    )

    /** Runtime permissions appropriate for the device SDK level. */
    fun forSdk(sdkInt: Int): List<PermSpec> = ALL.filter { sdkInt >= it.minSdk && sdkInt < it.maxSdkExclusive }

    fun forThisDevice(): List<PermSpec> = forSdk(Build.VERSION.SDK_INT)

    fun statusLabel(granted: Boolean): String = if (granted) "надано" else "не надано"
    fun statusCheck(granted: Boolean): CheckStatus = if (granted) CheckStatus.PASS else CheckStatus.WARN
    fun shortName(perm: String): String = perm.substringAfterLast('.')
}
