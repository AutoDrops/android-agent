package com.autodrops.agent

import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/**
 * v3.22: "Sign in with ChatGPT" exactly like the open-source Codex CLI (openai/codex @970111f6, codex-rs/login).
 * Public client id (no secret), PKCE, device-code flow (phone has no loopback browser callback), token refresh,
 * and the Codex Responses backend. Tokens live ONLY in EncryptedSharedPreferences (Keystore master key).
 */
object ChatGptOAuth {
    const val ISSUER = "https://auth.openai.com"
    const val CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann"
    const val LOOPBACK_REDIRECT = "http://127.0.0.1:1455/auth/callback"
    const val SCOPE = "openid profile email offline_access api.connectors.read api.connectors.invoke"
    const val DEVICE_PAGE = "$ISSUER/codex/device"
    const val DEVICE_REDIRECT = "$ISSUER/deviceauth/callback"
    const val BACKEND = "https://chatgpt.com/backend-api/codex"
    const val PREFIX = "chatgpt/"
    /** From codex models.json (visibility=list). Live list replaces it when available. */
    val DEFAULT_MODELS = listOf("gpt-5.5", "gpt-5.6-sol", "gpt-5.6-terra", "gpt-5.6-luna", "gpt-6-sol", "gpt-6.1-sol", "gpt-6-luna", "gpt-6-astra")

    fun isChatGptModel(id: String) = id.startsWith(PREFIX)
    fun backendModel(id: String) = id.removePrefix(PREFIX)

    fun b64url(b: ByteArray): String = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(b)
    data class Pkce(val verifier: String, val challenge: String)
    fun pkce(rnd: SecureRandom = SecureRandom()): Pkce {
        val bytes = ByteArray(64).also { rnd.nextBytes(it) }
        val v = b64url(bytes)
        return Pkce(v, challenge(v))
    }
    fun challenge(verifier: String) = b64url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
    fun state(rnd: SecureRandom = SecureRandom()) = b64url(ByteArray(32).also { rnd.nextBytes(it) })

    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")
    fun authorizeUrl(p: Pkce, state: String, redirect: String = LOOPBACK_REDIRECT): String =
        "$ISSUER/oauth/authorize?" + listOf(
            "response_type" to "code", "client_id" to CLIENT_ID, "redirect_uri" to redirect, "scope" to SCOPE,
            "code_challenge" to p.challenge, "code_challenge_method" to "S256", "id_token_add_organizations" to "true",
            "codex_cli_simplified_flow" to "true", "state" to state, "originator" to "codex_cli_rs"
        ).joinToString("&") { (k, v) -> "$k=${enc(v)}" }

    data class Tokens(val idToken: String, val accessToken: String, val refreshToken: String, val accountId: String, val email: String, val expiresAt: Long)

    /** Decode a JWT payload (no signature check — only used to read our own claims). */
    fun jwtClaims(jwt: String): JSONObject = runCatching {
        val part = jwt.split(".")[1].trimEnd('=')
        JSONObject(String(java.util.Base64.getUrlDecoder().decode(part + "=".repeat((4 - part.length % 4) % 4)), Charsets.UTF_8))
    }.getOrElse { JSONObject() }

    fun accountId(idToken: String): String =
        jwtClaims(idToken).optJSONObject("https://api.openai.com/auth")?.optString("chatgpt_account_id").orEmpty()

    /** Parse /oauth/token JSON. On refresh the server may omit id/refresh tokens — keep previous ones. */
    fun parseTokens(json: String, prev: Tokens? = null, now: Long = System.currentTimeMillis()): Tokens {
        val o = JSONObject(json)
        if (o.has("error")) throw RuntimeException(tokenError(o))
        val id = o.optString("id_token").ifBlank { prev?.idToken.orEmpty() }
        val access = o.optString("access_token").ifBlank { throw RuntimeException("Немає access_token у відповіді") }
        val refresh = o.optString("refresh_token").ifBlank { prev?.refreshToken.orEmpty() }
        val exp = if (o.has("expires_in")) now + o.optLong("expires_in") * 1000
                  else jwtClaims(access).optLong("exp", 0).takeIf { it > 0 }?.times(1000) ?: (now + 3_600_000)
        return Tokens(id, access, refresh, accountId(id).ifBlank { prev?.accountId.orEmpty() },
            jwtClaims(id).optString("email").ifBlank { prev?.email.orEmpty() }, exp)
    }
    fun tokenError(o: JSONObject): String {
        val e = o.opt("error"); val code = if (e is JSONObject) e.optString("code", e.optString("message")) else e?.toString().orEmpty()
        return when (code) {
            "refresh_token_expired", "refresh_token_reused", "refresh_token_invalidated", "invalid_grant" -> "Сесія ChatGPT завершилась ($code). Увійди ще раз."
            else -> "ChatGPT OAuth: $code ${o.optString("error_description")}".trim()
        }
    }

    fun codeExchangeForm(code: String, verifier: String, redirect: String) = mapOf(
        "grant_type" to "authorization_code", "code" to code, "redirect_uri" to redirect, "client_id" to CLIENT_ID, "code_verifier" to verifier)
    fun refreshBody(refresh: String): JSONObject =
        JSONObject().put("client_id", CLIENT_ID).put("grant_type", "refresh_token").put("refresh_token", refresh)

    data class DeviceCode(val deviceAuthId: String, val userCode: String, val intervalSec: Int)
    fun parseDeviceCode(json: String): DeviceCode {
        val o = JSONObject(json)
        return DeviceCode(o.getString("device_auth_id"), o.optString("user_code").ifBlank { o.getString("usercode") },
            o.optString("interval").toIntOrNull()?.coerceIn(1, 30) ?: 5)
    }
    data class DeviceGrant(val code: String, val verifier: String)
    fun parseDeviceGrant(json: String): DeviceGrant { val o = JSONObject(json); return DeviceGrant(o.getString("authorization_code"), o.getString("code_verifier")) }
}

/** Encrypted token storage (EncryptedSharedPreferences in the app; plain prefs in JVM tests). */
class ChatGptTokenStore(private val prefs: SharedPreferences) {
    fun load(): ChatGptOAuth.Tokens? {
        val a = prefs.getString("access", null) ?: return null
        return ChatGptOAuth.Tokens(prefs.getString("id", "")!!, a, prefs.getString("refresh", "")!!,
            prefs.getString("account", "")!!, prefs.getString("email", "")!!, prefs.getLong("exp", 0))
    }
    fun save(t: ChatGptOAuth.Tokens) = prefs.edit().putString("id", t.idToken).putString("access", t.accessToken)
        .putString("refresh", t.refreshToken).putString("account", t.accountId).putString("email", t.email).putLong("exp", t.expiresAt).apply()
    fun clear() = prefs.edit().clear().apply()
    var models: List<String>
        get() = prefs.getString("models", null)?.split(',')?.filter { it.isNotBlank() } ?: ChatGptOAuth.DEFAULT_MODELS
        set(v) = prefs.edit().putString("models", v.joinToString(",")).apply()
}

/** Chat-completions ⇄ Responses API mapping, so the existing agent loop is reused unchanged. */
object CodexMapping {
    /** history (chat format) → Responses `instructions` + `input`. */
    fun buildBody(model: String, messages: JSONArray, chatTools: JSONArray): JSONObject {
        val instr = StringBuilder(); val input = JSONArray()
        for (i in 0 until messages.length()) {
            val m = messages.getJSONObject(i)
            val content = m.optString("content", "").takeIf { it != "null" } ?: ""
            when (m.optString("role")) {
                "system" -> { if (instr.isNotEmpty()) instr.append("\n\n"); instr.append(content) }
                "user" -> input.put(msg("user", "input_text", content))
                "assistant" -> {
                    if (content.isNotBlank()) input.put(msg("assistant", "output_text", content))
                    m.optJSONArray("tool_calls")?.let { tc -> for (j in 0 until tc.length()) {
                        val c = tc.getJSONObject(j); val f = c.getJSONObject("function")
                        input.put(JSONObject().put("type", "function_call").put("call_id", c.optString("id"))
                            .put("name", f.optString("name")).put("arguments", f.optString("arguments", "{}")))
                    } }
                }
                "tool" -> input.put(JSONObject().put("type", "function_call_output").put("call_id", m.optString("tool_call_id")).put("output", content))
            }
        }
        val tools = JSONArray()
        for (i in 0 until chatTools.length()) {
            val f = chatTools.getJSONObject(i).optJSONObject("function") ?: continue
            tools.put(JSONObject().put("type", "function").put("name", f.optString("name")).put("description", f.optString("description"))
                .put("strict", false).put("parameters", f.optJSONObject("parameters") ?: JSONObject().put("type", "object")))
        }
        return JSONObject().put("model", model).put("instructions", instr.toString()).put("input", input)
            .put("tools", tools).put("tool_choice", "auto").put("parallel_tool_calls", false)
            .put("store", false).put("stream", true).put("include", JSONArray().put("reasoning.encrypted_content"))
    }
    private fun msg(role: String, type: String, text: String) = JSONObject().put("type", "message").put("role", role)
        .put("content", JSONArray().put(JSONObject().put("type", type).put("text", text)))

    /** Incremental SSE parser. Feed `data:` payloads; get a chat-format assistant message at the end. */
    class SseState(private val onDelta: (String) -> Unit) {
        val text = StringBuilder(); val calls = ArrayList<JSONObject>(); var usage: JSONObject? = null; var done = false
        fun onEvent(data: String) {
            val o = runCatching { JSONObject(data) }.getOrNull() ?: return
            when (o.optString("type")) {
                "response.output_text.delta" -> o.optString("delta").takeIf { it.isNotEmpty() }?.let { text.append(it); onDelta(it) }
                "response.output_item.done" -> o.optJSONObject("item")?.takeIf { it.optString("type") == "function_call" }?.let { it2 ->
                    calls += JSONObject().put("id", it2.optString("call_id").ifBlank { "call_${calls.size}" }).put("type", "function")
                        .put("function", JSONObject().put("name", it2.optString("name")).put("arguments", it2.optString("arguments", "{}")))
                }
                "response.completed" -> { done = true; o.optJSONObject("response")?.optJSONObject("usage")?.let { u ->
                    usage = JSONObject().put("prompt_tokens", u.optInt("input_tokens")).put("completion_tokens", u.optInt("output_tokens")) } }
                "response.failed", "error" -> throw RuntimeException("ChatGPT: " + (o.optJSONObject("response")?.optJSONObject("error")?.optString("message")
                    ?: o.optJSONObject("error")?.optString("message") ?: o.optString("message", "помилка відповіді")))
            }
        }
        fun message(): JSONObject = JSONObject().put("role", "assistant").put("content", text.toString()).also { m ->
            if (calls.isNotEmpty()) m.put("tool_calls", JSONArray(calls)); usage?.let { m.put("_usage", it) } }
    }
}

/** Network side: device-code login, refresh, completion. */
class ChatGptClient(private val store: ChatGptTokenStore, private val http: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(20, TimeUnit.SECONDS).readTimeout(300, TimeUnit.SECONDS).build()) {
    private val json = "application/json".toMediaType()
    private fun post(url: String, body: JSONObject) = Request.Builder().url(url).post(body.toString().toRequestBody(json)).build()

    suspend fun startDevice(): ChatGptOAuth.DeviceCode = withContext(Dispatchers.IO) {
        http.newCall(post("${ChatGptOAuth.ISSUER}/api/accounts/deviceauth/usercode", JSONObject().put("client_id", ChatGptOAuth.CLIENT_ID))).execute().use { r ->
            val t = r.body?.string().orEmpty()
            if (r.code == 404) throw RuntimeException("Вхід кодом пристрою вимкнено для цього акаунта (404). Спробуй «Вхід через браузер».")
            if (!r.isSuccessful) throw RuntimeException("deviceauth ${r.code}: ${t.take(200)}")
            ChatGptOAuth.parseDeviceCode(t)
        }
    }
    /** Poll until approved (403/404 = pending), max 15 min, then exchange. */
    suspend fun pollDevice(dc: ChatGptOAuth.DeviceCode): ChatGptOAuth.Tokens = withContext(Dispatchers.IO) {
        val deadline = System.currentTimeMillis() + 15 * 60_000
        while (System.currentTimeMillis() < deadline) {
            coroutineContext.ensureActive()
            val grant = http.newCall(post("${ChatGptOAuth.ISSUER}/api/accounts/deviceauth/token",
                JSONObject().put("device_auth_id", dc.deviceAuthId).put("user_code", dc.userCode))).execute().use { r ->
                val t = r.body?.string().orEmpty()
                when { r.isSuccessful -> ChatGptOAuth.parseDeviceGrant(t); r.code == 403 || r.code == 404 -> null
                       else -> throw RuntimeException("deviceauth/token ${r.code}: ${t.take(200)}") }
            }
            if (grant != null) return@withContext exchange(grant.code, grant.verifier, ChatGptOAuth.DEVICE_REDIRECT)
            kotlinx.coroutines.delay(dc.intervalSec * 1000L)
        }
        throw RuntimeException("Час на вхід минув (15 хв). Спробуй ще раз.")
    }
    suspend fun exchange(code: String, verifier: String, redirect: String): ChatGptOAuth.Tokens = withContext(Dispatchers.IO) {
        val fb = FormBody.Builder().apply { ChatGptOAuth.codeExchangeForm(code, verifier, redirect).forEach { (k, v) -> add(k, v) } }.build()
        http.newCall(Request.Builder().url("${ChatGptOAuth.ISSUER}/oauth/token").post(fb).build()).execute().use { r ->
            val t = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw RuntimeException(runCatching { ChatGptOAuth.tokenError(JSONObject(t)) }.getOrDefault("oauth/token ${r.code}"))
            ChatGptOAuth.parseTokens(t).also { store.save(it) }
        }
    }
    suspend fun refresh(): ChatGptOAuth.Tokens = withContext(Dispatchers.IO) {
        val prev = store.load() ?: throw RuntimeException("Не виконано вхід у ChatGPT.")
        if (prev.refreshToken.isBlank()) throw RuntimeException("Немає refresh-токена. Увійди ще раз.")
        http.newCall(post("${ChatGptOAuth.ISSUER}/oauth/token", ChatGptOAuth.refreshBody(prev.refreshToken))).execute().use { r ->
            val t = r.body?.string().orEmpty()
            if (!r.isSuccessful) { val e = runCatching { ChatGptOAuth.tokenError(JSONObject(t)) }.getOrDefault("refresh ${r.code}")
                if (r.code == 400 || r.code == 401) store.clear(); throw RuntimeException(e) }
            ChatGptOAuth.parseTokens(t, prev).also { store.save(it) }
        }
    }
    private suspend fun fresh(): ChatGptOAuth.Tokens {
        val t = store.load() ?: throw RuntimeException("Увійди через ChatGPT: вкладка «Моделі» → «Увійти через ChatGPT».")
        return if (t.expiresAt - System.currentTimeMillis() < 60_000) refresh() else t
    }

    suspend fun complete(model: String, messages: JSONArray, tools: JSONArray, onDelta: (String) -> Unit): JSONObject = withContext(Dispatchers.IO) {
        var tok = fresh()
        val body = CodexMapping.buildBody(ChatGptOAuth.backendModel(model), messages, tools)
        for (attempt in 0..1) {
            val req = Request.Builder().url("${ChatGptOAuth.BACKEND}/responses")
                .header("Authorization", "Bearer ${tok.accessToken}").header("ChatGPT-Account-ID", tok.accountId)
                .header("originator", "codex_cli_rs").header("session-id", java.util.UUID.randomUUID().toString())
                .header("Accept", "text/event-stream").post(body.toString().toRequestBody(json)).build()
            http.newCall(req).execute().use { r ->
                if (r.code == 401 && attempt == 0) { tok = refresh(); return@use }
                if (!r.isSuccessful) throw RuntimeException("ChatGPT HTTP ${r.code}: ${r.body?.string().orEmpty().take(300)}")
                val st = CodexMapping.SseState(onDelta); val src = r.body!!.source()
                while (!src.exhausted()) {
                    coroutineContext.ensureActive()
                    val line = src.readUtf8Line() ?: break
                    if (line.startsWith("data:")) st.onEvent(line.removePrefix("data:").trim())
                }
                return@withContext st.message()
            }
        }
        throw RuntimeException("ChatGPT: не вдалося оновити токен.")
    }
    suspend fun liveModels(): List<String>? = runCatching { withContext(Dispatchers.IO) {
        val t = fresh()
        http.newCall(Request.Builder().url("${ChatGptOAuth.BACKEND}/models?client_version=0.50.0")
            .header("Authorization", "Bearer ${t.accessToken}").header("ChatGPT-Account-ID", t.accountId).build()).execute().use { r ->
            if (!r.isSuccessful) return@use null
            val o = JSONObject(r.body!!.string()); val a = o.optJSONArray("models") ?: o.optJSONArray("data") ?: return@use null
            (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let { m -> m.optString("slug").ifBlank { m.optString("id") } }?.takeIf { it.isNotBlank() } }
        }
    } }.getOrNull()?.takeIf { it.isNotEmpty() }
}

/** Sanitized OAuth event log (no tokens/codes ever stored). */
object OAuthLog {
    private val buf = ArrayDeque<String>()
    private val secret = Regex("(?i)(access_token|refresh_token|id_token|code|code_verifier|authorization_code)([\"=:\\s]+)[A-Za-z0-9._~+/-]{6,}")
    private val jwt = Regex("eyJ[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]*")
    fun sanitize(s: String) = jwt.replace(secret.replace(s) { it.groupValues[1] + it.groupValues[2] + "***" }, "***jwt***")
    @Synchronized fun add(stage: String, msg: String) {
        val t = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        buf.addLast("$t [$stage] ${sanitize(msg).take(300)}"); while (buf.size > 50) buf.removeFirst()
    }
    @Synchronized fun lines(): List<String> = buf.toList()
}
