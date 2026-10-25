package com.autodrops.agent

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * v3.19: GGUF / llama.cpp wiring — catalog entry, RAM/quant selection, facade routing,
 * download/verify still works, honesty about what is linked vs run-verified.
 */
@RunWith(RobolectricTestRunner::class)
class V319Test {
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    @Test fun llama_cpp_aar_is_linked() {
        assertTrue("llama-android AAR must be on the classpath", GgufLlm.ENGINE_LINKED)
        assertNotNull(javaClass.classLoader!!.getResource("dev/ffmpegkit/llama/Llama.class"))
    }

    @Test fun catalog_has_pinned_uncensored_gguf() {
        val m = ModelCatalogLocal.byId("llama32-1b-instruct-ablit-q4")
        assertNotNull(m)
        m!!
        assertEquals("gguf", m.engine)
        assertTrue(m.name.contains("без цензури"))
        assertTrue(m.note.contains("Без цензури") || m.note.contains("без цензури") || m.note.contains("abliterated"))
        assertEquals(64, m.sha256.length)
        assertTrue(m.sha256.all { it in "0123456789abcdef" })
        assertTrue(m.url.startsWith("https://"))
        assertTrue(m.fileName().endsWith(".gguf"))
        assertTrue("pinned uncensored GGUF must be runnable when engine linked", m.isRunnable)
        assertTrue(m.note.contains("не перевірено") || m.note.contains("не перевірена"))
    }

    @Test fun gguf_is_runnable_via_ondevice_facade() {
        val m = ModelCatalogLocal.byId("llama32-1b-instruct-ablit-q4")!!
        assertTrue(OnDeviceLlm.isRunnable(m))
        assertEquals("llama.cpp (GGUF)", OnDeviceLlm.engineLabel(m))
        val mp = ModelCatalogLocal.runnable().first { it.engine == "mediapipe" }
        assertEquals("MediaPipe", OnDeviceLlm.engineLabel(mp))
    }

    @Test fun tofu_gguf_stays_in_catalog_but_not_auto_preferred() {
        val tofu = ModelCatalogLocal.CATALOG.filter { it.engine == "gguf" && it.sha256.isBlank() }
        assertTrue(tofu.isNotEmpty())
        // pickGguf prefers pinned
        val pick = GgufConfig.pickGguf(ModelCatalogLocal.CATALOG, availableRamMb = 8192, lowRam = false)
        assertNotNull(pick)
        assertTrue(pick!!.sha256.isNotBlank())
    }

    @Test fun ram_config_tightens_on_low_ram() {
        val ok = GgufConfig.choose(sizeMb = 400, availableRamMb = 8192, lowRam = false)
        assertEquals(2048, ok.contextSize)
        assertEquals(4, ok.threads)
        val tight = GgufConfig.choose(sizeMb = 912, availableRamMb = 1024, lowRam = true)
        assertEquals(512, tight.contextSize)
        assertEquals(2, tight.threads)
        assertEquals(128, tight.maxTokens)
        val mid = GgufConfig.choose(sizeMb = 400, availableRamMb = 1500, lowRam = false)
        assertTrue(mid.contextSize <= 1024)
    }

    @Test fun gguf_magic_detector() {
        val good = File(ctx.cacheDir, "good.gguf").apply {
            outputStream().use { it.write(byteArrayOf('G'.code.toByte(), 'G'.code.toByte(), 'U'.code.toByte(), 'F'.code.toByte(), 0, 1, 2, 3)) }
        }
        val bad = File(ctx.cacheDir, "bad.bin").apply { writeText("NOT-GGUF-CONTENT-XXXX") }
        assertTrue(GgufConfig.hasGgufMagic(good))
        assertFalse(GgufConfig.hasGgufMagic(bad))
        assertFalse(GgufConfig.hasGgufMagic(File(ctx.cacheDir, "missing.gguf")))
    }

    @Test fun gguf_ensureLoaded_gates_on_missing_file_and_bad_magic() {
        GgufLlm.unload()
        val m = ModelCatalogLocal.byId("llama32-1b-instruct-ablit-q4")!!
        assertFalse(GgufLlm.ensureLoaded(ctx, m, File(ctx.filesDir, "nope.gguf")))
        assertNotNull(GgufLlm.lastError)

        val bad = File(ctx.filesDir, "fake.gguf").apply { writeText("x".repeat(2048)) }
        assertFalse(GgufLlm.ensureLoaded(ctx, m, bad))
        assertTrue(GgufLlm.lastError!!.contains("GGUF") || GgufLlm.lastError!!.contains("магіч"))
        assertFalse(GgufLlm.isLoaded)
    }

    @Test fun gguf_ensureLoaded_rejects_mediapipe_model() {
        GgufLlm.unload()
        val mp = ModelCatalogLocal.runnable().first { it.engine == "mediapipe" }
        val f = File(ctx.filesDir, "x.task").apply { writeText("x".repeat(2048)) }
        assertFalse(GgufLlm.ensureLoaded(ctx, mp, f))
    }

    @Test fun generate_before_load_returns_null() {
        GgufLlm.unload()
        assertNull(GgufLlm.generate("hi"))
        assertNotNull(GgufLlm.lastError)
    }

    @Test fun ondevice_facade_routes_and_unloads_both() {
        OnDeviceLlm.unload()
        assertFalse(OnDeviceLlm.isLoaded)
        assertNull(OnDeviceLlm.generate("x"))
        assertTrue(OnDeviceLlm.offlineFallback().isNotBlank())
        assertTrue(OnDeviceLlm.anyEngineLinked)
    }

    @Test fun local_model_json_roundtrip_preserves_gguf() {
        val m = ModelCatalogLocal.byId("llama32-1b-instruct-ablit-q4")!!
        assertEquals(m, LocalModel.fromJson(m.toJson()))
    }

    @Test fun multipart_download_urls_still_work_for_gemma() {
        // regression: gemma multipart path untouched
        val g = ModelCatalogLocal.byId("gemma2-2b-it-ablit-mp-int4")!!
        assertEquals(3, g.downloadUrls().size)
        assertTrue(g.downloadUrls().all { it.startsWith("https://") })
    }
}
