package com.autodrops.agent

/** Per-tool permission profiles. Computes the disabled-tools set for a named profile. */
object ToolProfiles {
    val PROFILES = listOf("full", "safe", "read_only")

    /** Given each tool's base risk, return the set of tool names that should be DISABLED under a profile. */
    fun disabledFor(profile: String, tools: List<Pair<String, Risk>>): Set<String> = when (profile) {
        "full" -> emptySet()
        // safe: block the most dangerous classes (HIGH/ELEVATED) but keep reads + routine effects
        "safe" -> tools.filter { it.second == Risk.HIGH || it.second == Risk.ELEVATED }.map { it.first }.toSet()
        // read_only: allow only LOW-risk read tools, disable everything that writes/affects/captures
        "read_only" -> tools.filter { it.second != Risk.LOW }.map { it.first }.toSet()
        else -> emptySet()
    }

    fun label(profile: String): String = when (profile) {
        "full" -> "Повний (усі інструменти)"
        "safe" -> "Безпечний (без HIGH/ELEVATED)"
        "read_only" -> "Лише читання (тільки LOW)"
        else -> profile
    }
}
