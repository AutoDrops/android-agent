package com.autodrops.agent

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import java.io.File
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/** A downloadable Vosk offline-STT model (zip that extracts to a model directory). SHA-256 pinned. */
data class VoskModel(
    val id: String, val lang: String, val name: String, val url: String,
    val sizeMb: Int, val sha256: String, val note: String = ""
) {
    private fun safeId() = id.filter { it.isLetterOrDigit() || it in "._-" }.replace("..", "_").ifBlank { "vosk" }
    fun dirName() = "vosk_" + safeId()
}

/** Curated small Vosk models (downloaded on demand, never bundled into the APK). Hashes pinned. */
object VoskCatalog {
    val CATALOG = listOf(
        VoskModel("small-uk", "uk", "Українська (small)", "https://alphacephei.com/vosk/models/vosk-model-small-uk-v3-small.zip",
            138, "ee05b6a8c796c53c61cd1c821d38e897e5bd67b4d691d8797ec8f9e5100d4247", "~138 МБ архів"),
        VoskModel("small-ru", "ru", "Русский (small)", "https://alphacephei.com/vosk/models/vosk-model-small-ru-0.22.zip",
            45, "961d5ff98a17f4aa6de69864d0aa71fa5bac682301d2b5d17a3f24c5c99a46d4", "~45 МБ архів"),
        VoskModel("small-en-us", "en", "English US (small)", "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip",
            40, "30f26242c4eb449f948e42cb302dd7a686cb29a3423a8367f99ff41780942498", "~40 МБ архів")
    )
    fun byId(id: String) = CATALOG.firstOrNull { it.id == id }
}

/**
 * Downloads + verifies (SHA-256) + extracts Vosk model zips into app-private storage.
 * Defenses: HTTPS-only, host-locked redirect, disk preflight, download byte cap, SHA-256 pin verify,
 * zip-slip guard, zip-bomb caps (entry count + decompressed bytes), atomic rename, failure cleanup.
 */
class VoskManager(private val ctx: Context) {
    private val http = OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS).readTimeout(180, TimeUnit.SECONDS).build()
    private fun base(): File = File(ctx.filesDir, "vosk").apply { mkdirs() }
    fun modelDir(m: VoskModel): File = File(base(), m.dirName())
    fun resolvedModelPath(m: VoskModel): File? {
        val d = modelDir(m); if (!d.isDirectory) return null
        if (File(d, "am").exists() || File(d, "conf").exists()) return d
        return d.listFiles()?.firstOrNull { it.isDirectory && (File(it, "am").exists() || File(it, "conf").exists()) } ?: d
    }
    fun isInstalled(m: VoskModel): Boolean = resolvedModelPath(m)?.let { File(it, "conf").exists() || File(it, "am").exists() } ?: false
    fun delete(m: VoskModel): Boolean = modelDir(m).deleteRecursively()

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    data class Progress(val bytes: Long, val total: Long, val done: Boolean = false, val error: String? = null)

    suspend fun download(m: VoskModel, onProgress: (Progress) -> Unit): Progress = withContext(Dispatchers.IO) {
        if (!m.url.startsWith("https://")) return@withContext Progress(0, 0, error = "HTTPS обовʼязковий")
        if (m.sha256.length != 64) return@withContext Progress(0, 0, error = "Немає зафіксованого SHA-256 — завантаження заблоковано")
        val free = runCatching { android.os.StatFs(ctx.filesDir.path).availableBytes }.getOrDefault(Long.MAX_VALUE)
        if (free < m.sizeMb * 3L * 1_048_576L) return@withContext Progress(0, 0, error = "Недостатньо місця (~${m.sizeMb * 3} МБ з урахуванням розпакування).")
        val expectHost = m.url.toHttpUrlOrNull()?.host
        val tmp = File(base(), m.dirName() + ".zip")
        val stage = File(base(), m.dirName() + ".tmp")
        val dest = modelDir(m)
        val dlCap = m.sizeMb * 2L * 1_048_576L              // download hard cap
        val unzipCap = m.sizeMb * 6L * 1_048_576L           // decompressed hard cap (zip-bomb guard)
        val maxEntries = 50_000
        try {
            val resp = http.newCall(Request.Builder().url(m.url).build()).execute()
            if (!resp.isSuccessful) { resp.close(); return@withContext Progress(0, 0, error = "HTTP ${resp.code}") }
            if (expectHost != null && resp.request.url.host != expectHost) { resp.close(); return@withContext Progress(0, 0, error = "Заблоковано міжхостовий редірект") }
            val body = resp.body ?: return@withContext Progress(0, 0, error = "порожня відповідь")
            val total = body.contentLength()
            val md = MessageDigest.getInstance("SHA-256")
            DigestInputStream(body.byteStream(), md).use { ins -> tmp.outputStream().use { out ->
                val buf = ByteArray(1 shl 16); var got = 0L; var last = 0L
                while (true) {
                    val n = ins.read(buf); if (n < 0) break
                    got += n
                    if (got > dlCap) throw SecurityException("Перевищено ліміт розміру завантаження")
                    out.write(buf, 0, n)
                    if (got - last > 2_000_000) { onProgress(Progress(got, total)); last = got }
                }
            } }
            val got = hex(md.digest())
            if (!got.equals(m.sha256, ignoreCase = true)) return@withContext Progress(0, total, error = "SHA-256 не збігається — файл відхилено")
            // atomic-ish: extract to stage, then swap into dest
            stage.deleteRecursively(); stage.mkdirs()
            var entries = 0; var written = 0L
            ZipInputStream(tmp.inputStream()).use { zis ->
                var e = zis.nextEntry
                while (e != null) {
                    if (++entries > maxEntries) throw SecurityException("Забагато записів у архіві")
                    val outFile = File(stage, e.name)
                    if (!outFile.canonicalPath.startsWith(stage.canonicalPath + File.separator)) throw SecurityException("zip-slip: ${e.name}")
                    if (e.isDirectory) outFile.mkdirs() else {
                        outFile.parentFile?.mkdirs()
                        outFile.outputStream().use { o ->
                            val buf = ByteArray(1 shl 16)
                            while (true) { val n = zis.read(buf); if (n < 0) break; written += n
                                if (written > unzipCap) throw SecurityException("Перевищено ліміт розпакування (zip-bomb)"); o.write(buf, 0, n) }
                        }
                    }
                    e = zis.nextEntry
                }
            }
            tmp.delete()
            dest.deleteRecursively()
            if (!stage.renameTo(dest)) { stage.copyRecursively(dest, overwrite = true); stage.deleteRecursively() }
            if (!isInstalled(m)) { dest.deleteRecursively(); return@withContext Progress(0, total, error = "Структуру моделі не знайдено після розпакування.") }
            Progress(written, total, done = true)
        } catch (t: Throwable) {
            tmp.delete(); stage.deleteRecursively(); dest.deleteRecursively()
            Progress(0, 0, error = Protocols.friendlyError(t.message))
        }
    }
}
