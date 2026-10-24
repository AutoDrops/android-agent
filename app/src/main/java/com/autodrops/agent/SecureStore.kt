package com.autodrops.agent

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** Settings in EncryptedSharedPreferences (AES-256-GCM; master key lives in Android Keystore). Key is never logged. */
class SecureStore internal constructor(private val prefs: android.content.SharedPreferences) {
    constructor(ctx: Context) : this(
        EncryptedSharedPreferences.create(
            ctx, "secure_settings",
            MasterKey.Builder(ctx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
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
    var matrixRain: Boolean
        get() = prefs.getBoolean("matrix_rain", true)
        set(v) = prefs.edit().putBoolean("matrix_rain", v).apply()
    var approvedHosts: Set<String>
        get() = prefs.getStringSet("approved_hosts", emptySet()) ?: emptySet()
        set(v) = prefs.edit().putStringSet("approved_hosts", v).apply()
    var disabledTools: Set<String>
        get() = prefs.getStringSet("disabled_tools", emptySet()) ?: emptySet()
        set(v) = prefs.edit().putStringSet("disabled_tools", v).apply()
    var toolProfile: String
        get() = prefs.getString("tool_profile", "full") ?: "full"
        set(v) = prefs.edit().putString("tool_profile", v).apply()
    var lanWatchEnabled: Boolean
        get() = prefs.getBoolean("lan_watch", false)
        set(v) = prefs.edit().putBoolean("lan_watch", v).apply()
    var selectedLocalModel: String
        get() = prefs.getString("local_model_id", "") ?: ""
        set(v) = prefs.edit().putString("local_model_id", v).apply()
    var voskEnabled: Boolean
        get() = prefs.getBoolean("vosk_enabled", false)
        set(v) = prefs.edit().putBoolean("vosk_enabled", v).apply()
    var voskModelId: String
        get() = prefs.getString("vosk_model_id", "small-uk") ?: "small-uk"
        set(v) = prefs.edit().putString("vosk_model_id", v).apply()
    /** When true, never fall back to the (possibly online) system recognizer. */
    var voskStrict: Boolean
        get() = prefs.getBoolean("vosk_strict", false)
        set(v) = prefs.edit().putBoolean("vosk_strict", v).apply()
    /** Saved terminal snippets as a JSON array of {name,cmd}. */
    var snippetsJson: String
        get() = prefs.getString("snippets_json", "[]") ?: "[]"
        set(v) = prefs.edit().putString("snippets_json", v).apply()

    /** Prefill the embedded build-time key once, only if the user hasn't set/cleared one. */
    fun seedEmbeddedKeyIfNeeded() {
        if (prefs.getBoolean("key_seeded", false)) return
        val embedded = BuildConfig.EMBEDDED_API_KEY
        if (apiKey.isBlank() && embedded.isNotBlank()) apiKey = embedded
        prefs.edit().putBoolean("key_seeded", true).apply()
    }
    /** User action: clear the stored key (and remember not to re-seed). */
    fun clearApiKey() { prefs.edit().remove("api_key").putBoolean("key_seeded", true).apply() }

    /** Refuse to send the key over plaintext HTTP. */
    fun baseUrlIsSafe() = Security.checkHost(baseUrl).allowed

    companion object {
        const val DEFAULT_BASE = "https://openrouter.ai/api/v1"
        const val DEFAULT_MODEL = "openai/gpt-4o-mini"
        /** Build an EncryptedSharedPreferences with the given name (reused by LAN watch / model store). */
        fun encrypted(ctx: Context, name: String): android.content.SharedPreferences =
            EncryptedSharedPreferences.create(ctx, name,
                MasterKey.Builder(ctx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
    }
}
