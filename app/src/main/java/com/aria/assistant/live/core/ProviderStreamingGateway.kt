package com.aria.assistant.live.core

import android.content.Context
import com.aria.assistant.LettaApiService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ProviderStreamingGateway(
    context: Context
) : StreamLlmGateway {

    private val apiService = LettaApiService(context.applicationContext)

    override suspend fun streamAssistantReply(
        userMessage: String,
        shortResponse: Boolean,
        onRawChunk: (String) -> Unit
    ): String = withContext(Dispatchers.IO) {
        val response = apiService.streamMessage(
            message = userMessage,
            liveShortResponse = shortResponse,
            onChunk = onRawChunk
        )
        response.text
    }
}
