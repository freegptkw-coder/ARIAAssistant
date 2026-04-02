package com.aria.assistant.live

import android.content.Context
import com.aria.assistant.SecurePrefs

object ConsentStore {
    private const val PREF = "ARIA_PREFS"

    private const val KEY_LIVE_ENABLED = "live_mode_enabled"
    private const val KEY_LIVE_VISION = "live_vision_enabled"
    private const val KEY_LIVE_ALWAYS_ON = "live_always_on"
    private const val KEY_LIVE_AVATAR_ENABLED = "live_avatar_enabled"
    private const val KEY_LIVE_VISION_INTERVAL_MS = "live_vision_interval_ms"
    private const val KEY_LIVE_BACKEND_MODE = "live_backend_mode"
    private const val KEY_LIVE_VOICE_MODE = "live_voice_mode"

    private const val KEY_LIVE_WS_URL = "live_ws_url"
    private const val KEY_LIVE_WS_TOKEN = "live_ws_token"
    private const val KEY_LIVE_WS_CERT_PIN = "live_ws_cert_pin"

    private const val KEY_MEMORIES_ENABLED = "memories_enabled"
    private const val KEY_MEMORIES_API_KEY_ENC = "memories_api_key_enc"
    private const val KEY_MEMORIES_API_KEY = "memories_api_key"
    private const val KEY_MEMORIES_PROMPT = "memories_prompt"

    private const val KEY_SESSION_STARTED_AT = "live_session_started_at"
    private const val KEY_SESSION_ACTIVE_UNTIL = "live_session_active_until"
    private const val KEY_SESSION_DEFAULT_MIN = "live_session_default_minutes"

    fun isLiveEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_LIVE_ENABLED, false)
    }

    fun setLiveEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_LIVE_ENABLED, enabled).apply()
    }

    fun isAlwaysOn(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_LIVE_ALWAYS_ON, false)
    }

    fun setAlwaysOn(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_LIVE_ALWAYS_ON, enabled).apply()
    }

    fun isVisionEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_LIVE_VISION, false)
    }

    fun setVisionEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_LIVE_VISION, enabled).apply()
    }

    fun isAvatarEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_LIVE_AVATAR_ENABLED, true)
    }

    fun setAvatarEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_LIVE_AVATAR_ENABLED, enabled).apply()
    }

    fun getVisionIntervalMs(context: Context): Long {
        return prefs(context)
            .getLong(KEY_LIVE_VISION_INTERVAL_MS, 2500L)
            .coerceIn(1200L, 15000L)
    }

    fun setVisionIntervalMs(context: Context, value: Long) {
        prefs(context).edit()
            .putLong(KEY_LIVE_VISION_INTERVAL_MS, value.coerceIn(1200L, 15000L))
            .apply()
    }

    fun getLiveBackendMode(context: Context): String {
        val mode = prefs(context).getString(KEY_LIVE_BACKEND_MODE, "auto").orEmpty().lowercase()
        return when (mode) {
            "auto", "ws", "memories", "hybrid" -> mode
            else -> "auto"
        }
    }

    fun setLiveBackendMode(context: Context, mode: String) {
        val safeMode = when (mode.lowercase()) {
            "auto", "ws", "memories", "hybrid" -> mode.lowercase()
            else -> "auto"
        }
        prefs(context).edit().putString(KEY_LIVE_BACKEND_MODE, safeMode).apply()
    }

    fun getLiveVoiceMode(context: Context): String {
        val mode = prefs(context).getString(KEY_LIVE_VOICE_MODE, "hands_free").orEmpty().lowercase()
        return when (mode) {
            "hands_free", "push_to_talk", "command" -> mode
            else -> "hands_free"
        }
    }

    fun setLiveVoiceMode(context: Context, mode: String) {
        val safeMode = when (mode.lowercase()) {
            "hands_free", "push_to_talk", "command" -> mode.lowercase()
            else -> "hands_free"
        }
        prefs(context).edit().putString(KEY_LIVE_VOICE_MODE, safeMode).apply()
    }

    fun getWsUrl(context: Context): String {
        return prefs(context).getString(KEY_LIVE_WS_URL, "").orEmpty()
    }

    fun setWsUrl(context: Context, value: String) {
        prefs(context).edit().putString(KEY_LIVE_WS_URL, value.trim()).apply()
    }

    fun getWsToken(context: Context): String {
        return prefs(context).getString(KEY_LIVE_WS_TOKEN, "").orEmpty()
    }

    fun setWsToken(context: Context, value: String) {
        prefs(context).edit().putString(KEY_LIVE_WS_TOKEN, value.trim()).apply()
    }

    fun getWsCertPin(context: Context): String {
        return prefs(context).getString(KEY_LIVE_WS_CERT_PIN, "").orEmpty()
    }

    fun setWsCertPin(context: Context, value: String) {
        prefs(context).edit().putString(KEY_LIVE_WS_CERT_PIN, value.trim()).apply()
    }

    fun setWsConfig(context: Context, wsUrl: String, wsToken: String, certPin: String = "") {
        prefs(context).edit()
            .putString(KEY_LIVE_WS_URL, wsUrl.trim())
            .putString(KEY_LIVE_WS_TOKEN, wsToken.trim())
            .putString(KEY_LIVE_WS_CERT_PIN, certPin.trim())
            .apply()
    }

    fun isMemoriesEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_MEMORIES_ENABLED, false)
    }

    fun setMemoriesEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_MEMORIES_ENABLED, enabled).apply()
    }

    fun getMemoriesApiKey(context: Context): String {
        return SecurePrefs.getDecryptedString(
            context,
            PREF,
            KEY_MEMORIES_API_KEY_ENC,
            KEY_MEMORIES_API_KEY
        )
    }

    fun setMemoriesApiKey(context: Context, apiKey: String) {
        prefs(context).edit()
            .putString(KEY_MEMORIES_API_KEY_ENC, SecurePrefs.encrypt(apiKey.trim()))
            .putString(KEY_MEMORIES_API_KEY, "")
            .apply()
    }

    fun getMemoriesPrompt(context: Context): String {
        return prefs(context)
            .getString(KEY_MEMORIES_PROMPT, "Describe the current screen in short helpful Bangla-English.")
            .orEmpty()
    }

    fun setMemoriesPrompt(context: Context, prompt: String) {
        prefs(context).edit().putString(KEY_MEMORIES_PROMPT, prompt.trim()).apply()
    }

    fun getDefaultSessionMinutes(context: Context): Int {
        return prefs(context).getInt(KEY_SESSION_DEFAULT_MIN, 15).coerceIn(1, 240)
    }

    fun startSession(context: Context, durationMinutes: Int) {
        val now = System.currentTimeMillis()
        val safeMinutes = durationMinutes.coerceIn(1, 240)
        val until = now + safeMinutes * 60_000L
        prefs(context).edit()
            .putLong(KEY_SESSION_STARTED_AT, now)
            .putLong(KEY_SESSION_ACTIVE_UNTIL, until)
            .putInt(KEY_SESSION_DEFAULT_MIN, safeMinutes)
            .apply()
    }

    fun endSession(context: Context) {
        prefs(context).edit()
            .putLong(KEY_SESSION_STARTED_AT, 0L)
            .putLong(KEY_SESSION_ACTIVE_UNTIL, 0L)
            .apply()
    }

    fun isSessionActive(context: Context): Boolean {
        val until = prefs(context).getLong(KEY_SESSION_ACTIVE_UNTIL, 0L)
        return until > System.currentTimeMillis()
    }

    fun getRemainingSessionMs(context: Context): Long {
        val until = prefs(context).getLong(KEY_SESSION_ACTIVE_UNTIL, 0L)
        return (until - System.currentTimeMillis()).coerceAtLeast(0L)
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
}
