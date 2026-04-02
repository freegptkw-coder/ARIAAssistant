package com.aria.assistant.live.core

import android.content.Context
import java.util.Locale

sealed class SttTranscriptEvent {
    object ListeningStarted : SttTranscriptEvent()
    object ListeningStopped : SttTranscriptEvent()

    data class Partial(val text: String) : SttTranscriptEvent()
    data class Final(val text: String) : SttTranscriptEvent()

    data class Error(
        val code: Int,
        val reason: String,
        val recoverable: Boolean
    ) : SttTranscriptEvent()

    object Timeout : SttTranscriptEvent()
    object Unavailable : SttTranscriptEvent()
}

interface StreamingSttGateway {
    fun start()
    fun stop()

    fun onVoiceActivity(active: Boolean) = Unit

    companion object {
        fun createDefault(
            context: Context,
            locale: Locale = Locale.getDefault(),
            onEvent: (SttTranscriptEvent) -> Unit
        ): StreamingSttGateway {
            return AndroidSpeechPartialGateway(
                context = context,
                locale = locale,
                onEvent = onEvent
            )
        }
    }
}
