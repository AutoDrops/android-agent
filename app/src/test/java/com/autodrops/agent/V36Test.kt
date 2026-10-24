package com.autodrops.agent

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class V36Test {
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    @Test fun vosk_catalog_has_uk_ru_en_https() {
        val langs = VoskCatalog.CATALOG.map { it.lang }.toSet()
        assertTrue(langs.containsAll(listOf("uk", "ru", "en")))
        VoskCatalog.CATALOG.forEach {
            assertTrue("vosk model must be HTTPS", it.url.startsWith("https://"))
            assertTrue("vosk model must declare a size", it.sizeMb > 0)
            assertTrue(it.url.endsWith(".zip"))
            assertEquals("vosk model must pin a 64-hex SHA-256", 64, it.sha256.length)
            assertTrue(it.sha256.all { c -> c in "0123456789abcdef" })
        }
    }

    @Test fun vosk_download_blocks_http_and_unpinned() = kotlinx.coroutines.runBlocking {
        val mgr = VoskManager(ctx)
        val http = VoskModel("x", "uk", "x", "http://h/x.zip", 10, "a".repeat(64))
        assertNotNull(mgr.download(http) {}.error)
        val unpinned = VoskModel("y", "uk", "y", "https://h/y.zip", 10, "")
        assertNotNull(mgr.download(unpinned) {}.error)
    }

    @Test fun vosk_dir_name_is_sanitized() {
        val m = VoskModel("../evil id", "uk", "x", "https://h/x.zip", 10, "a".repeat(64))
        assertFalse(m.dirName().contains("/"))
        assertFalse(m.dirName().contains(".."))
        assertTrue(m.dirName().startsWith("vosk_"))
    }

    @Test fun vosk_not_installed_by_default() {
        val mgr = VoskManager(ctx)
        VoskCatalog.CATALOG.forEach { assertFalse(mgr.isInstalled(it)) }
    }

    @Test fun vosk_class_bundled_for_offline_stt() {
        // Proves the Vosk engine is linked into the build (download-on-demand only applies to the model data).
        assertNotNull(javaClass.classLoader!!.getResource("org/vosk/Model.class"))
        assertNotNull(javaClass.classLoader!!.getResource("org/vosk/android/SpeechService.class"))
    }

    @Test fun selfcheck_does_not_fake_generation_without_model() {
        // No model on disk → self-check must report engine state honestly, never a fabricated result.
        assertNull(LocalLlm.loadedId)
        val m = ModelCatalogLocal.runnable().first()
        // generate before load must be null (no fake output)
        LocalLlm.unload()
        assertNull("generate without load must return null, never a stub", LocalLlm.generate("hi"))
    }
}
