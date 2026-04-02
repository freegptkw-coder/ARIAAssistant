package com.aria.assistant

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSource
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class LettaResponse(
    val text: String,
    val rootCommand: String? = null
)

private data class RequestContext(
    val selectedProvider: String,
    val selectedModel: String,
    val providerChain: List<String>,
    val systemPrompt: String,
    val historyMessages: List<Message>,
    val message: String
)

class LettaApiService(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(35, TimeUnit.SECONDS)
        .writeTimeout(8, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()
    private val prefs = context.getSharedPreferences("ARIA_PREFS", Context.MODE_PRIVATE)

    private fun getProvider(): String = prefs.getString("ai_provider", "groq") ?: "groq"
    private fun getModel(): String = prefs.getString("model", "llama-3.3-70b-versatile") ?: "llama-3.3-70b-versatile"
    private fun getApiKey(): String? {
        val key = SecurePrefs.getDecryptedString(context, "ARIA_PREFS", "api_key_enc", "api_key")
        return key.ifEmpty { null }
    }

    private fun getBaseUrl(provider: String): String {
        return when (provider) {
            "groq" -> "https://api.groq.com/openai/v1"
            "openrouter" -> "https://openrouter.ai/api/v1"
            "gemini" -> "https://generativelanguage.googleapis.com/v1beta"
            else -> prefs.getString("api_endpoint", "https://api.letta.com") ?: "https://api.letta.com"
        }
    }

    private fun getDefaultModel(provider: String): String {
        return when (provider) {
            "groq" -> "llama-3.3-70b-versatile"
            "openrouter" -> "meta-llama/llama-3.3-70b-instruct"
            "gemini" -> "gemini-1.5-flash"
            else -> "default"
        }
    }

    private fun getApiKey(provider: String): String? {
        val providerSpecific = SecurePrefs.getDecryptedString(
            context,
            "ARIA_PREFS",
            "api_key_${provider}_enc",
            "api_key_${provider}"
        )
        if (providerSpecific.isNotBlank()) return providerSpecific
        return getApiKey()
    }

    fun sendMessage(message: String): LettaResponse {
        val ctx = buildRequestContext(message = message, liveShortResponse = false)
        val errors = mutableListOf<String>()

        ctx.providerChain.forEach { provider ->
            val apiKey = getApiKey(provider)
            if (provider != "letta" && apiKey.isNullOrBlank()) {
                errors += "$provider: missing api key"
                AiReliabilityLogger.log(context, "skip provider=$provider reason=missing_key")
                return@forEach
            }

            val model = if (provider == ctx.selectedProvider) ctx.selectedModel else getDefaultModel(provider)

            try {
                val response = sendMessageWithProvider(
                    provider = provider,
                    model = model,
                    apiKey = apiKey,
                    systemPrompt = ctx.systemPrompt,
                    message = ctx.message,
                    historyMessages = ctx.historyMessages
                )

                if (provider != ctx.selectedProvider) {
                    AiReliabilityLogger.log(context, "fallback success selected=${ctx.selectedProvider} used=$provider")
                }

                ConversationMemory.addMessage(context, "assistant", response.text)
                return response
            } catch (e: Exception) {
                val reason = e.message ?: "unknown_error"
                errors += "$provider: $reason"
                AiReliabilityLogger.log(context, "provider failure provider=$provider reason=$reason")
            }
        }

        throw Exception("All AI providers failed: ${errors.joinToString(" | ")}")
    }

    fun streamMessage(
        message: String,
        liveShortResponse: Boolean = true,
        onChunk: (String) -> Unit
    ): LettaResponse {
        val ctx = buildRequestContext(message = message, liveShortResponse = liveShortResponse)
        val errors = mutableListOf<String>()

        ctx.providerChain.forEach { provider ->
            val apiKey = getApiKey(provider)
            if (provider != "letta" && apiKey.isNullOrBlank()) {
                errors += "$provider: missing api key"
                AiReliabilityLogger.log(context, "stream skip provider=$provider reason=missing_key")
                return@forEach
            }

            val model = if (provider == ctx.selectedProvider) ctx.selectedModel else getDefaultModel(provider)

            try {
                val response = streamMessageWithProvider(
                    provider = provider,
                    model = model,
                    apiKey = apiKey,
                    systemPrompt = ctx.systemPrompt,
                    message = ctx.message,
                    historyMessages = ctx.historyMessages,
                    onChunk = onChunk
                )

                if (provider != ctx.selectedProvider) {
                    AiReliabilityLogger.log(context, "stream fallback success selected=${ctx.selectedProvider} used=$provider")
                }

                ConversationMemory.addMessage(context, "assistant", response.text)
                return response
            } catch (e: Exception) {
                val reason = e.message ?: "unknown_error"
                errors += "$provider: $reason"
                AiReliabilityLogger.log(context, "stream provider failure provider=$provider reason=$reason")
            }
        }

        throw Exception("All AI providers failed (stream): ${errors.joinToString(" | ")}")
    }

    private fun buildRequestContext(message: String, liveShortResponse: Boolean): RequestContext {
        val selectedProvider = getProvider()
        val selectedModel = getModel()

        val personality = prefs.getString("personality", "girlfriend") ?: "girlfriend"
        val userName = prefs.getString("user_name", "") ?: ""
        val nickname = prefs.getString("nickname", "") ?: ""

        val banglaModeEnabled = prefs.getBoolean("bangla_mode", true)
        val hasBanglaText = message.any { it.code in 0x0980..0x09FF }
        val wantsBangla = hasBanglaText || message.lowercase().contains("bangla") || message.contains("বাংলা")

        var systemPrompt = PersonalityPrompts.getSystemPrompt(personality, userName, nickname)
        systemPrompt += """

            Safety automation rules:
            - Never output raw shell/root commands.
            - For device automation requests, respond with a warm Banglalish acknowledgement, then include a JSON object only using safe intents.
            - Preferred JSON formats:
              1) {"action":"launch_multiple_apps","target_apps":["whatsapp","chrome"]}
              2) {"action":"automation_request","tasks":[{"type":"read_incoming_sms","enabled":true,"risk_level":"low","require_confirmation":false}]}
            - Sensitive tasks (send_sms, social_post, contact_edit) must set "require_confirmation": true.
        """.trimIndent()

        if (banglaModeEnabled && wantsBangla) {
            systemPrompt += "\n\nLanguage rule: Reply in natural Bangla/Banglish. Keep it warm, simple, and easy for TTS."
        }

        if (liveShortResponse) {
            systemPrompt += "\n\nLive mode rule: keep replies concise (1-3 short sentences), action-first, interruption-friendly."
        }

        val historyMessages = ConversationMemory.getMessages(context)

        ConversationMemory.addMessage(context, "user", message)

        val fallbackOrderRaw = prefs.getString("ai_fallback_order", "")
            .orEmpty()
            .split(',')
            .map { it.trim().lowercase() }
            .filter { it.isNotBlank() }

        val providerChain = linkedSetOf<String>().apply {
            add(selectedProvider)
            if (fallbackOrderRaw.isNotEmpty()) {
                addAll(fallbackOrderRaw)
            } else {
                addAll(listOf("groq", "gemini", "openrouter", "letta"))
            }
        }.toList()

        return RequestContext(
            selectedProvider = selectedProvider,
            selectedModel = selectedModel,
            providerChain = providerChain,
            systemPrompt = systemPrompt,
            historyMessages = historyMessages,
            message = message
        )
    }

    private fun streamMessageWithProvider(
        provider: String,
        model: String,
        apiKey: String?,
        systemPrompt: String,
        message: String,
        historyMessages: List<Message>,
        onChunk: (String) -> Unit
    ): LettaResponse {
        return when (provider) {
            "gemini" -> streamGemini(provider, model, apiKey, systemPrompt, message, onChunk)
            "letta" -> {
                val fallback = sendMessageWithProvider(provider, model, apiKey, systemPrompt, message, historyMessages)
                emitChunked(fallback.text, onChunk)
                fallback
            }
            else -> streamOpenAiCompatible(provider, model, apiKey, systemPrompt, message, historyMessages, onChunk)
        }
    }

    private fun streamOpenAiCompatible(
        provider: String,
        model: String,
        apiKey: String?,
        systemPrompt: String,
        message: String,
        historyMessages: List<Message>,
        onChunk: (String) -> Unit
    ): LettaResponse {
        val url = "${getBaseUrl(provider)}/chat/completions"

        val payload = buildOpenAiLikePayload(
            model = model,
            systemPrompt = systemPrompt,
            message = message,
            historyMessages = historyMessages,
            stream = true
        )

        val requestBody = payload.toString().toRequestBody("application/json".toMediaType())
        val requestBuilder = Request.Builder()
            .url(url)
            .post(requestBody)
            .addHeader("Content-Type", "application/json")

        if (!apiKey.isNullOrBlank()) {
            requestBuilder.addHeader("Authorization", "Bearer $apiKey")
        }

        if (provider == "openrouter") {
            requestBuilder.addHeader("HTTP-Referer", "https://aria-assistant.local")
            requestBuilder.addHeader("X-Title", "ARIA Assistant")
        }

        val response = client.newCall(requestBuilder.build()).execute()
        if (!response.isSuccessful) {
            throw Exception("stream API error ${response.code}: ${response.message}")
        }

        val source = response.body?.source() ?: throw Exception("Empty stream body")
        val full = StringBuilder()
        var streamedChunks = 0

        consumeSse(source) { payloadLine ->
            if (payloadLine == "[DONE]") return@consumeSse
            val chunk = parseOpenAiStreamChunk(payloadLine)
            if (chunk.isNotBlank()) {
                streamedChunks++
                full.append(chunk)
                onChunk(chunk)
            }
        }

        val finalText = full.toString().trim()
        if (streamedChunks == 0 || finalText.isBlank()) {
            val fallback = sendMessageWithProvider(provider, model, apiKey, systemPrompt, message, historyMessages)
            emitChunked(fallback.text, onChunk)
            return fallback
        }

        return LettaResponse(finalText)
    }

    private fun streamGemini(
        provider: String,
        model: String,
        apiKey: String?,
        systemPrompt: String,
        message: String,
        onChunk: (String) -> Unit
    ): LettaResponse {
        if (apiKey.isNullOrBlank()) throw Exception("gemini: missing api key")

        val baseUrl = getBaseUrl(provider)
        val url = "$baseUrl/models/$model:streamGenerateContent?alt=sse&key=$apiKey"

        val history = ConversationMemory.getConversationHistoryForAPI(context)
        val finalPrompt = "$systemPrompt\n\n$history\nUser: $message"
        val payload = JSONObject().apply {
            put(
                "contents",
                JSONArray().put(
                    JSONObject().put(
                        "parts",
                        JSONArray().put(JSONObject().put("text", finalPrompt))
                    )
                )
            )
        }

        val requestBody = payload.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url(url)
            .post(requestBody)
            .addHeader("Content-Type", "application/json")
            .build()

        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            throw Exception("gemini stream API error ${response.code}: ${response.message}")
        }

        val source = response.body?.source() ?: throw Exception("Empty gemini stream body")
        val full = StringBuilder()
        var streamedChunks = 0

        consumeSse(source) { payloadLine ->
            val chunk = parseGeminiStreamChunk(payloadLine)
            if (chunk.isNotBlank()) {
                streamedChunks++
                full.append(chunk)
                onChunk(chunk)
            }
        }

        val finalText = full.toString().trim()
        if (streamedChunks == 0 || finalText.isBlank()) {
            val fallback = sendMessageWithProvider(provider, model, apiKey, systemPrompt, message, ConversationMemory.getMessages(context))
            emitChunked(fallback.text, onChunk)
            return fallback
        }

        return LettaResponse(finalText)
    }

    private fun sendMessageWithProvider(
        provider: String,
        model: String,
        apiKey: String?,
        systemPrompt: String,
        message: String,
        historyMessages: List<Message>
    ): LettaResponse {
        val baseUrl = getBaseUrl(provider)
        val url = when (provider) {
            "gemini" -> "$baseUrl/models/$model:generateContent?key=$apiKey"
            "letta" -> "$baseUrl/v1/agents/${prefs.getString("agent_id", "")}/messages"
            else -> "$baseUrl/chat/completions"
        }

        val payload = when (provider) {
            "gemini" -> {
                val history = ConversationMemory.getConversationHistoryForAPI(context)
                val finalPrompt = "$systemPrompt\n\n$history\nUser: $message"
                JSONObject().apply {
                    put(
                        "contents",
                        JSONArray().put(
                            JSONObject().put("parts", JSONArray().put(JSONObject().put("text", finalPrompt)))
                        )
                    )
                }
            }
            "letta" -> JSONObject().apply {
                put("message", message)
            }
            else -> buildOpenAiLikePayload(
                model = model,
                systemPrompt = systemPrompt,
                message = message,
                historyMessages = historyMessages,
                stream = false
            )
        }

        val requestBody = payload.toString().toRequestBody("application/json".toMediaType())

        val requestBuilder = Request.Builder()
            .url(url)
            .post(requestBody)
            .addHeader("Content-Type", "application/json")

        if (provider != "gemini") {
            apiKey?.let { requestBuilder.addHeader("Authorization", "Bearer $it") }
        }

        if (provider == "openrouter") {
            requestBuilder.addHeader("HTTP-Referer", "https://aria-assistant.local")
            requestBuilder.addHeader("X-Title", "ARIA Assistant")
        }

        val response = client.newCall(requestBuilder.build()).execute()
        if (!response.isSuccessful) {
            throw Exception("API error ${response.code}: ${response.message}")
        }

        val responseBody = response.body?.string() ?: throw Exception("Empty response")
        return parseResponse(responseBody, provider)
    }

    private fun buildOpenAiLikePayload(
        model: String,
        systemPrompt: String,
        message: String,
        historyMessages: List<Message>,
        stream: Boolean
    ): JSONObject {
        val messages = JSONArray().put(
            JSONObject().put("role", "system").put("content", systemPrompt)
        )

        historyMessages.forEach { msg ->
            if (msg.role == "user" || msg.role == "assistant") {
                messages.put(
                    JSONObject()
                        .put("role", msg.role)
                        .put("content", msg.content)
                )
            }
        }

        messages.put(
            JSONObject()
                .put("role", "user")
                .put("content", message)
        )

        return JSONObject().apply {
            put("model", model)
            put("messages", messages)
            put("stream", stream)
            put("temperature", 0.6)
            put("max_tokens", 280)
        }
    }

    private fun parseOpenAiStreamChunk(payload: String): String {
        return runCatching {
            val obj = JsonParser.parseString(payload).asJsonObject
            val choices = obj.getAsJsonArray("choices") ?: return@runCatching ""
            if (choices.size() <= 0) return@runCatching ""
            val choice = choices[0].asJsonObject

            val delta = choice.getAsJsonObject("delta")
            val deltaText = delta?.get("content")?.asString.orEmpty()
            if (deltaText.isNotBlank()) return@runCatching deltaText

            val messageObj = choice.getAsJsonObject("message")
            messageObj?.get("content")?.asString.orEmpty()
        }.getOrDefault("")
    }

    private fun parseGeminiStreamChunk(payload: String): String {
        return runCatching {
            val obj = JsonParser.parseString(payload).asJsonObject

            // Some responses may be wrapped.
            val candidateRoot = when {
                obj.has("candidates") -> obj
                obj.has("response") && obj.get("response").isJsonObject -> obj.getAsJsonObject("response")
                else -> obj
            }

            val candidates = candidateRoot.getAsJsonArray("candidates") ?: return@runCatching ""
            if (candidates.size() <= 0) return@runCatching ""
            val first = candidates[0].asJsonObject

            val contentObj = first.getAsJsonObject("content") ?: return@runCatching ""
            val parts = contentObj.getAsJsonArray("parts") ?: return@runCatching ""
            if (parts.size() <= 0) return@runCatching ""
            parts[0].asJsonObject.get("text")?.asString.orEmpty()
        }.getOrDefault("")
    }

    private fun consumeSse(source: BufferedSource, onData: (String) -> Unit) {
        while (true) {
            val line = source.readUtf8Line() ?: break
            val trimmed = line.trim()
            if (!trimmed.startsWith("data:")) continue

            val payload = trimmed.removePrefix("data:").trim()
            if (payload.isBlank()) continue
            onData(payload)
        }
    }

    private fun emitChunked(text: String, onChunk: (String) -> Unit) {
        val clean = text.replace("\n", " ").replace(Regex("\\s+"), " ").trim()
        if (clean.isBlank()) return

        val punctuated = clean
            .split(Regex("(?<=[.!?।])\\s+"))
            .map { it.trim() }
            .filter { it.isNotBlank() }

        if (punctuated.isEmpty()) {
            clean.chunked(72).forEach { onChunk(it) }
            return
        }

        punctuated.forEach { sentence ->
            if (sentence.length <= 120) {
                onChunk(sentence)
            } else {
                sentence.chunked(90).forEach { onChunk(it) }
            }
        }
    }

    private fun parseResponse(jsonResponse: String, provider: String): LettaResponse {
        try {
            val jsonObject = JsonParser.parseString(jsonResponse).asJsonObject

            val assistantText = when (provider) {
                "gemini" -> {
                    jsonObject.getAsJsonArray("candidates")
                        ?.get(0)?.asJsonObject
                        ?.getAsJsonObject("content")
                        ?.getAsJsonArray("parts")
                        ?.get(0)?.asJsonObject
                        ?.get("text")?.asString ?: "No response"
                }
                "letta" -> parseLettaAssistantText(jsonObject)
                else -> {
                    jsonObject.getAsJsonArray("choices")
                        ?.get(0)?.asJsonObject
                        ?.getAsJsonObject("message")
                        ?.get("content")?.asString ?: "No response"
                }
            }

            return LettaResponse(assistantText)
        } catch (e: Exception) {
            throw Exception("Parse error: ${e.message}")
        }
    }

    private fun parseLettaAssistantText(jsonObject: JsonObject): String {
        val messages = jsonObject.getAsJsonArray("messages") ?: return ""
        for (msg in messages) {
            val msgObj = msg.asJsonObject
            val type = msgObj.get("message_type")?.asString.orEmpty()
            if (type == "assistant_message") {
                return msgObj.get("message")?.asString.orEmpty()
            }
        }
        return ""
    }
}
