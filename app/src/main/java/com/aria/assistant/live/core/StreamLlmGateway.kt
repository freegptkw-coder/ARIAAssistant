package com.aria.assistant.live.core

interface StreamLlmGateway {
    suspend fun streamAssistantReply(
        userMessage: String,
        shortResponse: Boolean = true,
        onRawChunk: (String) -> Unit
    ): String
}
