package com.autodrops.agent

import android.content.Context
import android.content.pm.PackageManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Pure, unit-testable helpers for phone-communication tools (SMS / notifications)
 * plus a fail-closed runtime-permission gate. No side effects here.
 */
object Perms {
    /** Pure: which of [needed] are absent from [granted] (fail-closed basis). */
    fun missing(granted: Set<String>, needed: List<String>): List<String> = needed.filter { it !in granted }

    fun has(ctx: Context, perm: String): Boolean =
        ctx.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED

    /** Returns null when all [perms] are granted, else a fail-closed message naming the missing ones. */
    fun require(ctx: Context, vararg perms: String): String? {
        val miss = perms.filter { !has(ctx, it) }
        return if (miss.isEmpty()) null
        else "⛔ Дозвіл не надано: ${miss.joinToString(", ") { it.substringAfterLast('.') }}. Надай його в налаштуваннях Android і повтори."
    }
}

object SmsFormat {
    data class Msg(val address: String, val body: String, val epochMs: Long)

    fun format(msgs: List<Msg>, limit: Int = 30): String {
        if (msgs.isEmpty()) return "no SMS"
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        return msgs.take(limit.coerceIn(1, 100)).joinToString("\n") {
            val who = sanitizeForDisplay(it.address.ifBlank { "?" })
            "[${fmt.format(Date(it.epochMs))}] $who: ${sanitizeForDisplay(it.body).take(400)}"
        }
    }

    /** Accepts digits plus + - ( ) and spaces; must contain at least one digit; bounded length. */
    fun validNumber(n: String): Boolean =
        n.isNotBlank() && n.length <= 20 && n.any { it.isDigit() } && n.all { it.isDigit() || it in "+ -()" }

    fun validMessage(m: String): Boolean = m.isNotEmpty() && m.length <= 1600
}
