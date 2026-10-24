package com.autodrops.agent

/** Privacy firewall + host policy. Pure, unit-testable. */
object Security {

    /** Hosts the API key may be sent to without a warning (official OpenAI-compatible providers). */
    val ALLOWLISTED_HOSTS = setOf(
        "openrouter.ai", "api.openai.com", "api.x.ai", "generativelanguage.googleapis.com",
        "api.anthropic.com", "api.groq.com", "api.deepseek.com", "api.mistral.ai", "api.together.xyz"
    )

    data class HostVerdict(val allowed: Boolean, val https: Boolean, val host: String?, val warning: String?)

    /** Decide whether it's safe to send the API key to baseUrl. */
    fun checkHost(baseUrl: String): HostVerdict {
        val host = runCatching { java.net.URI(baseUrl).host?.lowercase() }.getOrNull()
        val https = baseUrl.startsWith("https://")
        if (!https) return HostVerdict(false, false, host,
            "Небезпечно: адреса не HTTPS. Ключ не буде надіслано відкритим каналом.")
        if (host == null) return HostVerdict(false, true, null, "Невірна адреса сервера.")
        val known = ALLOWLISTED_HOSTS.any { host == it || host.endsWith(".$it") }
        return if (known) HostVerdict(true, true, host, null)
        else HostVerdict(true, true, host,
            "Увага: $host не є у списку відомих провайдерів. Ваш ключ буде надіслано на цей сервер. Продовжуйте лише якщо довіряєте йому.")
    }

    // ---- Secret redaction of tool output before it goes back to the model / provider ----
    private val PATTERNS = listOf(
        Regex("sk-[A-Za-z0-9_-]{16,}"),                        // OpenAI-style keys
        Regex("sk-or-v1-[A-Za-z0-9]{16,}"),                    // OpenRouter keys
        Regex("xai-[A-Za-z0-9]{16,}"),                         // xAI keys
        Regex("ghp_[A-Za-z0-9]{20,}"),                         // GitHub PAT
        Regex("AIza[A-Za-z0-9_-]{20,}"),                       // Google API key
        Regex("(?i)bearer\\s+[A-Za-z0-9._-]{20,}"),            // bearer tokens
        Regex("eyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{6,}"), // JWT
        Regex("-----BEGIN[A-Z ]*PRIVATE KEY-----[\\s\\S]*?-----END[A-Z ]*PRIVATE KEY-----"), // PEM
        Regex("(?i)(api[_-]?key|secret|password|passwd|token)\\s*[:=]\\s*[\"']?[A-Za-z0-9._/+-]{8,}")
    )

    /** Shannon entropy for a token, used to catch generic high-entropy secrets. */
    fun entropy(s: String): Double {
        if (s.isEmpty()) return 0.0
        val freq = s.groupingBy { it }.eachCount()
        return freq.values.sumOf { c -> val p = c.toDouble() / s.length; -p * (Math.log(p) / Math.log(2.0)) }
    }

    fun redact(text: String): String {
        var out = text
        for (p in PATTERNS) out = p.replace(out) { "[REDACTED]" }
        // high-entropy long tokens (likely secrets)
        out = Regex("[A-Za-z0-9+/_=-]{32,}").replace(out) { m ->
            if (entropy(m.value) >= 4.0) "[REDACTED]" else m.value
        }
        return out
    }

    fun wasRedacted(original: String, redacted: String) = original != redacted
}
