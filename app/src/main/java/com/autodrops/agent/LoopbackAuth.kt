package com.autodrops.agent

import java.net.InetAddress
import java.net.ServerSocket
import java.net.URLDecoder
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Codex-CLI browser flow on a phone: bind 127.0.0.1:1455, open Custom Tab to auth.openai.com,
 * catch the redirect to http://127.0.0.1:1455/auth/callback?code=&state=.
 * Preferred path on Android is still the device-code flow (no loopback needed).
 */
class LoopbackAuth(private val expectedState: String, private val port: Int = 1455) : AutoCloseable {
    private val sock = ServerSocket(port, 1, InetAddress.getByName("127.0.0.1"))
    private val closed = AtomicBoolean(false)
    val redirectUri: String get() = "http://127.0.0.1:$port/auth/callback"

    data class Result(val code: String, val state: String)

    /** Block until the browser hits us or timeoutMs elapses. Throws on state mismatch / timeout. */
    fun await(timeoutMs: Long = 5 * 60_000L): Result {
        sock.soTimeout = timeoutMs.toInt().coerceAtLeast(1_000)
        sock.accept().use { c ->
            c.soTimeout = 5_000
            val req = c.getInputStream().bufferedReader().readLine() ?: throw RuntimeException("Порожній callback")
            val path = req.substringAfter(" ").substringBefore(" ")
            val q = path.substringAfter('?', "")
            val params = q.split('&').mapNotNull { p -> p.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to URLDecoder.decode(it[1], "UTF-8") } }.toMap()
            val err = params["error"]; if (!err.isNullOrBlank()) throw RuntimeException("OAuth: $err ${params["error_description"].orEmpty()}".trim())
            val code = params["code"] ?: throw RuntimeException("Немає code у callback")
            val state = params["state"].orEmpty()
            if (state != expectedState) throw RuntimeException("State не збігається (CSRF).")
            val body = "<html><body style='background:#000;color:#0f0;font-family:monospace'>DRON.exe: вхід прийнято. Повернись у додаток.</body></html>"
            c.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${body.toByteArray().size}\r\nConnection: close\r\n\r\n$body".toByteArray())
            return Result(code, state)
        }
    }
    override fun close() { if (closed.compareAndSet(false, true)) runCatching { sock.close() } }
}
