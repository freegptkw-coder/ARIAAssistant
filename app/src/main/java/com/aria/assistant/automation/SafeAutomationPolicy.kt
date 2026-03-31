package com.aria.assistant.automation

import android.content.Context

object SafeAutomationPolicy {
    private const val PREF = "ARIA_PREFS"
    private const val KEY_ALLOWED_ACTIONS = "safe_allowed_actions"
    private const val KEY_DENIED_ACTIONS = "safe_denied_actions"

    private val defaultAllowed = setOf(
        SafeTaskTypes.LAUNCH_MULTIPLE_APPS,
        SafeTaskTypes.READ_INCOMING_SMS,
        SafeTaskTypes.SEND_SMS,
        SafeTaskTypes.SOCIAL_POST
    )

    private val defaultDenied = setOf(
        SafeTaskTypes.CONTACT_EDIT
    )

    fun isAllowed(context: Context, taskType: String): Boolean {
        val denied = getRules(context, KEY_DENIED_ACTIONS, defaultDenied)
        if (taskType in denied) return false

        val allowed = getRules(context, KEY_ALLOWED_ACTIONS, defaultAllowed)
        return taskType in allowed
    }

    fun shouldRequireConfirmation(task: SafeTask): Boolean {
        return when (task.type) {
            SafeTaskTypes.SEND_SMS,
            SafeTaskTypes.SOCIAL_POST,
            SafeTaskTypes.CONTACT_EDIT -> true
            else -> task.requireConfirmation == true
        }
    }

    private fun getRules(context: Context, key: String, defaults: Set<String>): Set<String> {
        val raw = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getString(key, "")
            .orEmpty()

        if (raw.isBlank()) return defaults
        return raw.split("\n", ",", ";")
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toSet()
    }
}
