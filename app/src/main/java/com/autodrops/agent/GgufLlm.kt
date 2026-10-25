package com.autodrops.agent

import android.app.ActivityManager
import android.content.Context
import dev.ffmpegkit.llama.Llama
import dev.ffmpegkit.llama.LlamaConfig
import dev.ffmpegkit.llama.LlamaModel
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * On-device GGUF inference via the published llama.cpp AAR
 * (`dev.ffmpegkit-maintained:llama-android:0.1.1`).
 *
 * REAL path: classes + native .so (arm64-v8a) are compiled into the APK, so [ENGINE_LINKED] is true
 * when the AAR is on the classpath. load/complete go through the library's suspend API.
 *
 * HONESTY:
 *  - Native generation was NOT executed on a physical device in this sandbox (no emulator/KVM).
 *  - The free AAR has no token streaming — [generateStream] delivers the full reply once, then calls
 *    [onPartial] with it (same contract as MediaPipe's blocking fallback).
 *  - Only arm64-v8a natives ship; on armeabi-v7a devices load will fail with a clear error.
 *  - Errors from the native layer are surfaced via [lastError], never fabricated into a model reply.
 */
object GgufLlm {
    private const val ENGINE_CLASS = "dev.ffmpegkit.llama.Llama"
    private const val ENGINE_RES = "dev/ffmpegkit/llama/Llama.class"

    /**
     * True when the llama.cpp AAR is linked into this build. On Android the class loads via
     * Class.forName; on a plain JVM (unit tests) we also accept the class file on the classpath.
     */
    val ENGINE_LINKED: Boolean =
        runCatching { Class.forName(ENGINE_CLASS, false, GgufLlm::class.java.classLoader); true }.getOrDefault(false) ||
            (GgufLlm::class.java.classLoader?.getResource(ENGINE_RES) != null)

    @Volatile private var model: LlamaModel? = null
    @Volatile private var loadedModelId: String? = null
    @Volatile var lastError: String? = null; private set

    val isLoaded: Boolean get() = model != null
    val loadedId: String? get() = loadedModelId

    /** RAM-aware config for a given GGUF model size (MB). Pure, unit-tested. */
    fun configFor(sizeMb: Int, availableRamMb: Long, lowRam: Boolean): GgufConfig.Choice =
        GgufConfig.choose(sizeMb, availableRamMb, lowRam)

    @Synchronized
    fun ensureLoaded(ctx: Context, modelInfo: LocalModel, file: File, expectedSha: String = modelInfo.sha256): Boolean {
        if (!ENGINE_LINKED) { lastError = "Рушій llama.cpp не злінкований у збірці."; return false }
        if (modelInfo.engine != "gguf") { lastError = "Модель ${modelInfo.id} не є GGUF."; return false }
        if (!file.exists() || file.length() < 1024) { lastError = "Файл моделі відсутній."; return false }
        if (!GgufConfig.hasGgufMagic(file)) { lastError = "Файл не виглядає як GGUF (немає магічного заголовка)."; return false }
        if (model != null && loadedModelId == modelInfo.id) return true
        if (expectedSha.isNotBlank()) {
            val okMarker = File(file.parentFile, file.name + ".ok")
            if (!(okMarker.exists() && okMarker.readText().trim().equals(expectedSha, true))) {
                val h = runCatching { Hashing.sha256Hex(file) }.getOrNull()
                if (h == null || !Hashing.verify(h, expectedSha)) {
                    lastError = "SHA-256 перевірка не пройдена — модель не завантажено."; return false
                }
                runCatching { okMarker.writeText(expectedSha.lowercase()) }
            }
        }
        unload()
        val am = runCatching { ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager }.getOrNull()
        val availMb = am?.let {
            val mi = ActivityManager.MemoryInfo(); it.getMemoryInfo(mi); mi.availMem / (1024 * 1024)
        } ?: 2048L
        val low = am?.isLowRamDevice == true
        val choice = configFor(modelInfo.sizeMb, availMb, low)
        return try {
            val cfg = LlamaConfig(
                contextSize = choice.contextSize,
                threads = choice.threads,
                gpuLayers = 0,
                temperature = 0.7f,
                topP = 0.9f,
                topK = 40,
                seed = -1
            )
            val loaded = runBlocking { Llama.loadModel(file.absolutePath, cfg) }
            if (!loaded.isLoaded) { lastError = "loadModel повернув незавантажену модель."; return false }
            model = loaded; loadedModelId = modelInfo.id; lastError = null; true
        } catch (t: Throwable) {
            lastError = Protocols.friendlyError(t.message ?: t.javaClass.simpleName)
            model = null; loadedModelId = null; false
        }
    }

    /** Blocking completion. Returns model text or null (with lastError set). */
    fun generate(prompt: String, systemPrompt: String = "", maxTokens: Int = 256): String? {
        val m = model ?: run { lastError = "Рушій llama.cpp не завантажено."; return null }
        return try {
            val result = runBlocking {
                Llama.complete(m, prompt, systemPrompt, maxTokens.coerceIn(16, 1024))
            }
            val text = result.text
            if (text.isBlank()) { lastError = "Порожня відповідь від llama.cpp."; null }
            else { lastError = null; text }
        } catch (t: Throwable) {
            lastError = Protocols.friendlyError(t.message ?: t.javaClass.simpleName); null
        }
    }

    /**
     * Streaming facade. Free AAR has no token Flow — delivers the full reply once via [onPartial].
     * Same contract as MediaPipe's blocking fallback. Honest about the limitation.
     */
    fun generateStream(
        prompt: String,
        onPartial: (String) -> Unit,
        systemPrompt: String = "",
        maxTokens: Int = 256
    ): String? {
        val full = generate(prompt, systemPrompt, maxTokens) ?: return null
        onPartial(full)
        return full
    }

    @Synchronized fun unload() {
        val m = model
        model = null; loadedModelId = null
        if (m != null) runCatching { Llama.releaseModel(m) }
    }

    fun status(modelDownloaded: Boolean): String = when {
        !ENGINE_LINKED -> "Рушій llama.cpp не злінкований у збірці."
        !modelDownloaded -> "Рушій llama.cpp вшито (arm64-v8a). Завантаж GGUF-модель, щоб працювати офлайн."
        isLoaded -> "GGUF-модель завантажена та готова (llama.cpp, on-device)."
        lastError != null -> "Рушій вшито, модель на диску. Остання помилка: $lastError"
        else -> "Рушій llama.cpp вшито, модель на диску. Перша генерація не перевірена на твоєму пристрої — можливі помилки, вони будуть показані."
    }
}

/**
 * Pure helpers for GGUF selection / RAM budgeting. No Android framework dependency beyond what
 * callers pass in — unit-tested on JVM.
 */
object GgufConfig {
    data class Choice(val contextSize: Int, val threads: Int, val maxTokens: Int, val reason: String)

    fun choose(sizeMb: Int, availableRamMb: Long, lowRam: Boolean): Choice {
        // Rule of thumb from the AAR README: need ~(size + 20%) free RAM.
        val needMb = (sizeMb * 1.2).toLong()
        val tight = lowRam || availableRamMb < needMb + 256
        val ctx = when {
            tight -> 512
            availableRamMb < needMb + 1024 -> 1024
            else -> 2048
        }
        val threads = when {
            lowRam -> 2
            availableRamMb < 2048 -> 3
            else -> 4
        }
        val maxTok = if (tight) 128 else 256
        val reason = if (tight) "low-RAM / tight budget → ctx=$ctx threads=$threads"
        else "ok → ctx=$ctx threads=$threads"
        return Choice(ctx, threads, maxTok, reason)
    }

    /** True if the file starts with the GGUF magic bytes. */
    fun hasGgufMagic(file: File): Boolean = runCatching {
        file.inputStream().use { ins ->
            val b = ByteArray(4)
            if (ins.read(b) != 4) return@runCatching false
            b[0] == 'G'.code.toByte() && b[1] == 'G'.code.toByte() &&
                b[2] == 'U'.code.toByte() && b[3] == 'F'.code.toByte()
        }
    }.getOrDefault(false)

    /** Prefer the smallest pinned-SHA uncensored GGUF when RAM is tight; else first runnable GGUF. */
    fun pickGguf(catalog: List<LocalModel>, availableRamMb: Long, lowRam: Boolean): LocalModel? {
        val gguf = catalog.filter { it.engine == "gguf" && it.sha256.isNotBlank() }
        if (gguf.isEmpty()) return catalog.firstOrNull { it.engine == "gguf" }
        val fits = gguf.filter { availableRamMb >= (it.sizeMb * 1.2).toLong() + 256 }
        val pool = if (lowRam || fits.isEmpty()) gguf.sortedBy { it.sizeMb } else fits.sortedBy { it.sizeMb }
        return pool.firstOrNull()
    }
}
