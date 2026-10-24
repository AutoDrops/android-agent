package com.autodrops.agent

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ModelSelectionTest {
    private fun prefs() = ApplicationProvider.getApplicationContext<Context>()
        .getSharedPreferences("test_settings", Context.MODE_PRIVATE)
    @Test fun oneTapSelectionPersists() {
        val p = prefs(); p.edit().clear().commit()
        SecureStore(p).model = "x-ai/grok-4.6"
        // new store instance over the SAME prefs = simulates app restart
        assertEquals("x-ai/grok-4.6", SecureStore(p).model)
    }
    @Test fun embeddedKeySeedThenClear() {
        val p = prefs(); p.edit().clear().commit()
        val store = SecureStore(p)
        store.clearApiKey()
        store.seedEmbeddedKeyIfNeeded() // after clear, must not re-seed
        assertEquals("", store.apiKey)
    }
    @Test fun buildConfigKeyFieldExists() {
        // Field is injected at build time; in git-tracked source it is only a reference, never a literal key.
        assertNotNull(BuildConfig.EMBEDDED_API_KEY)
    }
}
