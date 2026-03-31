package com.aria.assistant

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonParser
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject

data class LettaResponse(
    val text: String,
    val rootCommand: String? = null
)

class LettaApiService(private val context: Context) {
    
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
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
    
    private fun getBaseUrl(): String {
        return when(getProvider()) {
            "groq" -> "https://api.groq.com/openai/v1"
            "openrouter" -> "https://openrouter.ai/api/v1"
            "gemini" -> "https://generativelanguage.googleapis.com/v1beta"
            else -> prefs.getString("api_endpoint", "https://api.letta.com") ?: "https://api.letta.com"
        }
    }
    
    fun sendMessage(message: String): LettaResponse {
        val provider = getProvider()
        val model = getModel()
        val apiKey = getApiKey()
        val baseUrl = getBaseUrl()

        // Get personality and user info
        val personality = prefs.getString("personality", "girlfriend") ?: "girlfriend"
        val userName = prefs.getString("user_name", "") ?: ""
        val nickname = prefs.getString("nickname", "") ?: ""

        // Bangla preference
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

        // Memory context (last 40 turns)
        val historyMessages = ConversationMemory.getMessages(context)

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
                    put("contents", JSONArray().put(
                        JSONObject().put("parts", JSONArray().put(JSONObject().put("text", finalPrompt)))
                    ))
                }
            }
            "letta" -> JSONObject().apply {
                put("message", message)
            }
            else -> {
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

                JSONObject().apply {
                    put("model", model)
                    put("messages", messages)
                    put("stream", false)
                }
            }
        }

        val requestBody = payload.toString().toRequestBody("application/json".toMediaType())

        val requestBuilder = Request.Builder()
            .url(url)
            .post(requestBody)
            .addHeader("Content-Type", "application/json")

        if (provider != "gemini") {
            apiKey?.let { requestBuilder.addHeader("Authorization", "Bearer $it") }
        }

        // Save user message to memory before request completes
        ConversationMemory.addMessage(context, "user", message)

        val response = client.newCall(requestBuilder.build()).execute()

        if (!response.isSuccessful) {
            throw Exception("API error ${response.code}: ${response.message}")
        }

        val responseBody = response.body?.string() ?: throw Exception("Empty response")
        val parsedResponse = parseResponse(responseBody, provider)

        // Save assistant response to memory
        ConversationMemory.addMessage(context, "assistant", parsedResponse.text)

        return parsedResponse
    }

    private fun parseResponse(jsonResponse: String, provider: String): LettaResponse {
        try {
            val jsonObject = JsonParser.parseString(jsonResponse).asJsonObject
            
            val assistantText = when(provider) {
                "gemini" -> {
                    jsonObject.getAsJsonArray("candidates")
                        ?.get(0)?.asJsonObject
                        ?.getAsJsonObject("content")
                        ?.getAsJsonArray("parts")
                        ?.get(0)?.asJsonObject
                        ?.get("text")?.asString ?: "No response"
                }
                "letta" -> {
                    val messages = jsonObject.getAsJsonArray("messages")
                    var text = ""
                    for (msg in messages) {
                        val msgObj = msg.asJsonObject
                        if (msgObj.has("message_type") && 
                            msgObj.get("message_type").asString == "assistant_message") {
                            text = msgObj.get("message").asString
                            break
                        }
                    }
                    text
                }
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
}
