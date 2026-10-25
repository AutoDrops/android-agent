package com.autodrops.agent

/** PURE, unit-testable helpers for app management and host extraction from control URLs. */
object AppsFmt {
    data class AppRow(val label: String, val pkg: String)

    /** Valid Android package name (used before launching anything). */
    fun isValidPackage(pkg: String): Boolean =
        Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$").matches(pkg.trim())

    fun format(apps: List<AppRow>): String {
        if (apps.isEmpty()) return "Немає запускних застосунків."
        val sorted = apps.sortedBy { it.label.lowercase() }
        return "Встановлено ${sorted.size} запускних застосунків:\n" +
            sorted.joinToString("\n") { "• ${it.label}  [${it.pkg}]" }
    }

    /** Extract the host from a control URL (for the RFC1918 guard on DLNA targets). */
    fun hostFromUrl(url: String): String? = runCatching {
        java.net.URI(url.trim()).host
    }.getOrNull()?.takeIf { it.isNotBlank() }
}
