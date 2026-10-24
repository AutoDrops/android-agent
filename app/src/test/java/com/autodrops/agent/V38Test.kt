package com.autodrops.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

/** v3.8: uncensored paid-model category + snippet secret-guard regex. */
class V38Test {
    private fun m(id: String, name: String = id) =
        ModelMeta(id, name, 8000, "0.000001", "0.000002", emptyList(), "", listOf("tools"))

    @Test fun uncensored_category_matches_dolphin_venice_abliterated() {
        val models = listOf(
            m("cognitivecomputations/dolphin-mixtral-8x7b"),
            m("venice/uncensored"),
            m("meta-llama/llama-3.3-70b"),
            m("some/abliterated-qwen"),
            m("openai/gpt-4o-mini")
        )
        val cat = ModelCatalog.categorize(models, "uncensored").map { it.id }
        assertTrue(cat.any { it.contains("dolphin") })
        assertTrue(cat.any { it.contains("venice") })
        assertTrue(cat.any { it.contains("abliterated") })
        assertFalse(cat.contains("openai/gpt-4o-mini"))
        assertFalse(cat.contains("meta-llama/llama-3.3-70b"))
    }

    @Test fun uncensored_category_exists_in_list() {
        assertTrue(ModelCatalog.CATEGORIES.any { it.id == "uncensored" && it.uaTitle == "Без цензури" })
    }

    // Mirror of AgentViewModel.snippetLooksSecret so the regex is unit-tested without Android deps.
    private val secret = Regex("(?i)(sk-[a-z]|bearer\\s|api[_-]?key|password|passwd|-p\\s*\\S|token=|secret|AKIA[0-9A-Z]{8})")
    @Test fun snippet_secret_guard_flags_credentials() {
        assertTrue(secret.containsMatchIn("export OPENROUTER_API_KEY=sk-or-123"))
        assertTrue(secret.containsMatchIn("curl -H 'Authorization: Bearer abc' x"))
        assertTrue(secret.containsMatchIn("mysql -u root -ppassword"))
        assertTrue(secret.containsMatchIn("aws key AKIA12345678"))
    }
    @Test fun snippet_secret_guard_allows_normal_commands() {
        assertFalse(secret.containsMatchIn("ls -la"))
        assertFalse(secret.containsMatchIn("python hello.py"))
        assertFalse(secret.containsMatchIn("git status"))
    }

    // Mirror of Exec exit-code header parsing.
    private fun parseExit(out: String): Int? =
        Regex("^exit=(-?\\d+)").find(out.trimStart())?.groupValues?.get(1)?.toIntOrNull()
    @Test fun exit_code_parsing() {
        assertEquals(0, parseExit("exit=0\nhello"))
        assertEquals(-1, parseExit("exit=-1 (timeout 30s, killed)\n"))
        assertEquals(2, parseExit("exit=2\nerror"))
        assertEquals(null, parseExit("no header here"))
    }
}
