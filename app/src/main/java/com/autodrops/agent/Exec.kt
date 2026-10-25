package com.autodrops.agent

import android.content.Context
import kotlinx.coroutines.*
import java.io.File
import java.util.concurrent.TimeUnit

/** Command execution backends. sandbox = app UID; shizuku = shell UID via Shizuku (if bound); root = su; termux = Termux RUN_COMMAND intent. */
object Exec {
    private const val MAX_OUT = 32_000

    fun workspace(ctx: Context): File = File(ctx.filesDir, "workspace").apply { mkdirs() }

    suspend fun run(ctx: Context, cmd: String, timeoutSec: Int, backend: String, cwd: File = workspace(ctx)): String =
        when (backend) {
            "root" -> process(ctx, listOf("su", "-c", "cd '${cwd.absolutePath}' && $cmd"), timeoutSec, cwd)
            else -> process(ctx, listOf("sh", "-c", cmd), timeoutSec, cwd)
        }

    private suspend fun process(ctx: Context, argv: List<String>, timeoutSec: Int, cwd: File): String = withContext(Dispatchers.IO) {
        val pb = ProcessBuilder(argv).directory(cwd)
        val env = pb.environment()
        val keep = setOf("PATH", "ANDROID_DATA", "ANDROID_ROOT", "BOOTCLASSPATH", "ANDROID_ART_ROOT",
            "ANDROID_I18N_ROOT", "ANDROID_TZDATA_ROOT", "LD_LIBRARY_PATH")
        env.keys.retainAll(keep)
        env["HOME"] = cwd.absolutePath
        env["TMPDIR"] = ctx.cacheDir.absolutePath
        val t0 = System.currentTimeMillis()
        val p = pb.start()
        val out = StringBuilder(); val err = StringBuilder()
        var truncated = false
        val reader = Thread {
            runCatching {
                p.inputStream.bufferedReader().use { r ->
                    var line = r.readLine()
                    while (line != null) {
                        synchronized(out) {
                            if (out.length < MAX_OUT) out.appendLine(line) else truncated = true
                        }
                        line = r.readLine()
                    }
                }
            }
        }.apply { isDaemon = true; start() }
        val errReader = Thread {
            runCatching { p.errorStream.bufferedReader().use { r -> r.lineSequence().forEach { l -> synchronized(err) { if (err.length < MAX_OUT / 2) err.appendLine(l) } } } }
        }.apply { isDaemon = true; start() }
        // Poll so the coroutine stays cancellable; a cancel (Stop button) kills the process tree.
        var finished = false
        var cancelled = false
        val deadline = System.currentTimeMillis() + timeoutSec * 1000L
        try {
            while (System.currentTimeMillis() < deadline) {
                ensureActive()
                if (p.waitFor(200, TimeUnit.MILLISECONDS)) { finished = true; break }
            }
        } catch (ce: CancellationException) {
            cancelled = true
        }
        if (!finished) {
            val pid = pidOf(p)
            if (pid > 0) runCatching { ProcessBuilder("pkill", "-P", pid.toString()).start() }
            p.destroyForcibly()
            if (cancelled) { reader.join(500); throw CancellationException("terminal stopped") }
        }
        reader.join(1500); errReader.join(500)
        val code = if (finished) p.exitValue() else -1
        format(code, finished, timeoutSec, System.currentTimeMillis() - t0, synchronized(out) { out.toString() }, synchronized(err) { err.toString() }, truncated)
    }

    /** "exit=N · Xms" header (parsed by the UI), then stdout, then a separate stderr section. */
    fun format(code: Int, finished: Boolean, timeoutSec: Int, ms: Long, stdout: String, stderr: String, truncated: Boolean = false) = buildString {
        append("exit=").append(code)
        if (!finished) append(" (timeout ${timeoutSec}s, killed)")
        append(" · ").append(ms).append("ms\n")
        append(stdout)
        if (truncated) append("\n...[output truncated]")
        if (stderr.isNotBlank()) { if (!endsWith("\n")) append('\n'); append("--- stderr ---\n").append(stderr.trimEnd()) }
        if (stdout.isBlank() && stderr.isBlank()) append("(немає виводу)")
    }

    private fun pidOf(p: Process): Long = runCatching {
        val f = p.javaClass.getDeclaredField("pid"); f.isAccessible = true; (f.get(p) as Int).toLong()
    }.getOrDefault(-1L)
}
