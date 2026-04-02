package com.aria.assistant.live.core

import android.content.Context
import com.aria.assistant.VoiceCommandParser
import com.aria.assistant.automation.AutomationExecutionResult
import com.aria.assistant.automation.SafeIntentEnvelope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong

class LiveDialogOrchestrator(
    private val context: Context,
    private val scope: CoroutineScope,
    private val llmGateway: StreamLlmGateway,
    private val listener: Listener
) {

    interface Listener {
        fun onLlmRequestStarted(turnId: Long, text: String)
        fun onLlmChunk(turnId: Long, chunk: String, source: String)
        fun onLlmCompleted(turnId: Long, fullText: String, source: String)
        fun onLlmFailed(turnId: Long, reason: String)

        fun onUiAcknowledgement(text: String)
        fun onConfirmationRequired(prompt: String)
        fun onAutomationStarted(action: String)
        fun onAutomationFinished(result: AutomationExecutionResult)
    }

    private val turnCounter = AtomicLong(0L)

    @Volatile
    private var activeTurnId: Long = 0L

    @Volatile
    private var wsPendingTurnId: Long = 0L

    private var providerJob: Job? = null
    private var wsTimeoutJob: Job? = null
    private var pendingConfirmation: SafeIntentEnvelope? = null

    suspend fun cancelAll() {
        wsPendingTurnId = 0L
        providerJob?.cancelAndJoin()
        providerJob = null
        wsTimeoutJob?.cancelAndJoin()
        wsTimeoutJob = null
    }

    fun onUserInterrupted() {
        wsPendingTurnId = 0L
        providerJob?.cancel()
        wsTimeoutJob?.cancel()
    }

    fun onWsAssistantActivity() {
        wsPendingTurnId = 0L
        wsTimeoutJob?.cancel()
    }

    fun handleFinalTranscript(
        transcript: String,
        voiceMode: String,
        wsPreferred: Boolean,
        wsSender: (turnId: Long, text: String) -> Boolean
    ) {
        val clean = transcript.trim()
        if (clean.isBlank()) return

        val turnId = turnCounter.incrementAndGet()
        activeTurnId = turnId

        providerJob?.cancel()
        wsTimeoutJob?.cancel()
        wsPendingTurnId = 0L

        if (handlePendingConfirmation(clean)) {
            return
        }

        val parsed = VoiceCommandParser.parseAutomation(clean)
        if (parsed != null && parsed.acknowledgement.isNotBlank()) {
            listener.onUiAcknowledgement(parsed.acknowledgement)
            if (VoiceCommandParser.requiresConfirmation(parsed.envelope)) {
                pendingConfirmation = parsed.envelope
                listener.onConfirmationRequired("Sensitive action detect hoyeche. Bolun confirm ba cancel.")
                return
            }

            executeAutomation(parsed.envelope)
            return
        }

        listener.onLlmRequestStarted(turnId, clean)

        val shouldUseShortResponse = voiceMode != "hands_free"
        if (wsPreferred && wsSender(turnId, clean)) {
            wsPendingTurnId = turnId
            wsTimeoutJob = scope.launch {
                delay(2400L)
                if (wsPendingTurnId == turnId && activeTurnId == turnId) {
                    wsPendingTurnId = 0L
                    startProviderFallback(turnId, clean, shouldUseShortResponse, "ws_timeout")
                }
            }
            return
        }

        startProviderFallback(turnId, clean, shouldUseShortResponse, "ws_unavailable")
    }

    private fun startProviderFallback(
        turnId: Long,
        text: String,
        shortResponse: Boolean,
        reason: String
    ) {
        providerJob?.cancel()
        providerJob = scope.launch(Dispatchers.IO) {
            val coordinator = ResponseStreamCoordinator()
            try {
                listener.onLlmChunk(turnId, "", "provider_fallback:$reason")
                val finalText = llmGateway.streamAssistantReply(
                    userMessage = text,
                    shortResponse = shortResponse
                ) { rawChunk ->
                    if (!isActive || activeTurnId != turnId) return@streamAssistantReply
                    val chunks = coordinator.push(rawChunk)
                    chunks.forEach { listener.onLlmChunk(turnId, it, "provider") }
                }

                if (!isActive || activeTurnId != turnId) return@launch

                coordinator.flush()?.let { remaining ->
                    listener.onLlmChunk(turnId, remaining, "provider")
                }

                val safeFinal = finalText.trim()
                if (safeFinal.isNotBlank()) {
                    listener.onLlmCompleted(turnId, safeFinal, "provider")
                } else {
                    listener.onLlmFailed(turnId, "provider_empty_response")
                }
            } catch (cancelled: Exception) {
                if (!isActive) return@launch
                if (activeTurnId != turnId) return@launch
                listener.onLlmFailed(turnId, cancelled.message ?: "provider_stream_failed")
            }
        }
    }

    private fun handlePendingConfirmation(input: String): Boolean {
        val pending = pendingConfirmation ?: return false
        val low = input.lowercase()

        val confirmWords = listOf("confirm", "yes", "ok", "ha", "proceed", "dao")
        val cancelWords = listOf("cancel", "no", "stop", "na", "bad dao")

        if (confirmWords.any { low == it || low.startsWith("$it ") }) {
            pendingConfirmation = null
            listener.onUiAcknowledgement("Confirm peyechi. Safe action execute kortesi.")
            executeAutomation(pending)
            return true
        }

        if (cancelWords.any { low == it || low.startsWith("$it ") }) {
            pendingConfirmation = null
            listener.onUiAcknowledgement("Thik ache, sensitive request cancel kore dilam.")
            return true
        }

        listener.onConfirmationRequired("Ager sensitive request pending. Bolun confirm ba cancel.")
        return true
    }

    private fun executeAutomation(envelope: SafeIntentEnvelope) {
        scope.launch(Dispatchers.IO) {
            listener.onAutomationStarted(envelope.action)
            val result = withContext(Dispatchers.IO) {
                VoiceCommandParser.executeAutomation(context, envelope)
            }
            listener.onAutomationFinished(result)
        }
    }
}
