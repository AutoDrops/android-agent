package com.autodrops.agent

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * v3.17 global crash safety. Pure formatting is unit-tested; the installer writes the report to a
 * local file and launches a recovery screen instead of letting the process hard-crash.
 */
object CrashReport {
    /** PURE: render a stable, human-readable crash report. No Android deps so it is unit-testable. */
    fun format(
        threadName: String,
        throwableClass: String,
        message: String?,
        stack: List<String>,
        epochMs: Long,
        appVersion: String = "",
        deviceModel: String = "",
        sdkInt: Int = 0
    ): String {
        val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(epochMs))
        return buildString {
            appendLine("DRON.exe crash report")
            appendLine("time: $ts")
            if (appVersion.isNotBlank()) appendLine("version: $appVersion")
            if (deviceModel.isNotBlank()) appendLine("device: $deviceModel")
            if (sdkInt > 0) appendLine("android_sdk: $sdkInt")
            appendLine("thread: ${threadName.ifBlank { "?" }}")
            appendLine("type: ${throwableClass.ifBlank { "?" }}")
            appendLine("message: ${sanitizeForDisplay((message ?: "").ifBlank { "(none)" })}")
            appendLine("--- stack ---")
            if (stack.isEmpty()) appendLine("(no stack)") else stack.take(60).forEach { appendLine("  at $it") }
        }.trim()
    }

    /** PURE overload taking a Throwable; walks the cause chain into flat stack lines. */
    fun format(threadName: String, t: Throwable, epochMs: Long, appVersion: String = "", deviceModel: String = "", sdkInt: Int = 0): String {
        val lines = ArrayList<String>()
        var cur: Throwable? = t
        var depth = 0
        while (cur != null && depth < 6) {
            if (depth > 0) lines.add("Caused by: ${cur.javaClass.name}: ${cur.message ?: ""}")
            cur.stackTrace.forEach { lines.add(it.toString()) }
            cur = cur.cause
            depth++
        }
        return format(threadName, t.javaClass.name, t.message, lines, epochMs, appVersion, deviceModel, sdkInt)
    }

    fun shortHeadline(report: String): String =
        report.lineSequence().firstOrNull { it.startsWith("type:") }?.removePrefix("type:")?.trim()
            ?: report.lineSequence().firstOrNull().orEmpty()

    private fun dir(ctx: Context) = File(ctx.filesDir, "crash").apply { mkdirs() }
    private fun file(ctx: Context) = File(dir(ctx), "last_crash.txt")

    fun save(ctx: Context, report: String) = runCatching { file(ctx).writeText(report) }
    fun last(ctx: Context): String? = runCatching { file(ctx).takeIf { it.exists() }?.readText() }.getOrNull()
    fun clear(ctx: Context) { runCatching { file(ctx).delete() } }
}
