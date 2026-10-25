package com.autodrops.agent

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class V35Test {
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    @Test fun engine_is_linked_into_build() {
        // Proves the MediaPipe LLM Inference engine is bundled in this build (class file present on classpath).
        assertTrue("MediaPipe LlmInference must be linked into the build", LocalLlm.ENGINE_LINKED)
        assertNotNull(javaClass.classLoader!!.getResource("com/google/mediapipe/tasks/genai/llminference/LlmInference.class"))
    }

    @Test fun catalog_has_real_runnable_model_pinned() {
        val runnable = ModelCatalogLocal.runnable()
        assertTrue("need at least one on-device .task model", runnable.isNotEmpty())
        val m = runnable.first()
        assertEquals("mediapipe", m.engine)
        assertTrue(m.isRunnable)
        assertTrue(m.fileName().endsWith(".task"))
        assertTrue(m.url.startsWith("https://"))
        assertEquals("pinned SHA-256 must be 64 hex chars", 64, m.sha256.length)
        assertTrue(m.sha256.all { it in "0123456789abcdef" })
    }

    @Test fun gguf_models_use_gguf_filename_and_runnable_when_engine_linked() {
        val gguf = ModelCatalogLocal.CATALOG.filter { it.engine == "gguf" }
        assertTrue(gguf.isNotEmpty())
        gguf.forEach {
            assertTrue(it.fileName().endsWith(".gguf"))
            // v3.19: GGUF is runnable iff the llama.cpp AAR is linked into this build.
            assertEquals(GgufLlm.ENGINE_LINKED, it.isRunnable)
        }
    }

    @Test fun model_json_roundtrip_preserves_engine() {
        val m = ModelCatalogLocal.runnable().first()
        assertEquals(m, LocalModel.fromJson(m.toJson()))
    }

    @Test fun generate_before_load_returns_null_with_error() {
        LocalLlm.unload()
        assertNull(LocalLlm.generate("hello"))
        assertNotNull(LocalLlm.lastError)
    }

    @Test fun ensureLoaded_gates_on_model_presence() {
        LocalLlm.unload()
        val m = ModelCatalogLocal.runnable().first()
        // No file on disk -> must refuse (presence gating), never pretend success.
        assertFalse(LocalLlm.ensureLoaded(ctx, m, File(ctx.filesDir, "does-not-exist.task")))
        assertFalse(LocalLlm.isLoaded)
    }

    @Test fun ensureLoaded_rejects_gguf_model_mediapipe_only() {
        // LocalLlm is MediaPipe-only; GGUF must go through GgufLlm / OnDeviceLlm.
        LocalLlm.unload()
        val gguf = ModelCatalogLocal.CATALOG.first { it.engine == "gguf" }
        val f = File(ctx.filesDir, "fake.gguf").apply { writeText("x".repeat(2048)) }
        assertFalse(LocalLlm.ensureLoaded(ctx, gguf, f))
        assertFalse(LocalLlm.isLoaded)
    }

    @Test fun status_reflects_engine_and_model_gating() {
        assertTrue(LocalLlm.ENGINE_PRESENT)
        assertTrue(LocalLlm.ENGINE_LINKED)
        assertTrue(LocalLlm.status(false).contains("Завантаж"))
        assertTrue(LocalLlm.offlineFallback().isNotBlank())
    }
}
