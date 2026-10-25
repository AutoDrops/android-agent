package com.autodrops.agent

/**
 * v3.17 on-device self-test result modeling. Pure and unit-testable; the actual checks run in
 * AgentViewModel on real hardware and feed these models.
 */
enum class CheckStatus { PASS, FAIL, WARN, SKIP }

data class CheckResult(val name: String, val status: CheckStatus, val detail: String)

object SelfTest {
    fun icon(s: CheckStatus): String = when (s) {
        CheckStatus.PASS -> "✓"
        CheckStatus.FAIL -> "✗"
        CheckStatus.WARN -> "⚠"
        CheckStatus.SKIP -> "–"
    }

    /** Overall status: FAIL if any failed, else WARN if any warning, else PASS (SKIP ignored). */
    fun overall(results: List<CheckResult>): CheckStatus = when {
        results.any { it.status == CheckStatus.FAIL } -> CheckStatus.FAIL
        results.any { it.status == CheckStatus.WARN } -> CheckStatus.WARN
        results.all { it.status == CheckStatus.SKIP } && results.isNotEmpty() -> CheckStatus.SKIP
        else -> CheckStatus.PASS
    }

    /** Short tally, e.g. "✓4 ✗1 ⚠2 –1". */
    fun summary(results: List<CheckResult>): String {
        val p = results.count { it.status == CheckStatus.PASS }
        val f = results.count { it.status == CheckStatus.FAIL }
        val w = results.count { it.status == CheckStatus.WARN }
        val s = results.count { it.status == CheckStatus.SKIP }
        return "✓$p ✗$f ⚠$w –$s"
    }
}
