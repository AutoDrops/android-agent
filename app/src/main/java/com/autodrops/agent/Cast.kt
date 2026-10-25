package com.autodrops.agent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * PURE, unit-testable Google Cast (CASTV2) protocol logic: protobuf CastMessage framing and the
 * JSON control payloads. Socket I/O lives in [Cast]. No third-party targets — callers guard the host.
 */
object CastProto {
    const val NS_CONNECTION = "urn:x-cast:com.google.cast.tp.connection"
    const val NS_HEARTBEAT = "urn:x-cast:com.google.cast.tp.heartbeat"
    const val NS_RECEIVER = "urn:x-cast:com.google.cast.receiver"
    const val NS_MEDIA = "urn:x-cast:com.google.cast.media"
    const val DEFAULT_MEDIA_APP = "CC1AD845"
    const val SOURCE_ID = "sender-0"
    const val RECEIVER_ID = "receiver-0"
    const val CAST_PORT = 8009

    private fun varint(value: Long): ByteArray {
        var v = value
        val out = ArrayList<Byte>()
        do {
            var b = (v and 0x7F).toInt()
            v = v ushr 7
            if (v != 0L) b = b or 0x80
            out.add(b.toByte())
        } while (v != 0L)
        return out.toByteArray()
    }

    private fun strField(num: Int, s: String): ByteArray {
        val bytes = s.toByteArray(Charsets.UTF_8)
        val tag = byteArrayOf((((num shl 3) or 2).toByte()))
        return tag + varint(bytes.size.toLong()) + bytes
    }

    private fun varintField(num: Int, value: Long): ByteArray =
        byteArrayOf(((num shl 3) or 0).toByte()) + varint(value)

    /** Serialize a CastMessage (protocol_version=0, payload_type=STRING). */
    fun castMessage(namespace: String, payloadUtf8: String,
                    sourceId: String = SOURCE_ID, destId: String = RECEIVER_ID): ByteArray =
        varintField(1, 0) +            // protocol_version = CASTV2_1_0
        strField(2, sourceId) +
        strField(3, destId) +
        strField(4, namespace) +
        varintField(5, 0) +            // payload_type = STRING
        strField(6, payloadUtf8)

    /** 4-byte big-endian length prefix + message, as sent on the wire. */
    fun framed(msg: ByteArray): ByteArray {
        val n = msg.size
        return byteArrayOf(
            ((n ushr 24) and 0xFF).toByte(), ((n ushr 16) and 0xFF).toByte(),
            ((n ushr 8) and 0xFF).toByte(), (n and 0xFF).toByte()
        ) + msg
    }

    fun connectPayload() = "{\"type\":\"CONNECT\"}"
    fun closePayload() = "{\"type\":\"CLOSE\"}"
    fun getStatusPayload(reqId: Int) = "{\"type\":\"GET_STATUS\",\"requestId\":$reqId}"
    fun launchPayload(appId: String, reqId: Int) =
        "{\"type\":\"LAUNCH\",\"appId\":\"$appId\",\"requestId\":$reqId}"
    fun stopPayload(sessionId: String, reqId: Int) =
        "{\"type\":\"STOP\",\"sessionId\":\"$sessionId\",\"requestId\":$reqId}"

    fun loadPayload(mediaUrl: String, contentType: String, reqId: Int): String {
        val url = mediaUrl.replace("\\", "\\\\").replace("\"", "\\\"")
        return "{\"type\":\"LOAD\",\"requestId\":$reqId,\"autoplay\":true," +
            "\"media\":{\"contentId\":\"$url\",\"streamType\":\"BUFFERED\",\"contentType\":\"$contentType\"}}"
    }

    fun parseTransportId(json: String): String? =
        Regex("\"transportId\"\\s*:\\s*\"([^\"]+)\"").find(json)?.groupValues?.get(1)
    fun parseSessionId(json: String): String? =
        Regex("\"sessionId\"\\s*:\\s*\"([^\"]+)\"").find(json)?.groupValues?.get(1)
    fun parseMediaSessionId(json: String): Int? =
        Regex("\"mediaSessionId\"\\s*:\\s*(\\d+)").find(json)?.groupValues?.get(1)?.toIntOrNull()

    fun guessContentType(url: String): String = when {
        url.endsWith(".mp4", true) -> "video/mp4"
        url.endsWith(".webm", true) -> "video/webm"
        url.endsWith(".mkv", true) -> "video/x-matroska"
        url.endsWith(".mp3", true) -> "audio/mpeg"
        url.endsWith(".flac", true) -> "audio/flac"
        url.endsWith(".wav", true) -> "audio/wav"
        url.endsWith(".m3u8", true) -> "application/x-mpegURL"
        url.endsWith(".jpg", true) || url.endsWith(".jpeg", true) -> "image/jpeg"
        url.endsWith(".png", true) -> "image/png"
        else -> "video/mp4"
    }
}

/**
 * Google Cast control over the device's own LAN (CASTV2 over TLS:8009). Built + unit-tested at the
 * protocol layer; the live socket flow is NOT verified on real hardware (no Cast device in CI).
 * Chromecast uses a self-signed cert, so TLS trust is relaxed for the direct LAN socket only.
 */
object Cast {
    private fun sslFactory(): SSLSocketFactory {
        val tm = object : X509TrustManager {
            override fun checkClientTrusted(c: Array<X509Certificate>?, a: String?) {}
            override fun checkServerTrusted(c: Array<X509Certificate>?, a: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, arrayOf<TrustManager>(tm), java.security.SecureRandom())
        return ctx.socketFactory
    }

    private fun send(out: DataOutputStream, namespace: String, payload: String, dest: String) {
        out.write(CastProto.framed(CastProto.castMessage(namespace, payload, destId = dest)))
        out.flush()
    }

    private fun readFrame(inp: DataInputStream): String? = runCatching {
        val len = inp.readInt()
        if (len <= 0 || len > 1_000_000) return null
        val buf = ByteArray(len); inp.readFully(buf)
        String(buf, Charsets.UTF_8)
    }.getOrNull()

    /** actions: play (launch default receiver + load url), stop (stop running app), status. */
    suspend fun control(host: String, action: String, mediaUrl: String?): String = withContext(Dispatchers.IO) {
        runCatching {
            (sslFactory().createSocket(host, CastProto.CAST_PORT) as SSLSocket).use { sock ->
                sock.soTimeout = 6000
                sock.startHandshake()
                val out = DataOutputStream(sock.getOutputStream())
                val inp = DataInputStream(sock.getInputStream())
                send(out, CastProto.NS_CONNECTION, CastProto.connectPayload(), CastProto.RECEIVER_ID)

                when (action) {
                    "status" -> {
                        send(out, CastProto.NS_RECEIVER, CastProto.getStatusPayload(1), CastProto.RECEIVER_ID)
                        readFrames(inp, 3)
                    }
                    "stop" -> {
                        send(out, CastProto.NS_RECEIVER, CastProto.getStatusPayload(1), CastProto.RECEIVER_ID)
                        val status = readFrames(inp, 3)
                        val sid = CastProto.parseSessionId(status) ?: return@use "Немає активного застосунку для зупинки."
                        send(out, CastProto.NS_RECEIVER, CastProto.stopPayload(sid, 2), CastProto.RECEIVER_ID)
                        "Зупинено (session=$sid)."
                    }
                    "play" -> {
                        val url = mediaUrl ?: return@use "Потрібен media_url для play."
                        send(out, CastProto.NS_RECEIVER, CastProto.launchPayload(CastProto.DEFAULT_MEDIA_APP, 1), CastProto.RECEIVER_ID)
                        val status = readFrames(inp, 5)
                        val transport = CastProto.parseTransportId(status)
                            ?: return@use "Отримано статус, але transportId не знайдено:\n${status.take(400)}"
                        send(out, CastProto.NS_CONNECTION, CastProto.connectPayload(), transport)
                        send(out, CastProto.NS_MEDIA,
                            CastProto.loadPayload(url, CastProto.guessContentType(url), 2), transport)
                        val media = readFrames(inp, 4)
                        "LOAD надіслано на $host (transport=$transport).\n${media.take(400)}"
                    }
                    else -> "Невідома дія: $action"
                }
            }
        }.getOrElse { "Cast помилка (${it.message}). Переконайся, що пристрій Cast увімкнено у твоїй мережі." }
    }

    private fun readFrames(inp: DataInputStream, max: Int): String {
        val sb = StringBuilder()
        repeat(max) { readFrame(inp)?.let { sb.append(it).append('\n') } }
        return sb.toString()
    }
}
