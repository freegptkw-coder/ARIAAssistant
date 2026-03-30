package com.aria.assistant

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

enum class TTSProvider {
    ANDROID, ELEVENLABS, CARTESIA
}

data class VoiceOption(
    val id: String,
    val name: String,
    val provider: TTSProvider,
    val description: String = ""
)

object TTSProviders {
    
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    
    // ElevenLabs popular voices
    val elevenLabsVoices = listOf(
        VoiceOption("21m00Tcm4TlvDq8ikWAM", "Rachel", TTSProvider.ELEVENLABS, "American Female - Calm"),
        VoiceOption("AZnzlk1XvdvUeBnXmlld", "Domi", TTSProvider.ELEVENLABS, "American Female - Strong"),
        VoiceOption("EXAVITQu4vr4xnSDxMaL", "Bella", TTSProvider.ELEVENLABS, "American Female - Soft"),
        VoiceOption("ErXwobaYiN019PkySvjV", "Antoni", TTSProvider.ELEVENLABS, "American Male - Young"),
        VoiceOption("MF3mGyEYCl7XYWbV9V6O", "Elli", TTSProvider.ELEVENLABS, "American Female - Emotional"),
        VoiceOption("TxGEqnHWrfWFTfGW9XjX", "Josh", TTSProvider.ELEVENLABS, "American Male - Deep"),
        VoiceOption("VR6AewLTigWG4xSOukaG", "Arnold", TTSProvider.ELEVENLABS, "American Male - Strong"),
        VoiceOption("pNInz6obpgDQGcFmaJgB", "Adam", TTSProvider.ELEVENLABS, "American Male - Clear"),
        VoiceOption("yoZ06aMxZJJ28mfd3POQ", "Sam", TTSProvider.ELEVENLABS, "American Male - Raspy")
    )
    
    // Cartesia voices
    val cartesiaVoices = listOf(
        VoiceOption("a0e99841-438c-4a64-b679-ae501e7d6091", "Barbershop Man", TTSProvider.CARTESIA, "Male - Friendly"),
        VoiceOption("79a125e8-cd45-4c13-8a67-188112f4dd22", "British Lady", TTSProvider.CARTESIA, "Female - British"),
        VoiceOption("95856005-0332-41b0-935f-352e296aa0df", "Classy British Man", TTSProvider.CARTESIA, "Male - British Elegant"),
        VoiceOption("fb26447f-308b-471e-8b00-8e9f04284eb5", "Midwestern Woman", TTSProvider.CARTESIA, "Female - Midwest American"),
        VoiceOption("694f9389-aac1-45b6-b726-9d9369183238", "Newsman", TTSProvider.CARTESIA, "Male - News Anchor"),
        VoiceOption("820a3788-2b37-4d21-847a-b65d8a68c99a", "Reading Lady", TTSProvider.CARTESIA, "Female - Narrator"),
        VoiceOption("638efaaa-4d0c-442e-b701-3fae16aad012", "Calm Lady", TTSProvider.CARTESIA, "Female - Calm & Soothing"),
        VoiceOption("41534e16-2966-4c6b-9670-111411def906", "Kentucky Man", TTSProvider.CARTESIA, "Male - Southern")
    )
    
    val androidVoices = listOf(
        VoiceOption("android_default", "Android Default", TTSProvider.ANDROID, "System TTS")
    )
    
    fun getAllVoices(): List<VoiceOption> {
        return androidVoices + elevenLabsVoices + cartesiaVoices
    }
    
    suspend fun generateSpeech(
        context: Context,
        text: String,
        provider: TTSProvider,
        voiceId: String,
        apiKey: String
    ): File? {
        return withContext(Dispatchers.IO) {
            try {
                when (provider) {
                    TTSProvider.ELEVENLABS -> generateElevenLabs(context, text, voiceId, apiKey)
                    TTSProvider.CARTESIA -> generateCartesia(context, text, voiceId, apiKey)
                    TTSProvider.ANDROID -> null // Handled by Android TTS
                }
            } catch (e: Exception) {
                null
            }
        }
    }
    
    private fun generateElevenLabs(context: Context, text: String, voiceId: String, apiKey: String): File? {
        val json = """
            {
                "text": "${text.replace("\"", "\\\"")}",
                "model_id": "eleven_turbo_v2",
                "voice_settings": {
                    "stability": 0.5,
                    "similarity_boost": 0.75,
                    "style": 0.0,
                    "use_speaker_boost": true
                }
            }
        """.trimIndent()
        
        val requestBody = json.toRequestBody("application/json".toMediaType())
        
        val request = Request.Builder()
            .url("https://api.elevenlabs.io/v1/text-to-speech/$voiceId")
            .post(requestBody)
            .addHeader("Accept", "audio/mpeg")
            .addHeader("Content-Type", "application/json")
            .addHeader("xi-api-key", apiKey)
            .build()
        
        val response = client.newCall(request).execute()
        
        if (response.isSuccessful) {
            val audioBytes = response.body?.bytes() ?: return null
            
            val outputFile = File(context.cacheDir, "tts_elevenlabs_${System.currentTimeMillis()}.mp3")
            FileOutputStream(outputFile).use { it.write(audioBytes) }
            
            return outputFile
        }
        
        return null
    }
    
    private fun generateCartesia(context: Context, text: String, voiceId: String, apiKey: String): File? {
        val json = """
            {
                "model_id": "sonic-english",
                "transcript": "${text.replace("\"", "\\\"")}",
                "voice": {
                    "mode": "id",
                    "id": "$voiceId"
                },
                "output_format": {
                    "container": "mp3",
                    "encoding": "mp3",
                    "sample_rate": 44100
                }
            }
        """.trimIndent()
        
        val requestBody = json.toRequestBody("application/json".toMediaType())
        
        val request = Request.Builder()
            .url("https://api.cartesia.ai/tts/bytes")
            .post(requestBody)
            .addHeader("X-API-Key", apiKey)
            .addHeader("Cartesia-Version", "2024-06-10")
            .addHeader("Content-Type", "application/json")
            .build()
        
        val response = client.newCall(request).execute()
        
        if (response.isSuccessful) {
            val audioBytes = response.body?.bytes() ?: return null
            
            val outputFile = File(context.cacheDir, "tts_cartesia_${System.currentTimeMillis()}.mp3")
            FileOutputStream(outputFile).use { it.write(audioBytes) }
            
            return outputFile
        }
        
        return null
    }
}
