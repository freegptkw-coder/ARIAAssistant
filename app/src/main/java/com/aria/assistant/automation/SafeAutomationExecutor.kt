package com.aria.assistant.automation

import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.delay

class SafeAutomationExecutor(private val context: Context) {

    private val appPackageAllowlist = mapOf(
        "whatsapp" to "com.whatsapp",
        "youtube" to "com.google.android.youtube",
        "chrome" to "com.android.chrome",
        "facebook" to "com.facebook.katana",
        "messenger" to "com.facebook.orca",
        "instagram" to "com.instagram.android",
        "telegram" to "org.telegram.messenger",
        "calculator" to "com.google.android.calculator",
        "notepad" to "com.google.android.keep",
        "settings" to "com.android.settings"
    )

    suspend fun execute(envelope: SafeIntentEnvelope): AutomationExecutionResult {
        val tasks = normalizeTasks(envelope)
        if (tasks.isEmpty()) {
            return AutomationExecutionResult(
                summary = "No executable safe task found.",
                executed = 0,
                blocked = 0,
                details = listOf("No task")
            )
        }

        val details = mutableListOf<String>()
        var executed = 0
        var blocked = 0

        for (task in tasks) {
            if (!SafeAutomationPolicy.isAllowed(context, task.type)) {
                blocked++
                val msg = "blocked:${task.type}:policy"
                details += msg
                AutomationAuditLogger.log(context, msg)
                continue
            }

            if (SafeAutomationPolicy.shouldRequireConfirmation(task)) {
                val guard = SensitiveScreenGuard.evaluate(context)
                if (guard.blocked) {
                    blocked++
                    val msg = "blocked:${task.type}:sensitive_screen:${guard.reason}"
                    details += msg
                    AutomationAuditLogger.log(context, "$msg:${guard.evidence}")
                    continue
                }
            }

            when (task.type) {
                SafeTaskTypes.LAUNCH_MULTIPLE_APPS -> {
                    val apps = task.targetApps.orEmpty().ifEmpty { task.app?.let { listOf(it) } ?: emptyList() }
                    val launched = launchApps(apps)
                    executed += launched
                    val fail = apps.size - launched
                    blocked += fail
                    details += "launch_apps:ok=$launched,fail=$fail"
                    AutomationAuditLogger.log(context, "launch_apps:${apps.joinToString(",")}:ok=$launched")
                }

                SafeTaskTypes.READ_INCOMING_SMS -> {
                    val enabled = task.enabled ?: true
                    setIncomingSmsRead(enabled)
                    executed++
                    details += "read_incoming_sms:${if (enabled) "enabled" else "disabled"}"
                    AutomationAuditLogger.log(context, "read_incoming_sms:${enabled}")
                }

                SafeTaskTypes.SEND_SMS -> {
                    composeSms(task.contact.orEmpty(), task.message.orEmpty())
                    executed++
                    details += "send_sms:compose_opened"
                    AutomationAuditLogger.log(context, "send_sms:compose:${task.contact.orEmpty()}")
                }

                SafeTaskTypes.SOCIAL_POST -> {
                    composeSocialPost(task.platform.orEmpty(), task.content.orEmpty())
                    executed++
                    details += "social_post:compose_opened"
                    AutomationAuditLogger.log(context, "social_post:compose:${task.platform.orEmpty()}")
                }

                else -> {
                    blocked++
                    details += "blocked:${task.type}:unsupported"
                    AutomationAuditLogger.log(context, "blocked:${task.type}:unsupported")
                }
            }
        }

        val summary = "Automation done: $executed executed, $blocked blocked."
        return AutomationExecutionResult(summary, executed, blocked, details)
    }

    private fun normalizeTasks(envelope: SafeIntentEnvelope): List<SafeTask> {
        return when (envelope.action) {
            SafeIntentActions.LAUNCH_MULTIPLE_APPS -> {
                listOf(
                    SafeTask(
                        type = SafeTaskTypes.LAUNCH_MULTIPLE_APPS,
                        targetApps = envelope.targetApps,
                        requireConfirmation = false,
                        riskLevel = "low"
                    )
                )
            }
            SafeIntentActions.AUTOMATION_REQUEST -> envelope.tasks.orEmpty()
            else -> emptyList()
        }
    }

    private suspend fun launchApps(apps: List<String>): Int {
        var ok = 0
        apps.map { it.lowercase() }.distinct().forEachIndexed { index, app ->
            val packageName = appPackageAllowlist[app]
            if (packageName != null) {
                val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
                if (launchIntent != null) {
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { context.startActivity(launchIntent) }.onSuccess { ok++ }
                }
            }
            if (index < apps.lastIndex) delay(2000)
        }
        return ok
    }

    private fun setIncomingSmsRead(enabled: Boolean) {
        context.getSharedPreferences("ARIA_PREFS", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("auto_read_incoming_sms", enabled)
            .apply()
    }

    private fun composeSms(contact: String, body: String) {
        val uri = Uri.parse("smsto:")
        val intent = Intent(Intent.ACTION_SENDTO, uri).apply {
            putExtra("address", contact)
            putExtra("sms_body", body)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
    }

    private fun composeSocialPost(platform: String, content: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, content)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        val pkg = when (platform.lowercase()) {
            "facebook", "fb" -> "com.facebook.katana"
            "instagram", "insta" -> "com.instagram.android"
            else -> null
        }
        if (pkg != null) intent.setPackage(pkg)

        val chooser = Intent.createChooser(intent, "Share post").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(chooser) }
    }
}
