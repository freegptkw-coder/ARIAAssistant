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
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()
    
    private val gson = Gson()
    private val prefs = context.getSharedPreferences("ARIA_PREFS", Context.MODE_PRIVATE)
    
    private val provider: String
        get() = prefs.getString("ai_provider", "groq") ?: "groq"
    
    private val baseUrl: String
        get() = when(provider) {
            "openrouter" -> "https://openrouter.ai/api/v1"
            "groq" -> "https://api.groq.com/openai/v1"
            "gemini" -> "https://generativelanguage.googleapis.com/v1beta"
            else -> prefs.getString("api_endpoint", "https://api.letta.com") ?: "https://api.letta.com"
        }
    
    private val model: String
        get() = prefs.getString("model", "llama-3.3-70b-versatile") ?: "llama-3.3-70b-versatile"
    
    private val agentId: String
        get() = prefs.getString("agent_id", "agent-82cf249d-d11f-47bb-a3d3-92458a13c4ea") ?: "agent-82cf249d-d11f-47bb-a3d3-92458a13c4ea"
    
    private val apiKey: String?
        get() = prefs.getString("api_key", null)
    
    fun sendMessage(message: String): LettaResponse {
        val json = when(provider) {
            "letta" -> """{"message": "$message"}"""
            "gemini" -> """{"contents":[{"parts":[{"text":"$message"}]}]}"""
            else -> """{"model": "$model", "messages": [{"role": "user", "content": "$message"}], "stream": false}"""
        }
        
        val requestBody = json.toRequestBody("application/json".toMediaType())
        
        val url = when(provider) {
            "letta" -> "$baseUrl/v1/agents/$agentId/messages"
            "gemini" -> "$baseUrl/models/$model:generateContent?key=$apiKey"
            else -> "$baseUrl/chat/completions"
        }
        
        val requestBuilder = Request.Builder()
            .url(url)
            .post(requestBody)
            .addHeader("Content-Type", "application/json")
        
        // Add API key if provided (not for Gemini - uses query param)
        if (provider != "gemini") {
            apiKey?.let {
                requestBuilder.addHeader("Authorization", "Bearer $it")
            }
        }
        
        val request = requestBuilder.build()
        
        val response = client.newCall(request).execute()
        
        if (!response.isSuccessful) {
            throw Exception("API call failed: ${response.code}")
        }
        
        val responseBody = response.body?.string() ?: throw Exception("Empty response")
        
        // Parse response
        return parseResponse(responseBody)
    }
    
    private fun parseResponse(jsonResponse: String): LettaResponse {
        try {
            val jsonObject = JsonParser.parseString(jsonResponse).asJsonObject
            
            var assistantText = when(provider) {
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
                "gemini" -> {
                    jsonObject.getAsJsonArray("candidates")
                        ?.get(0)?.asJsonObject
                        ?.getAsJsonObject("content")
                        ?.getAsJsonArray("parts")
                        ?.get(0)?.asJsonObject
                        ?.get("text")?.asString ?: "No response"
                }
                else -> {
                    jsonObject.getAsJsonArray("choices")
                        ?.get(0)?.asJsonObject
                        ?.getAsJsonObject("message")
                        ?.get("content")?.asString ?: "No response"
                }
            }
            
            // Look for JSON command block at the end
            var rootCommand: String? = null
            if (assistantText.contains("{") && assistantText.contains("}")) {
                val jsonStart = assistantText.lastIndexOf("{")
                val jsonEnd = assistantText.lastIndexOf("}") + 1
                
                if (jsonStart >= 0 && jsonEnd > jsonStart) {
                    try {
                        val jsonBlock = assistantText.substring(jsonStart, jsonEnd)
                        val commandObj = JsonParser.parseString(jsonBlock).asJsonObject
                        
                        if (commandObj.has("action") && 
                            commandObj.get("action").asString == "root_shell") {
                            rootCommand = commandObj.get("command").asString
                            // Remove JSON block from text
                            assistantText = assistantText.substring(0, jsonStart).trim()
                        }
                    } catch (e: Exception) {
                        // Not a valid JSON command, ignore
                    }
                }
            }
            
            return LettaResponse(assistantText, rootCommand)
        } catch (e: Exception) {
            throw Exception("Failed to parse response: ${e.message}")
        }
    }
}
