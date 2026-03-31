package com.aria.assistant

import android.content.Context

object AppHealthMonitor {
    private const val PREFS = "ARIA_HEALTH"
    private const val KEY_LAST_START = "last_start"
    private const val KEY_LAST_HEARTBEAT = "last_heartbeat"
    private const val KEY_LAST_CRASH = "last_crash"
    private const val KEY_CLEAN_EXIT = "clean_exit"

    fun installCrashHandler(context: Context) {
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val crashSummary = "${throwable.javaClass.simpleName}: ${throwable.message ?: "unknown"}"
            prefs.edit()
                .putString(KEY_LAST_CRASH, crashSummary)
                .putBoolean(KEY_CLEAN_EXIT, false)
                .apply()
            previousHandler?.uncaughtException(thread, throwable)
        }
    }

    fun markAppStart(context: Context) {
        val now = System.currentTimeMillis()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_LAST_START, now)
            .putLong(KEY_LAST_HEARTBEAT, now)
            .putBoolean(KEY_CLEAN_EXIT, false)
            .apply()
    }

    fun markAlive(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_LAST_HEARTBEAT, System.currentTimeMillis())
            .apply()
    }

    fun markCleanExit(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_CLEAN_EXIT, true)
            .apply()
    }

    fun consumeLastCrashSummary(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val cleanExit = prefs.getBoolean(KEY_CLEAN_EXIT, true)
        val crash = prefs.getString(KEY_LAST_CRASH, null)

        if (!cleanExit && !crash.isNullOrBlank()) {
            prefs.edit().remove(KEY_LAST_CRASH).apply()
            return crash
        }
        return null
    }
}
