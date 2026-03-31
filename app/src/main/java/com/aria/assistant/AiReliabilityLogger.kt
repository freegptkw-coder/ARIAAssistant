package com.aria.assistant

import android.content.Context

object AiReliabilityLogger {
    private const val PREF = "ARIA_PREFS"
    private const val KEY = "ai_reliability_log"
    private const val MAX_LINES = 200

    fun log(context: Context, message: String) {
        val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val existing = prefs.getString(KEY, "").orEmpty()
        val updated = (existing.lines().filter { it.isNotBlank() } + "$now | $message")
            .takeLast(MAX_LINES)
            .joinToString("\n")
        prefs.edit().putString(KEY, updated).apply()
    }

    fun read(context: Context): String {
        return context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getString(KEY, "No AI reliability events yet.")
            .orEmpty()
            .ifBlank { "No AI reliability events yet." }
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY)
            .apply()
    }
}
