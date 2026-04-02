package com.aria.assistant.live.core

class VoiceTurnStateMachine(
    initialState: VoiceSessionState = VoiceSessionState.IDLE,
    private val onTransition: ((from: VoiceSessionState, to: VoiceSessionState, event: VoiceSessionEvent) -> Unit)? = null
) {

    @Volatile
    var currentState: VoiceSessionState = initialState
        private set

    @Synchronized
    fun onEvent(event: VoiceSessionEvent): VoiceSessionState {
        val from = currentState
        val to = reduce(from, event)
        if (to != from) {
            currentState = to
            onTransition?.invoke(from, to, event)
        }
        return currentState
    }

    private fun reduce(state: VoiceSessionState, event: VoiceSessionEvent): VoiceSessionState {
        if (event is VoiceSessionEvent.SessionStopped) return VoiceSessionState.IDLE

        return when (state) {
            VoiceSessionState.IDLE -> when (event) {
                VoiceSessionEvent.SessionStarted -> VoiceSessionState.LISTENING
                is VoiceSessionEvent.BackendFailure -> VoiceSessionState.ERROR_RECOVERY
                else -> state
            }

            VoiceSessionState.LISTENING -> when (event) {
                VoiceSessionEvent.UserSpeechDetected -> VoiceSessionState.PARTIAL_TRANSCRIPTION
                VoiceSessionEvent.LlmRequestStarted -> VoiceSessionState.THINKING
                is VoiceSessionEvent.ConfirmationRequested -> VoiceSessionState.AWAITING_CONFIRMATION
                is VoiceSessionEvent.ActionExecutionStarted -> VoiceSessionState.EXECUTING_ACTION
                is VoiceSessionEvent.BackendFailure -> VoiceSessionState.ERROR_RECOVERY
                else -> state
            }

            VoiceSessionState.PARTIAL_TRANSCRIPTION -> when (event) {
                VoiceSessionEvent.UserSpeechEnded,
                is VoiceSessionEvent.SttFinal,
                VoiceSessionEvent.LlmRequestStarted -> VoiceSessionState.THINKING

                is VoiceSessionEvent.ConfirmationRequested -> VoiceSessionState.AWAITING_CONFIRMATION
                is VoiceSessionEvent.BackendFailure -> VoiceSessionState.ERROR_RECOVERY
                else -> state
            }

            VoiceSessionState.THINKING -> when (event) {
                is VoiceSessionEvent.LlmResponseChunk,
                is VoiceSessionEvent.AssistantAudioStarted,
                is VoiceSessionEvent.AssistantAudioChunk -> VoiceSessionState.SPEAKING

                is VoiceSessionEvent.ActionExecutionStarted -> VoiceSessionState.EXECUTING_ACTION
                is VoiceSessionEvent.ConfirmationRequested -> VoiceSessionState.AWAITING_CONFIRMATION
                is VoiceSessionEvent.BackendFailure -> VoiceSessionState.ERROR_RECOVERY
                else -> state
            }

            VoiceSessionState.SPEAKING -> when (event) {
                is VoiceSessionEvent.UserInterruptedAssistant -> VoiceSessionState.INTERRUPTED
                VoiceSessionEvent.AssistantAudioFinished -> VoiceSessionState.LISTENING
                is VoiceSessionEvent.ActionExecutionStarted -> VoiceSessionState.EXECUTING_ACTION
                is VoiceSessionEvent.ConfirmationRequested -> VoiceSessionState.AWAITING_CONFIRMATION
                is VoiceSessionEvent.BackendFailure -> VoiceSessionState.ERROR_RECOVERY
                else -> state
            }

            VoiceSessionState.INTERRUPTED -> when (event) {
                VoiceSessionEvent.UserSpeechDetected -> VoiceSessionState.PARTIAL_TRANSCRIPTION
                VoiceSessionEvent.AssistantAudioFinished -> VoiceSessionState.LISTENING
                is VoiceSessionEvent.BackendFailure -> VoiceSessionState.ERROR_RECOVERY
                else -> state
            }

            VoiceSessionState.EXECUTING_ACTION -> when (event) {
                is VoiceSessionEvent.ActionExecutionFinished -> VoiceSessionState.LISTENING
                is VoiceSessionEvent.BackendFailure -> VoiceSessionState.ERROR_RECOVERY
                else -> state
            }

            VoiceSessionState.AWAITING_CONFIRMATION -> when (event) {
                is VoiceSessionEvent.ConfirmationResolved -> {
                    if (event.confirmed) VoiceSessionState.EXECUTING_ACTION else VoiceSessionState.LISTENING
                }

                is VoiceSessionEvent.BackendFailure -> VoiceSessionState.ERROR_RECOVERY
                else -> state
            }

            VoiceSessionState.ERROR_RECOVERY -> when (event) {
                VoiceSessionEvent.RecoveryCompleted,
                VoiceSessionEvent.SessionStarted -> VoiceSessionState.LISTENING

                else -> state
            }
        }
    }
}