package com.autodrops.agent

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest

class V322Test {
    private val known = setOf("shell", "ping", "file_read", "net_discover")

    // ---------- text-tool fallback ----------
    @Test fun dolphinProseOnlyNarrates_noCallsButDetected() {
        val t = "Sure! I'll run these commands to check your phone:\n1. dumpsys battery\n2. top -n 1\n3. free -m\nLet me know!"
        assertTrue(TextTools.parse(t, known).isEmpty())
        assertTrue(TextTools.narratesWithoutAction(t))
    }
    @Test fun ukrainianNarrationDetected() = assertTrue(TextTools.narratesWithoutAction("Зараз запущу dumpsys і перевірю памʼять."))
    @Test fun finalAnswerNotNarration() = assertFalse(TextTools.narratesWithoutAction("Your phone has 3.2 GB free RAM and battery is 81%."))
    @Test fun shBlockBecomesShellCall() {
        val c = TextTools.parse("I'll run:\n```bash\n$ dumpsys battery\n# comment\ntop -n 1\nfree -m\n```", known)
        assertEquals(1, c.size); assertEquals("shell", c[0].name)
        assertEquals("dumpsys battery ; top -n 1 ; free -m", c[0].arguments.getString("command"))
    }
    @Test fun toolJsonBlock() {
        val c = TextTools.parse("```tool\n{\"name\":\"ping\",\"arguments\":{\"host\":\"192.168.1.1\"}}\n```", known)
        assertEquals("ping", c.single().name); assertEquals("192.168.1.1", c[0].arguments.getString("host"))
    }
    @Test fun openAiFunctionShapeWithStringArgs() {
        val c = TextTools.parse("```json\n{\"function\":{\"name\":\"file_read\",\"arguments\":\"{\\\"path\\\":\\\"a.txt\\\"}\"}}\n```", known)
        assertEquals("a.txt", c.single().arguments.getString("path"))
    }
    @Test fun freeModelInlineJsonNoFence() {
        val c = TextTools.parse("Calling now {\"name\": \"net_discover\", \"arguments\": {}} ok", known)
        assertEquals("net_discover", c.single().name)
    }
    @Test fun unfencedLangShellHeuristic() =
        assertEquals("free -m", TextTools.parse("```\nfree -m\n```", known).single().arguments.getString("command"))
    @Test fun unknownToolDroppedFailClosed() = assertTrue(TextTools.parse("```tool\n{\"name\":\"rm_rf_all\",\"arguments\":{}}\n```", known).isEmpty())
    @Test fun nonShellCodeIgnored() = assertTrue(TextTools.parse("```python\nprint(1)\n```", known).isEmpty())
    @Test fun syntheticToolCallsShape() {
        val a = TextTools.toToolCalls(listOf(TextTools.Call("shell", JSONObject().put("command", "id"))), 3)
        val f = a.getJSONObject(0).getJSONObject("function")
        assertEquals("text_3_0", a.getJSONObject(0).getString("id")); assertEquals("shell", f.getString("name"))
        assertEquals("id", JSONObject(f.getString("arguments")).getString("command"))
    }
    @Test fun instructionsListTools() = assertTrue(TextTools.instructions(known).contains("```tool") && TextTools.instructions(known).contains("net_discover"))
    @Test fun resultMessageMarkedUntrusted() = assertTrue(TextTools.resultMessage("shell", "{}", "out").contains("untrusted"))

    @Test fun glmXmlShapeFromLaguna() {
        val c = TextTools.parse("<tool_call>shell<arg_key>command</arg_key><arg_value>free -m</arg_value></tool_call>", known)
        assertEquals("shell", c.single().name); assertEquals("free -m", c[0].arguments.getString("command"))
    }
    @Test fun nameDoubleBraceShape() =
        assertEquals("free -m", TextTools.parse("I'll check.<tool_call>shell{{\"command\": \"free -m\"}}", known).single().arguments.getString("command"))
    @Test fun hermesShape() =
        assertEquals("ping", TextTools.parse("<tool_call>{\"name\":\"ping\",\"arguments\":{\"host\":\"10.0.0.1\"}}</tool_call>", known).single().name)

    // ---------- ChatGPT OAuth ----------
    @Test fun pkceIsS256OfVerifier() {
        val p = ChatGptOAuth.pkce()
        assertEquals(86, p.verifier.length) // 64 bytes base64url no pad
        assertFalse(p.verifier.contains('=') || p.verifier.contains('+') || p.verifier.contains('/'))
        val exp = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(p.verifier.toByteArray()))
        assertEquals(exp, p.challenge)
    }
    @Test fun rfc7636Vector() = assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", ChatGptOAuth.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
    @Test fun authorizeUrl() {
        val u = ChatGptOAuth.authorizeUrl(ChatGptOAuth.Pkce("v", "CH"), "ST")
        assertTrue(u.startsWith("https://auth.openai.com/oauth/authorize?response_type=code&client_id=app_EMoamEEZ73f0CkXaXp7hrann"))
        listOf("redirect_uri=http%3A%2F%2F127.0.0.1%3A1455%2Fauth%2Fcallback", "code_challenge=CH", "code_challenge_method=S256",
            "state=ST", "codex_cli_simplified_flow=true", "originator=codex_cli_rs", "scope=openid%20profile%20email%20offline_access").forEach { assertTrue(it, u.contains(it)) }
    }
    private fun jwt(claims: JSONObject) = "h." + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(claims.toString().toByteArray()) + ".s"
    private val idTok = jwt(JSONObject().put("email", "b@x.ua").put("https://api.openai.com/auth", JSONObject().put("chatgpt_account_id", "acc-123")))
    @Test fun tokenParsing() {
        val t = ChatGptOAuth.parseTokens(JSONObject().put("id_token", idTok).put("access_token", "AT").put("refresh_token", "RT").put("expires_in", 3600).toString(), now = 1000)
        assertEquals("acc-123", t.accountId); assertEquals("b@x.ua", t.email); assertEquals("RT", t.refreshToken); assertEquals(1000 + 3_600_000, t.expiresAt)
    }
    @Test fun refreshKeepsPreviousTokens() {
        val prev = ChatGptOAuth.Tokens(idTok, "old", "RT", "acc-123", "b@x.ua", 0)
        val t = ChatGptOAuth.parseTokens("{\"access_token\":\"new\",\"expires_in\":10}", prev, now = 0)
        assertEquals("new", t.accessToken); assertEquals("RT", t.refreshToken); assertEquals("acc-123", t.accountId)
    }
    @Test fun refreshErrorsFriendly() {
        val e = runCatching { ChatGptOAuth.parseTokens("{\"error\":{\"code\":\"refresh_token_expired\"}}") }.exceptionOrNull()!!
        assertTrue(e.message!!.contains("Увійди ще раз"))
    }
    @Test fun refreshAndExchangeBodies() {
        val r = ChatGptOAuth.refreshBody("RT")
        assertEquals("refresh_token", r.getString("grant_type")); assertEquals(ChatGptOAuth.CLIENT_ID, r.getString("client_id"))
        val f = ChatGptOAuth.codeExchangeForm("C", "V", ChatGptOAuth.DEVICE_REDIRECT)
        assertEquals("authorization_code", f["grant_type"]); assertEquals("https://auth.openai.com/deviceauth/callback", f["redirect_uri"]); assertEquals("V", f["code_verifier"])
    }
    @Test fun deviceCodeParsing() {
        val d = ChatGptOAuth.parseDeviceCode("{\"device_auth_id\":\"D1\",\"user_code\":\"ABCD-EFGH\",\"interval\":\"5\"}")
        assertEquals("ABCD-EFGH", d.userCode); assertEquals(5, d.intervalSec)
        val g = ChatGptOAuth.parseDeviceGrant("{\"authorization_code\":\"AC\",\"code_verifier\":\"CV\",\"code_challenge\":\"x\"}")
        assertEquals("AC", g.code); assertEquals("CV", g.verifier)
    }
    @Test fun modelPrefix() { assertTrue(ChatGptOAuth.isChatGptModel("chatgpt/gpt-5.5")); assertEquals("gpt-5.5", ChatGptOAuth.backendModel("chatgpt/gpt-5.5")); assertFalse(ChatGptOAuth.isChatGptModel("openai/gpt-4o-mini")) }

    // ---------- Responses mapping + SSE ----------
    @Test fun requestMapping() {
        val h = JSONArray()
            .put(JSONObject().put("role", "system").put("content", "SYS"))
            .put(JSONObject().put("role", "user").put("content", "hi"))
            .put(JSONObject().put("role", "assistant").put("content", "").put("tool_calls", JSONArray().put(JSONObject().put("id", "c1").put("type", "function")
                .put("function", JSONObject().put("name", "shell").put("arguments", "{\"command\":\"id\"}")))))
            .put(JSONObject().put("role", "tool").put("tool_call_id", "c1").put("content", "uid=0"))
        val tools = JSONArray().put(JSONObject().put("type", "function").put("function", JSONObject().put("name", "shell").put("description", "d").put("parameters", JSONObject().put("type", "object"))))
        val b = CodexMapping.buildBody("gpt-5.5", h, tools)
        assertEquals("SYS", b.getString("instructions")); assertTrue(b.getBoolean("stream")); assertFalse(b.getBoolean("store"))
        val inp = b.getJSONArray("input"); assertEquals(3, inp.length())
        assertEquals("input_text", inp.getJSONObject(0).getJSONArray("content").getJSONObject(0).getString("type"))
        assertEquals("function_call", inp.getJSONObject(1).getString("type")); assertEquals("c1", inp.getJSONObject(1).getString("call_id"))
        assertEquals("function_call_output", inp.getJSONObject(2).getString("type")); assertEquals("uid=0", inp.getJSONObject(2).getString("output"))
        assertEquals("shell", b.getJSONArray("tools").getJSONObject(0).getString("name"))
    }
    @Test fun sseTextAndToolCall() {
        val deltas = StringBuilder(); val st = CodexMapping.SseState { deltas.append(it) }
        st.onEvent("{\"type\":\"response.output_text.delta\",\"delta\":\"Hel\"}")
        st.onEvent("{\"type\":\"response.output_text.delta\",\"delta\":\"lo\"}")
        st.onEvent("{\"type\":\"response.output_item.done\",\"item\":{\"type\":\"function_call\",\"call_id\":\"call_9\",\"name\":\"shell\",\"arguments\":\"{\\\"command\\\":\\\"free -m\\\"}\"}}")
        st.onEvent("{\"type\":\"response.completed\",\"response\":{\"usage\":{\"input_tokens\":10,\"output_tokens\":4}}}")
        val m = st.message()
        assertEquals("Hello", deltas.toString()); assertEquals("Hello", m.getString("content"))
        val c = m.getJSONArray("tool_calls").getJSONObject(0)
        assertEquals("call_9", c.getString("id")); assertEquals("shell", c.getJSONObject("function").getString("name"))
        assertEquals(10, m.getJSONObject("_usage").getInt("prompt_tokens"))
    }
    @Test fun sseFailureSurfaces() {
        val e = runCatching { CodexMapping.SseState {}.onEvent("{\"type\":\"response.failed\",\"response\":{\"error\":{\"message\":\"usage limit\"}}}") }.exceptionOrNull()
        assertTrue(e!!.message!!.contains("usage limit"))
    }
    @Test fun noEmbeddedKey() = assertEquals("", BuildConfig.EMBEDDED_API_KEY)
}
