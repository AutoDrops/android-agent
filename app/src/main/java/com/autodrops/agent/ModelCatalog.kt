package com.autodrops.agent

import org.json.JSONArray

/** Categorized model catalog with curated Ukrainian descriptions, built from /api/v1/models (+ seed). */
object ModelCatalog {
    data class Cat(val id: String, val uaTitle: String, val uaHint: String)
    val CATEGORIES = listOf(
        Cat("top", "Топ-моделі", "Найсильніші та найпопулярніші моделі зараз"),
        Cat("code", "Код", "Найкращі для програмування та рефакторингу"),
        Cat("reasoning", "Міркування", "Складні задачі, логіка, математика, планування"),
        Cat("fast", "Швидкі/дешеві", "Низька ціна та затримка для простих задач"),
        Cat("free", "Безкоштовні", "Моделі з нульовою ціною на OpenRouter"),
        Cat("vision", "Зір (vision)", "Розуміють зображення та скриншоти"),
        Cat("long", "Довгий контекст", "Велике вікно контексту (128K+)"),
        Cat("unusual", "Незвичайні/цікаві", "Нішеві, експериментальні або унікальні моделі")
    )

    fun toDouble(s: String?): Double = s?.toDoubleOrNull() ?: Double.MAX_VALUE
    private fun isFree(m: ModelMeta) = toDouble(m.inPrice) == 0.0 && toDouble(m.outPrice) == 0.0
    private fun hasVision(m: ModelMeta) =
        m.modalities.any { it.equals("image", true) } || m.id.contains("vision", true) ||
        m.id.contains("-vl", true) || m.id.contains("gpt-4o", true)
    private fun isCode(m: ModelMeta) =
        listOf("code", "coder", "codex", "deepseek", "qwen", "devstral").any { m.id.contains(it, true) }
    private fun isReasoning(m: ModelMeta) =
        m.params.any { it.equals("reasoning", true) } ||
        listOf("o1", "o3", "o4", "r1", "reason", "think", "gpt-5", "opus").any { m.id.contains(it, true) }
    private fun isFast(m: ModelMeta) =
        toDouble(m.outPrice) in 0.0..0.0000015 && !isFree(m) ||
        listOf("mini", "flash", "haiku", "nano", "small", "lite", "8b", "7b").any { m.id.contains(it, true) }
    private fun isLong(m: ModelMeta) = m.ctx >= 128_000
    private fun isUnusual(m: ModelMeta) =
        listOf("uncensored", "roleplay", "rp", "abliterated", "dolphin", "merge", "creative", "storytelling", "medical", "finance", "sonar", "perplexity")
            .any { m.id.contains(it, true) || m.name.contains(it, true) }

    /** Popularity-ish ranking for the "top" tab: strong brands, big context, tool support. */
    private fun topScore(m: ModelMeta): Int {
        var s = 0
        listOf("gpt-5", "claude", "gemini-2", "gemini-3", "grok", "deepseek", "llama-3.3", "qwen3", "mistral-large", "o3", "o4")
            .forEach { if (m.id.contains(it, true)) s += 10 }
        if (m.ctx >= 200_000) s += 3; if (m.params.any { it == "tools" }) s += 2
        if (isFree(m)) s += 1
        return s
    }

    fun categorize(models: List<ModelMeta>, catId: String): List<ModelMeta> = when (catId) {
        "top" -> models.sortedByDescending { topScore(it) }.filter { topScore(it) > 0 }.take(30)
        "code" -> models.filter { isCode(it) }
        "reasoning" -> models.filter { isReasoning(it) }
        "fast" -> models.filter { isFast(it) }
        "free" -> models.filter { isFree(it) }
        "vision" -> models.filter { hasVision(it) }
        "long" -> models.filter { isLong(it) }.sortedByDescending { it.ctx }
        "unusual" -> models.filter { isUnusual(it) }
        else -> models
    }.sortedBy { it.id }

    private fun uaPrice(m: ModelMeta): String {
        if (isFree(m)) return "безкоштовно"
        val inP = toDouble(m.inPrice); val outP = toDouble(m.outPrice)
        fun perM(x: Double) = if (x == Double.MAX_VALUE) "?" else "$%.2f".format(x * 1_000_000)
        return "вхід ${perM(inP)}/M ток., вихід ${perM(outP)}/M ток."
    }
    private fun uaCtx(m: ModelMeta): String = when {
        m.ctx >= 1_000_000 -> "${m.ctx / 1_000_000}M токенів контексту"
        m.ctx >= 1000 -> "${m.ctx / 1000}K токенів контексту"
        m.ctx > 0 -> "${m.ctx} токенів контексту"
        else -> "контекст невідомий"
    }

    /** Detailed Ukrainian description: strengths, price, context, when to use. */
    fun describeUa(m: ModelMeta): String {
        val strengths = buildList {
            if (isCode(m)) add("сильна у програмуванні")
            if (isReasoning(m)) add("добре міркує та планує")
            if (hasVision(m)) add("розуміє зображення")
            if (isLong(m)) add("великий контекст")
            if (isFast(m)) add("швидка й недорога")
            if (isFree(m)) add("безкоштовна")
        }.ifEmpty { listOf("універсальна модель") }.joinToString(", ")
        val whenUse = when {
            isCode(m) -> "Варто брати для написання та рефакторингу коду, рев'ю та агентних задач."
            isReasoning(m) -> "Підходить для складних міркувань, математики та багатокрокових планів."
            hasVision(m) -> "Корисна, коли треба аналізувати скриншоти чи фото."
            isFast(m) -> "Добрий вибір для швидких, дешевих, масових запитів."
            isLong(m) -> "Бери, коли треба обробити великі документи або довгі діалоги."
            else -> "Універсальний вибір для повсякденних задач."
        }
        val tools = if (m.params.any { it == "tools" }) " Підтримує виклик інструментів (function calling)." else ""
        val curated = m.desc.takeIf { it.isNotBlank() }?.let { " \u2014 $it" } ?: ""
        return "${m.name}\nСильні сторони: $strengths.\nЦіна: ${uaPrice(m)}.\nКонтекст: ${uaCtx(m)}.$tools\n$whenUse$curated"
    }

    fun parse(json: String): List<ModelMeta> {
        val arr = JSONArray(json)
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val mods = o.opt("mod")
            val modList = when (mods) {
                is JSONArray -> (0 until mods.length()).map { mods.getString(it) }
                is String -> listOf(mods)
                else -> emptyList()
            }
            val params = o.optJSONArray("params")?.let { p -> (0 until p.length()).map { p.getString(it) } } ?: emptyList()
            ModelMeta(o.getString("id"), o.optString("name", o.getString("id")), o.optInt("ctx", 0),
                o.optString("in", ""), o.optString("out", ""), modList, o.optString("desc", ""), params)
        }
    }
}

data class ModelMeta(
    val id: String, val name: String, val ctx: Int,
    val inPrice: String, val outPrice: String,
    val modalities: List<String>, val desc: String, val params: List<String>
)
