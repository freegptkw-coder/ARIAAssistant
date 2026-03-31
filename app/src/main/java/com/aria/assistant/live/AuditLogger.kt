package com.aria.assistant.live

import android.content.Context

object AuditLogger {
    private const val PREF = "ARIA_PREFS"
    private const val KEY_AUDIT = "live_mode_audit"
    private const val MAX_LINES = 300

    fun log(context: Context, event: String) {
        val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val current = prefs.getString(KEY_AUDIT, "").orEmpty()
        val merged = (current.lines().filter { it.isNotBlank() } + "$now | $event")
            .takeLast(MAX_LINES)
            .joinToString("\n")

        prefs.edit().putString(KEY_AUDIT, merged).apply()
    }

    fun read(context: Context): String {
        return context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getString(KEY_AUDIT, "No Live audit entries yet.")
            .orEmpty()
            .ifBlank { "No Live audit entries yet." }
    }
}
