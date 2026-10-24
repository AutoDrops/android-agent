package com.autodrops.agent

/** Per-message token/cost accounting. */
data class Usage(val promptTokens: Int, val completionTokens: Int, val cost: Double) {
    val totalTokens get() = promptTokens + completionTokens
    fun label(): String {
        val c = if (cost > 0) " · $" + String.format("%.5f", cost) else ""
        return "⛁ $promptTokens→$completionTokens tok$c"
    }
    operator fun plus(o: Usage) = Usage(promptTokens + o.promptTokens, completionTokens + o.completionTokens, cost + o.cost)
    companion object {
        val ZERO = Usage(0, 0, 0.0)
        /** Fallback client-side cost from per-token USD price strings (OpenRouter pricing format). */
        fun estimate(pt: Int, ct: Int, promptPrice: String, completionPrice: String): Double {
            val pp = promptPrice.toDoubleOrNull() ?: 0.0
            val cp = completionPrice.toDoubleOrNull() ?: 0.0
            return pt * pp + ct * cp
        }
    }
}
