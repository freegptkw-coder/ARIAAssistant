package com.aria.assistant

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

class LettaApiService {
    
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    
    private val gson = Gson()
    private val prefs = context.getSharedPreferences("ARIA_PREFS", Context.MODE_PRIVATE)
    
    private val baseUrl: String
        get() = prefs.getString("api_endpoint", "https://api.letta.com") ?: "https://api.letta.com"
    
    private val agentId: String
        get() = prefs.getString("agent_id", "agent-82cf249d-d11f-47bb-a3d3-92458a13c4ea") ?: "agent-82cf249d-d11f-47bb-a3d3-92458a13c4ea"
    
    private val apiKey: String?
        get() = prefs.getString("api_key", null)
    
    fun sendMessage(message: String): LettaResponse {
        val json = """
            {
                "message": "$message"
            }
        """.trimIndent()
        
        val requestBody = json.toRequestBody("application/json".toMediaType())
        
        val request = Request.Builder()
            .url("$baseUrl/v1/agents/$agentId/messages")
            .post(requestBody)
            .addHeader("Content-Type", "application/json")
            .build()
        
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
            
            // Extract assistant's text response
            val messages = jsonObject.getAsJsonArray("messages")
            var assistantText = ""
            
            for (msg in messages) {
                val msgObj = msg.asJsonObject
                if (msgObj.has("message_type") && 
                    msgObj.get("message_type").asString == "assistant_message") {
                    assistantText = msgObj.get("message").asString
                    break
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
