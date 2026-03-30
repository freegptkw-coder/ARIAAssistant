package com.aria.assistant

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonParser
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

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
    private fun getApiKey(): String? = prefs.getString("api_key", null)
    
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
        
        val json = when(provider) {
            "gemini" -> """{"contents":[{"parts":[{"text":"$message"}]}]}"""
            "letta" -> """{"message": "$message"}"""
            else -> """{"model": "$model", "messages": [{"role": "user", "content": "$message"}], "stream": false}"""
        }
        
        val requestBody = json.toRequestBody("application/json".toMediaType())
        
        val url = when(provider) {
            "gemini" -> "$baseUrl/models/$model:generateContent?key=$apiKey"
            "letta" -> "$baseUrl/v1/agents/${prefs.getString("agent_id", "")}/messages"
            else -> "$baseUrl/chat/completions"
        }
        
        val requestBuilder = Request.Builder()
            .url(url)
            .post(requestBody)
            .addHeader("Content-Type", "application/json")
        
        if (provider != "gemini") {
            apiKey?.let {
                requestBuilder.addHeader("Authorization", "Bearer $it")
            }
        }
        
        val request = requestBuilder.build()
        val response = client.newCall(request).execute()
        
        if (!response.isSuccessful) {
            throw Exception("API error ${response.code}: ${response.message}")
        }
        
        val responseBody = response.body?.string() ?: throw Exception("Empty response")
        return parseResponse(responseBody, provider)
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
