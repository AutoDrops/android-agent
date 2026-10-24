package com.autodrops.agent

import org.json.JSONObject

/** Pure, testable validation for OpenRouter-style model slugs and request messages. */
object ModelValidation {
    /** OpenRouter model ids are "vendor/model" (e.g. anthropic/claude-haiku-5.5). */
    fun looksLikeOpenRouterSlug(id: String): Boolean = id.trim().contains('/') && !id.trim().startsWith("/") && !id.trim().endsWith("/")

    /**
     * Validate the selected model against the live catalog ids. Returns a clear error message, or null if OK.
     * If the slug is missing a vendor prefix but a unique match exists in the catalog, the suggestion names it.
     */
    fun validateSlug(idRaw: String, known: List<String>): String? {
        val id = idRaw.trim()
        if (id.isBlank()) return "Модель не вибрана. Обери модель у вкладці «Моделі»."
        if (known.isEmpty()) {
            // No live list to check against; only guard obviously malformed slugs.
            return if (looksLikeOpenRouterSlug(id)) null
            else "Схоже, slug «$id» неповний. OpenRouter очікує формат «vendor/model», напр. anthropic/claude-haiku-5.5."
        }
        if (known.contains(id)) return null
        val suffix = id.substringAfterLast('/')
        val matches = known.filter { it == id || it.substringAfterLast('/') == suffix || it.endsWith("/$id") }
        return if (matches.size == 1)
            "Модель «$id» недійсна для цього сервера. Схоже, ти мав на увазі «${matches[0]}» — обери її у вкладці «Моделі»."
        else if (matches.isNotEmpty())
            "Модель «$id» недійсна. Можливі збіги: ${matches.take(3).joinToString(", ")}. Обери у вкладці «Моделі»."
        else
            "Модель «$id» не знайдено у списку сервера. Натисни «Оновити» у «Моделі» та обери наявну модель."
    }

    /** True if the messages array is a valid, non-empty OpenAI-style conversation. */
    fun validateMessages(messages: org.json.JSONArray): String? {
        if (messages.length() == 0) return "Порожній запит: немає повідомлень."
        val roles = setOf("system", "user", "assistant", "tool")
        for (i in 0 until messages.length()) {
            val m = messages.optJSONObject(i) ?: return "Повідомлення #$i має неправильний формат."
            val role = m.optString("role")
            if (role !in roles) return "Повідомлення #$i має недопустиму роль «$role»."
        }
        return null
    }

    /** Extract a human-readable error from an OpenRouter/OpenAI JSON error body, else return the raw text. */
    fun extractError(httpCode: Int, body: String?): String {
        val raw = body?.trim().orEmpty()
        if (raw.isNotEmpty()) {
            runCatching {
                val o = JSONObject(raw)
                val err = o.opt("error")
                val msg = when (err) {
                    is JSONObject -> err.optString("message").ifBlank { err.optString("code") }
                    is String -> err
                    else -> o.optString("message")
                }
                if (!msg.isNullOrBlank()) return "HTTP $httpCode: $msg"
            }
            return "HTTP $httpCode: ${raw.take(500)}"
        }
        return "HTTP $httpCode"
    }
}
