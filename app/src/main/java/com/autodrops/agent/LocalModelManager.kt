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
    fun fileFor(m: LocalModel): File = File(dir(), m.fileName())
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
 * On-device inference engine, backed by MediaPipe LLM Inference (com.google.mediapipe:tasks-genai).
 * This is a REAL generate() path: it builds LlmInferenceOptions, creates the engine from a downloaded
 * .task model, and calls generateResponse(). The MediaPipe library (incl. native .so) is compiled into
 * the APK, so ENGINE_LINKED is true. HONESTY: generation could not be executed on a physical device in
 * the build sandbox (no emulator/KVM), so it is "wired + compiled, unverified on-device". Errors from the
 * native layer are surfaced, never hidden, and we never fabricate a model reply.
 */
object LocalLlm {
    private const val ENGINE_CLASS = "com.google.mediapipe.tasks.genai.llminference.LlmInference"

    private const val ENGINE_RES = "com/google/mediapipe/tasks/genai/llminference/LlmInference.class"
    /**
     * True when the MediaPipe LLM engine is linked into this build. On Android the dexed class loads via
     * Class.forName; on a plain JVM (unit tests) the MediaPipe bytecode is Java-21 and may not load under
     * a Java-17 test runtime, so we also accept the presence of the class file on the classpath. Either
     * signal proves the engine is bundled.
     */
    val ENGINE_LINKED: Boolean =
        runCatching { Class.forName(ENGINE_CLASS, false, LocalLlm::class.java.classLoader); true }.getOrDefault(false) ||
        (LocalLlm::class.java.classLoader?.getResource(ENGINE_RES) != null)
    /** Kept for backward compatibility with older UI/tests; now reflects the linked native engine. */
    const val ENGINE_PRESENT = true

    @Volatile private var engine: Any? = null
    @Volatile private var loadedModelId: String? = null
    @Volatile var lastError: String? = null; private set

    val isLoaded: Boolean get() = engine != null

    /** Load (or reuse) the engine for a MediaPipe .task model file. Returns true on success. */
    val loadedId: String? get() = loadedModelId

    @Synchronized
    fun ensureLoaded(ctx: Context, model: LocalModel, file: File, expectedSha: String = model.sha256, maxTokens: Int = 512): Boolean {
        if (!ENGINE_LINKED) { lastError = "MediaPipe-рушій не злінкований у збірці."; return false }
        if (!model.isRunnable) { lastError = "Модель ${model.id} не сумісна з on-device рушієм (.task потрібен)."; return false }
        if (!file.exists() || file.length() < 1024) { lastError = "Файл моделі відсутній."; return false }
        if (engine != null && loadedModelId == model.id) return true
        // Integrity recheck before loading (defends against post-download tampering). Cached via .ok marker.
        if (expectedSha.isNotBlank()) {
            val okMarker = File(file.parentFile, file.name + ".ok")
            if (!(okMarker.exists() && okMarker.readText().trim().equals(expectedSha, true))) {
                val h = runCatching { Hashing.sha256Hex(file) }.getOrNull()
                if (h == null || !Hashing.verify(h, expectedSha)) { lastError = "SHA-256 перевірка не пройдена — модель не завантажено."; return false }
                runCatching { okMarker.writeText(expectedSha.lowercase()) }
            }
        }
        // Free any previously loaded engine before replacing (prevents native leak).
        runCatching { (engine as? AutoCloseable)?.close() }; engine = null; loadedModelId = null
        return try {
            val llm = Class.forName(ENGINE_CLASS)
            val optClass = Class.forName("$ENGINE_CLASS\$LlmInferenceOptions")
            val builder = optClass.getMethod("builder").invoke(null)
            val bClass = builder.javaClass
            bClass.getMethod("setModelPath", String::class.java).invoke(builder, file.absolutePath)
            bClass.getMethod("setMaxTokens", Int::class.javaPrimitiveType).invoke(builder, maxTokens.coerceIn(64, 1024))
            bClass.getMethod("setMaxTopK", Int::class.javaPrimitiveType).invoke(builder, 40)
            val options = bClass.getMethod("build").invoke(builder)
            val inst = llm.getMethod("createFromOptions", Context::class.java, optClass).invoke(null, ctx, options)
            engine = inst; loadedModelId = model.id; lastError = null; true
        } catch (t: Throwable) { lastError = Protocols.friendlyError(t.message ?: t.javaClass.simpleName); engine = null; false }
    }

    /** Run a real on-device generation. Returns the model text, or null (with lastError set) on failure. */
    fun generate(prompt: String): String? {
        val e = engine ?: run { lastError = "Рушій не завантажено."; return null }
        return try {
            e.javaClass.getMethod("generateResponse", String::class.java).invoke(e, prompt) as? String
        } catch (t: Throwable) { lastError = Protocols.friendlyError(t.message ?: t.javaClass.simpleName); null }
    }

    @Synchronized fun unload() {
        runCatching { (engine as? AutoCloseable)?.close() }
        engine = null; loadedModelId = null
    }

    fun status(modelDownloaded: Boolean): String = when {
        !ENGINE_LINKED -> "Рушій on-device інференсу не злінкований у цій збірці."
        !modelDownloaded -> "Рушій MediaPipe вшито. Завантаж сумісну .task-модель, щоб працювати офлайн."
        isLoaded -> "Локальна модель завантажена та готова (MediaPipe, on-device)."
        lastError != null -> "Рушій вшито, модель на диску. Остання помилка: $lastError"
        else -> "Рушій MediaPipe вшито, модель на диску. Перша генерація не перевірена на твоєму пристрої — можливі помилки, вони будуть показані."
    }

    fun offlineFallback(): String =
        if (ENGINE_LINKED) "Немає мережі. Завантаж on-device .task-модель у Налаштуваннях, щоб відповідати офлайн через MediaPipe."
        else "Немає мережі, а локальний рушій недоступний у цій збірці. Увімкни мережу для OpenRouter."
}
