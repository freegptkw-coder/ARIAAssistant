package com.aria.assistant

import android.content.Context
import com.aria.assistant.automation.AutomationExecutionResult
import com.aria.assistant.automation.ParsedAutomationCommand
import com.aria.assistant.automation.SafeAutomationExecutor
import com.aria.assistant.automation.SafeAutomationPolicy
import com.aria.assistant.automation.SafeIntentEnvelope
import com.aria.assistant.automation.SafeIntentParser

object VoiceCommandParser {

    fun parseAutomation(text: String): ParsedAutomationCommand? {
        return SafeIntentParser.parseFromUserVoiceOrText(text)
    }

    fun parseAssistantJson(text: String): ParsedAutomationCommand? {
        return SafeIntentParser.parseFromAssistantText(text)
    }

    suspend fun executeAutomation(context: Context, envelope: SafeIntentEnvelope): AutomationExecutionResult {
        val executor = SafeAutomationExecutor(context)
        return executor.execute(envelope)
    }

    fun requiresConfirmation(envelope: SafeIntentEnvelope): Boolean {
        val tasks = when (envelope.action) {
            "launch_multiple_apps" -> emptyList()
            else -> envelope.tasks.orEmpty()
        }
        return tasks.any { SafeAutomationPolicy.shouldRequireConfirmation(it) }
    }

    fun toJson(envelope: SafeIntentEnvelope): String = SafeIntentParser.toPrettyJson(envelope)
}
