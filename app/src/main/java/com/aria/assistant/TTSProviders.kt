package com.aria.assistant

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
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

data class VoiceValidationResult(
    val success: Boolean,
    val reason: String
)

object TTSProviders {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var lastErrorReason: String = ""

    fun getLastErrorReason(): String = lastErrorReason

    private fun setLastError(reason: String) {
        lastErrorReason = reason
    }

    val elevenLabsVoices = listOf(
        VoiceOption("EXAVITQu4vr4xnSDxMaL", "Bella", TTSProvider.ELEVENLABS, "American Female - Soft ✅ FREE"),
        VoiceOption("pNInz6obpgDQGcFmaJgB", "Adam", TTSProvider.ELEVENLABS, "American Male - Clear ✅ FREE"),
        VoiceOption("ErXwobaYiN019PkySvjV", "Antoni", TTSProvider.ELEVENLABS, "American Male - Young ✅ FREE"),
        VoiceOption("AZnzlk1XvdvUeBnXmlld", "Domi", TTSProvider.ELEVENLABS, "American Female - Strong ✅ FREE"),
        VoiceOption("MF3mGyEYCl7XYWbV9V6O", "Elli", TTSProvider.ELEVENLABS, "American Female - Emotional ✅ FREE")
    )

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

    suspend fun validateVoiceConfig(
        context: Context,
        provider: TTSProvider,
        voiceId: String,
        apiKey: String
    ): VoiceValidationResult {
        return withContext(Dispatchers.IO) {
            if (apiKey.isBlank()) return@withContext VoiceValidationResult(false, "invalid_key")
            if (provider == TTSProvider.ANDROID) return@withContext VoiceValidationResult(true, "android_ok")

            val testText = "Hello from ARIA"
            val file = try {
                generateSpeech(context, testText, provider, voiceId, apiKey)
            } catch (_: Exception) {
                null
            }

            if (file != null && file.exists() && file.length() > 0) {
                file.delete()
                return@withContext VoiceValidationResult(true, "ok")
            }

            val reason = getLastErrorReason().ifBlank { "unknown_error" }
            VoiceValidationResult(false, reason)
        }
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
                setLastError("")
                when (provider) {
                    TTSProvider.ELEVENLABS -> generateElevenLabs(context, text, voiceId, apiKey)
                    TTSProvider.CARTESIA -> generateCartesia(context, text, voiceId, apiKey)
                    TTSProvider.ANDROID -> null
                }
            } catch (e: Exception) {
                setLastError("exception:${e.message ?: "unknown"}")
                null
            }
        }
    }

    private fun generateElevenLabs(context: Context, text: String, voiceId: String, apiKey: String): File? {
        val payload = JSONObject().apply {
            put("text", text)
            put("model_id", "eleven_multilingual_v2")
            put("voice_settings", JSONObject().apply {
                put("stability", 0.5)
                put("similarity_boost", 0.75)
                put("style", 0.0)
                put("use_speaker_boost", true)
            })
        }

        val requestBody = payload.toString().toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url("https://api.elevenlabs.io/v1/text-to-speech/$voiceId")
            .post(requestBody)
            .addHeader("Accept", "audio/mpeg")
            .addHeader("Content-Type", "application/json")
            .addHeader("xi-api-key", apiKey)
            .build()

        client.newCall(request).execute().use { response ->
            if (response.isSuccessful) {
                val audioBytes = response.body?.bytes() ?: run {
                    setLastError("empty_audio")
                    return null
                }

                val outputFile = File(context.cacheDir, "tts_elevenlabs_${System.currentTimeMillis()}.mp3")
                FileOutputStream(outputFile).use { it.write(audioBytes) }
                setLastError("")
                return outputFile
            }

            val body = response.body?.string().orEmpty()
            val reason = mapTtsError("elevenlabs", response.code, body)
            setLastError(reason)
            Log.e("TTSProviders", "ElevenLabs failed code=${response.code}, reason=$reason, body=$body")
            return null
        }
    }

    private fun generateCartesia(context: Context, text: String, voiceId: String, apiKey: String): File? {
        val payload = JSONObject().apply {
            put("model_id", "sonic-english")
            put("transcript", text)
            put("voice", JSONObject().apply {
                put("mode", "id")
                put("id", voiceId)
            })
            put("output_format", JSONObject().apply {
                put("container", "mp3")
                put("sample_rate", 44100)
                put("bit_rate", 128000)
            })
        }

        val requestBody = payload.toString().toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url("https://api.cartesia.ai/tts/bytes")
            .post(requestBody)
            .addHeader("X-API-Key", apiKey)
            .addHeader("Cartesia-Version", "2026-03-01")
            .addHeader("Content-Type", "application/json")
            .build()

        client.newCall(request).execute().use { response ->
            if (response.isSuccessful) {
                val audioBytes = response.body?.bytes() ?: run {
                    setLastError("empty_audio")
                    return null
                }

                val outputFile = File(context.cacheDir, "tts_cartesia_${System.currentTimeMillis()}.mp3")
                FileOutputStream(outputFile).use { it.write(audioBytes) }
                setLastError("")
                return outputFile
            }

            val body = response.body?.string().orEmpty()
            val reason = mapTtsError("cartesia", response.code, body)
            setLastError(reason)
            Log.e("TTSProviders", "Cartesia failed code=${response.code}, reason=$reason, body=$body")
            return null
        }
    }

    private fun mapTtsError(provider: String, code: Int, body: String): String {
        val lower = body.lowercase(Locale.getDefault())
        return when {
            code == 401 || lower.contains("invalid api key") || lower.contains("unauthorized") -> "invalid_key"
            code == 404 || lower.contains("voice_not_found") || lower.contains("voice not found") -> "voice_not_found"
            code == 400 && lower.contains("bit_rate") -> "invalid_output_format"
            code == 400 && lower.contains("model") -> "invalid_model"
            code == 400 && lower.contains("voice") -> "invalid_voice_id"
            code == 429 || lower.contains("quota") || lower.contains("rate limit") -> "quota_exceeded"
            lower.contains("paid_plan_required") -> "paid_plan_required"
            code in 500..599 -> "server_error"
            else -> "${provider}_http_$code"
        }
    }
}
