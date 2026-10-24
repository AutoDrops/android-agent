package com.autodrops.agent

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** Settings in EncryptedSharedPreferences (AES-256-GCM; master key lives in Android Keystore). Key is never logged. */
class SecureStore(ctx: Context) {
    private val prefs = EncryptedSharedPreferences.create(
        ctx, "secure_settings",
        MasterKey.Builder(ctx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )
    var apiKey: String
        get() = prefs.getString("api_key", "") ?: ""
        set(v) = prefs.edit().putString("api_key", v.trim()).apply()
    var baseUrl: String
        get() = prefs.getString("base_url", DEFAULT_BASE) ?: DEFAULT_BASE
        set(v) = prefs.edit().putString("base_url", v.trim().trimEnd('/')).apply()
    var model: String
        get() = prefs.getString("model", DEFAULT_MODEL) ?: DEFAULT_MODEL
        set(v) = prefs.edit().putString("model", v.trim()).apply()
    var stream: Boolean
        get() = prefs.getBoolean("stream", true)
        set(v) = prefs.edit().putBoolean("stream", v).apply()
    var shellMode: String   // sandbox | shizuku | root
        get() = prefs.getString("shell_mode", "sandbox") ?: "sandbox"
        set(v) = prefs.edit().putString("shell_mode", v).apply()
    var favorites: Set<String>
        get() = prefs.getStringSet("favorites", emptySet()) ?: emptySet()
        set(v) = prefs.edit().putStringSet("favorites", v).apply()
    var disabledTools: Set<String>
        get() = prefs.getStringSet("disabled_tools", emptySet()) ?: emptySet()
        set(v) = prefs.edit().putStringSet("disabled_tools", v).apply()

    /** Refuse to send the key over plaintext HTTP. */
    fun baseUrlIsSafe() = baseUrl.startsWith("https://")

    companion object {
        const val DEFAULT_BASE = "https://openrouter.ai/api/v1"
        const val DEFAULT_MODEL = "openai/gpt-4o-mini"
    }
}
