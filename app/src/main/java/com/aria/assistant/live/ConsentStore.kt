package com.aria.assistant.live

import android.content.Context

object ConsentStore {
    private const val PREF = "ARIA_PREFS"
    private const val KEY_LIVE_ENABLED = "live_mode_enabled"
    private const val KEY_LIVE_VISION = "live_vision_enabled"
    private const val KEY_LIVE_WS_URL = "live_ws_url"
    private const val KEY_LIVE_WS_TOKEN = "live_ws_token"

    fun isLiveEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getBoolean(KEY_LIVE_ENABLED, false)
    }

    fun isVisionEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getBoolean(KEY_LIVE_VISION, false)
    }

    fun getWsUrl(context: Context): String {
        return context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getString(KEY_LIVE_WS_URL, "")
            .orEmpty()
    }

    fun getWsToken(context: Context): String {
        return context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getString(KEY_LIVE_WS_TOKEN, "")
            .orEmpty()
    }
}
