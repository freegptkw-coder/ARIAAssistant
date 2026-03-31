package com.aria.assistant.live

import android.content.Context

object ConsentStore {
    private const val PREF = "ARIA_PREFS"

    private const val KEY_LIVE_ENABLED = "live_mode_enabled"
    private const val KEY_LIVE_VISION = "live_vision_enabled"

    private const val KEY_LIVE_WS_URL = "live_ws_url"
    private const val KEY_LIVE_WS_TOKEN = "live_ws_token"
    private const val KEY_LIVE_WS_CERT_PIN = "live_ws_cert_pin"

    private const val KEY_SESSION_STARTED_AT = "live_session_started_at"
    private const val KEY_SESSION_ACTIVE_UNTIL = "live_session_active_until"
    private const val KEY_SESSION_DEFAULT_MIN = "live_session_default_minutes"

    fun isLiveEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_LIVE_ENABLED, false)
    }

    fun setLiveEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_LIVE_ENABLED, enabled).apply()
    }

    fun isVisionEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_LIVE_VISION, false)
    }

    fun setVisionEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_LIVE_VISION, enabled).apply()
    }

    fun getWsUrl(context: Context): String {
        return prefs(context).getString(KEY_LIVE_WS_URL, "").orEmpty()
    }

    fun getWsToken(context: Context): String {
        return prefs(context).getString(KEY_LIVE_WS_TOKEN, "").orEmpty()
    }

    fun getWsCertPin(context: Context): String {
        return prefs(context).getString(KEY_LIVE_WS_CERT_PIN, "").orEmpty()
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
