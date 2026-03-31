package com.aria.assistant.automation

import com.google.gson.annotations.SerializedName

data class SafeIntentEnvelope(
    @SerializedName("action") val action: String,
    @SerializedName("target_apps") val targetApps: List<String>? = null,
    @SerializedName("tasks") val tasks: List<SafeTask>? = null,
    @SerializedName("source") val source: String? = null
)

data class SafeTask(
    @SerializedName("type") val type: String,
    @SerializedName("target_apps") val targetApps: List<String>? = null,
    @SerializedName("app") val app: String? = null,
    @SerializedName("contact") val contact: String? = null,
    @SerializedName("message") val message: String? = null,
    @SerializedName("platform") val platform: String? = null,
    @SerializedName("content") val content: String? = null,
    @SerializedName("enabled") val enabled: Boolean? = null,
    @SerializedName("require_confirmation") val requireConfirmation: Boolean? = null,
    @SerializedName("risk_level") val riskLevel: String? = null
)

data class ParsedAutomationCommand(
    val envelope: SafeIntentEnvelope,
    val acknowledgement: String,
    val fromVoiceHeuristic: Boolean = false
)

data class AutomationExecutionResult(
    val summary: String,
    val executed: Int,
    val blocked: Int,
    val details: List<String>
)

object SafeIntentActions {
    const val LAUNCH_MULTIPLE_APPS = "launch_multiple_apps"
    const val AUTOMATION_REQUEST = "automation_request"
}

object SafeTaskTypes {
    const val LAUNCH_MULTIPLE_APPS = "launch_multiple_apps"
    const val READ_INCOMING_SMS = "read_incoming_sms"
    const val SEND_SMS = "send_sms"
    const val SOCIAL_POST = "social_post"
    const val CONTACT_EDIT = "contact_edit"
}
