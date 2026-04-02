package com.aria.assistant.live.core

enum class VoiceSessionState {
    IDLE,
    LISTENING,
    PARTIAL_TRANSCRIPTION,
    THINKING,
    SPEAKING,
    INTERRUPTED,
    EXECUTING_ACTION,
    AWAITING_CONFIRMATION,
    ERROR_RECOVERY
}

fun VoiceSessionState.isSpeechOutputState(): Boolean {
    return this == VoiceSessionState.SPEAKING
}