package com.aria.assistant.live.core

sealed class VoiceSessionEvent {
    object SessionStarted : VoiceSessionEvent()
    object SessionStopped : VoiceSessionEvent()

    object UserSpeechDetected : VoiceSessionEvent()
    object UserSpeechEnded : VoiceSessionEvent()
    data class SttPartial(val text: String) : VoiceSessionEvent()
    data class SttFinal(val text: String) : VoiceSessionEvent()

    object LlmRequestStarted : VoiceSessionEvent()
    data class LlmResponseChunk(val text: String) : VoiceSessionEvent()

    data class AssistantAudioStarted(val source: String) : VoiceSessionEvent()
    data class AssistantAudioChunk(val source: String) : VoiceSessionEvent()
    object AssistantAudioFinished : VoiceSessionEvent()

    data class UserInterruptedAssistant(val reason: String = "barge_in") : VoiceSessionEvent()

    data class ActionExecutionStarted(val action: String) : VoiceSessionEvent()
    data class ActionExecutionFinished(val action: String, val success: Boolean) : VoiceSessionEvent()

    data class ConfirmationRequested(val reason: String) : VoiceSessionEvent()
    data class ConfirmationResolved(val confirmed: Boolean) : VoiceSessionEvent()

    data class BackendFailure(val reason: String) : VoiceSessionEvent()
    data class RecoverableWarning(val reason: String) : VoiceSessionEvent()
    object RecoveryCompleted : VoiceSessionEvent()

    fun compactName(): String {
        return when (this) {
            SessionStarted -> "session_started"
            SessionStopped -> "session_stopped"
            UserSpeechDetected -> "user_speech_detected"
            UserSpeechEnded -> "user_speech_ended"
            is SttPartial -> "stt_partial"
            is SttFinal -> "stt_final"
            LlmRequestStarted -> "llm_request_started"
            is LlmResponseChunk -> "llm_response_chunk"
            is AssistantAudioStarted -> "assistant_audio_started"
            is AssistantAudioChunk -> "assistant_audio_chunk"
            AssistantAudioFinished -> "assistant_audio_finished"
            is UserInterruptedAssistant -> "user_interrupted_assistant"
            is ActionExecutionStarted -> "action_execution_started"
            is ActionExecutionFinished -> "action_execution_finished"
            is ConfirmationRequested -> "confirmation_requested"
            is ConfirmationResolved -> "confirmation_resolved"
            is BackendFailure -> "backend_failure"
            is RecoverableWarning -> "recoverable_warning"
            RecoveryCompleted -> "recovery_completed"
        }
    }
}