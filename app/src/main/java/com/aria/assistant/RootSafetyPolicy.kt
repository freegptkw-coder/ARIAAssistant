package com.aria.assistant

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class RootPolicyDecision(
    val allowed: Boolean,
    val reason: String
)

object RootSafetyPolicy {

    private const val PREFS = "ARIA_PREFS"
    private const val KEY_ALLOWLIST = "root_allowlist"
    private const val KEY_DENYLIST = "root_denylist"
    private const val KEY_STRICT = "root_strict_mode"
    private const val KEY_AUDIT = "root_audit_log"
    private const val MAX_AUDIT_LINES = 120

    fun getAllowlist(context: Context): String {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_ALLOWLIST, "settings\nsvc\ninput\nam") ?: ""
    }

    fun getDenylist(context: Context): String {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_DENYLIST, "rm -rf /\nmkfs\ndd if=\nreboot\nshutdown\nformat") ?: ""
    }

    fun isStrictMode(context: Context): Boolean {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_STRICT, false)
    }

    fun savePolicy(context: Context, allowlist: String, denylist: String, strict: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ALLOWLIST, allowlist)
            .putString(KEY_DENYLIST, denylist)
            .putBoolean(KEY_STRICT, strict)
            .apply()
    }

    fun evaluate(context: Context, command: String): RootPolicyDecision {
        val lowerCommand = command.lowercase(Locale.getDefault())

        val denyTokens = parseRules(getDenylist(context))
        if (denyTokens.any { lowerCommand.contains(it) }) {
            return RootPolicyDecision(
                allowed = false,
                reason = "Blocked by denylist"
            )
        }

        val allowTokens = parseRules(getAllowlist(context))
        if (isStrictMode(context) && allowTokens.isNotEmpty() && allowTokens.none { lowerCommand.contains(it) }) {
            return RootPolicyDecision(
                allowed = false,
                reason = "Strict mode: command not in allowlist"
            )
        }

        return RootPolicyDecision(allowed = true, reason = "Allowed")
    }

    fun appendAudit(context: Context, command: String, status: String, details: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val current = prefs.getString(KEY_AUDIT, "") ?: ""
        val ts = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        val line = "[$ts] $status | $command | $details"

        val merged = (current.lines().filter { it.isNotBlank() } + line)
            .takeLast(MAX_AUDIT_LINES)
            .joinToString("\n")

        prefs.edit().putString(KEY_AUDIT, merged).apply()
    }

    fun getAuditLog(context: Context): String {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_AUDIT, "No root audit entries yet.")
            .orEmpty()
            .ifBlank { "No root audit entries yet." }
    }

    fun clearAuditLog(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_AUDIT)
            .apply()
    }

    private fun parseRules(text: String): List<String> {
        return text.split("\n", ",", ";")
            .map { it.trim().lowercase(Locale.getDefault()) }
            .filter { it.isNotBlank() }
    }
}
