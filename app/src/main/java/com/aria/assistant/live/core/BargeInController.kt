package com.aria.assistant.live.core

data class BargeInDecision(
    val shouldInterrupt: Boolean,
    val reason: String = ""
)

class BargeInController(
    private val enabledProvider: () -> Boolean = { true },
    private val minInterruptIntervalMs: Long = 500L,
    private val assistantOutputHoldMs: Long = 1800L
) {

    @Volatile
    private var assistantOutputActiveUntilMs: Long = 0L

    @Volatile
    private var lastInterruptAtMs: Long = 0L

    fun markAssistantOutputStarted(nowMs: Long = System.currentTimeMillis()) {
        assistantOutputActiveUntilMs = nowMs + assistantOutputHoldMs
    }

    fun markAssistantOutputStopped(nowMs: Long = System.currentTimeMillis()) {
        assistantOutputActiveUntilMs = nowMs
    }

    fun evaluateUserSpeechInterruption(
        currentState: VoiceSessionState,
        nowMs: Long = System.currentTimeMillis()
    ): BargeInDecision {
        if (!enabledProvider()) {
            return BargeInDecision(false, "disabled")
        }

        val assistantLikelyActive = currentState.isSpeechOutputState() || nowMs <= assistantOutputActiveUntilMs
        if (!assistantLikelyActive) {
            return BargeInDecision(false, "assistant_not_speaking")
        }

        if (nowMs - lastInterruptAtMs < minInterruptIntervalMs) {
            return BargeInDecision(false, "cooldown")
        }

        return BargeInDecision(true, "barge_in")
    }

    fun markInterrupted(nowMs: Long = System.currentTimeMillis()) {
        lastInterruptAtMs = nowMs
        assistantOutputActiveUntilMs = nowMs
    }
}