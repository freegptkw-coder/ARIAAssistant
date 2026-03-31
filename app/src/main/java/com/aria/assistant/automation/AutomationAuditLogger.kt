package com.aria.assistant.automation

import android.content.Context

object AutomationAuditLogger {
    private const val PREF = "ARIA_PREFS"
    private const val KEY = "automation_audit_log"
    private const val MAX_LINES = 300

    fun log(context: Context, message: String) {
        val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val old = prefs.getString(KEY, "").orEmpty()
        val merged = (old.lines().filter { it.isNotBlank() } + "$now | $message")
            .takeLast(MAX_LINES)
            .joinToString("\n")
        prefs.edit().putString(KEY, merged).apply()
    }

    fun read(context: Context): String {
        return context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getString(KEY, "No automation audit yet.")
            .orEmpty()
            .ifBlank { "No automation audit yet." }
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().remove(KEY).apply()
    }
}
