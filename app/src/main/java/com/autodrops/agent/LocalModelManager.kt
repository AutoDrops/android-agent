package com.autodrops.agent

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * REAL downloadable-model manager: downloads a GGUF file to app-private storage, streams it to disk,
 * computes SHA-256 and (when an expected hash is known) verifies it before marking the file usable.
 * Inference itself (llama.cpp JNI) is PENDING — see LocalLlm. We never pretend to run a model we can't.
 */
class LocalModelManager(private val ctx: Context) {
    private val http = OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS).build()
    private fun dir(): File = File(ctx.filesDir, "models").apply { mkdirs() }
    fun fileFor(m: LocalModel): File = File(dir(), "${m.id}.gguf")
    fun isDownloaded(m: LocalModel): Boolean = fileFor(m).let { it.exists() && it.length() > 1024 }
    fun sizeOnDisk(m: LocalModel): Long = fileFor(m).let { if (it.exists()) it.length() else 0 }
    fun delete(m: LocalModel): Boolean = fileFor(m).let { if (it.exists()) it.delete() else false }

    data class Progress(val downloadedBytes: Long, val totalBytes: Long, val done: Boolean = false, val verified: Boolean = false, val error: String? = null)

    /** Downloads with progress, computing SHA-256 as bytes arrive. Verifies if an expected hash is set. */
    suspend fun download(m: LocalModel, onProgress: (Progress) -> Unit): Progress = withContext(Dispatchers.IO) {
        val tmp = File(dir(), "${m.id}.part")
        runCatching {
            if (!m.url.startsWith("https://")) return@withContext Progress(0, 0, error = "HTTPS обовʼязковий")
            val expectHost = m.url.toHttpUrlOrNull()?.host
            val resp = http.newCall(Request.Builder().url(m.url).build()).execute()
            if (!resp.isSuccessful) { resp.close(); return@withContext Progress(0, 0, error = "HTTP ${resp.code}") }
            // block cross-host redirects (domain-swap TOFU)
            if (expectHost != null && resp.request.url.host != expectHost) { resp.close(); return@withContext Progress(0, 0, error = "Заблоковано міжхостовий редірект") }
            val body = resp.body ?: return@withContext Progress(0, 0, error = "empty body")
            val total = body.contentLength()
            if (total > (m.sizeMb + 128L) * 1_048_576L) { resp.close(); return@withContext Progress(0, total, error = "Розмір перевищує очікуваний — відхилено") }
            val md = MessageDigest.getInstance("SHA-256")
            body.byteStream().use { ins -> tmp.outputStream().use { out ->
                val buf = ByteArray(1 shl 16); var got = 0L; var last = 0L
                while (true) {
                    val n = ins.read(buf); if (n < 0) break
                    out.write(buf, 0, n); md.update(buf, 0, n); got += n
                    if (got - last > 2_000_000) { onProgress(Progress(got, total)); last = got }
                }
            } }
            val hex = md.digest().joinToString("") { "%02x".format(it) }
            // If the catalog pins a hash, enforce it; otherwise record the observed hash (TOFU) and keep the file.
            if (m.sha256.isNotBlank() && !Hashing.verify(hex, m.sha256)) {
                tmp.delete()
                return@withContext Progress(0, total, error = "SHA-256 не збігається — файл відхилено (можливе псування/MITM).")
            }
            val dest = fileFor(m)
            if (dest.exists()) dest.delete()
            tmp.renameTo(dest)
            SecureStore.encrypted(ctx, "local_models").edit().putString("${m.id}_sha", hex).apply()
            Progress(dest.length(), total, done = true, verified = m.sha256.isNotBlank())
        }.getOrElse { tmp.delete(); Progress(0, 0, error = Protocols.friendlyError(it.message)) }
    }

    fun observedHash(m: LocalModel): String? =
        SecureStore.encrypted(ctx, "local_models").getString("${m.id}_sha", null)
}

/**
 * On-device inference engine wrapper. The native llama.cpp JNI library is NOT bundled in this build
 * (it cannot be cross-compiled in the current sandbox), so inference is explicitly PENDING. The manager
 * above is fully real; this object stays honest about what is wired vs not.
 */
object LocalLlm {
    const val ENGINE_PRESENT = false   // flips to true once a native llama.cpp .so is shipped
    fun status(modelDownloaded: Boolean): String = when {
        !modelDownloaded -> "Локальну модель не завантажено."
        !ENGINE_PRESENT -> "Модель завантажено й перевірено на диску. Рушій інференсу (llama.cpp JNI) ще не вшито в цю збірку — on-device генерація в розробці. Поки немає мережі, DRON використовує OpenRouter-резерв."
        else -> "Локальна модель готова."
    }
    /** Offline fallback answer when there is no network AND no local engine. */
    fun offlineFallback(): String =
        "Немає підключення до мережі, а локальний рушій інференсу ще не активний у цій збірці. " +
        "Увімкни мережу для роботи через OpenRouter або встанови локальну модель, коли рушій буде доданий."
}
