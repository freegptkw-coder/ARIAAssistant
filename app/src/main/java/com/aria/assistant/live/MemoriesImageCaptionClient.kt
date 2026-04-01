package com.aria.assistant.live

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class MemoriesImageCaptionClient(
    private val apiKey: String
) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .build()

    fun describeFrameBase64(imageBase64: String, userPrompt: String): String? {
        if (apiKey.isBlank() || imageBase64.isBlank()) return null

        val payload = JSONObject()
            .put("user_prompt", userPrompt)
            .put("image_base64", imageBase64)
            .put("img_type", "image/png")
            .put("thinking", false)
            .put("qa", true)
            .toString()

        val request = Request.Builder()
            .url("https://security.memories.ai/v1/understand/uploadImgFileBase64")
            .addHeader("Authorization", apiKey)
            .addHeader("Content-Type", "application/json")
            .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        return runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val body = response.body?.string().orEmpty()
                val obj = JSONObject(body)
                val code = obj.opt("code")?.toString().orEmpty()
                if (code.isNotBlank() && code != "0" && code != "0000") {
                    return@use null
                }
                val data = obj.optJSONObject("data") ?: return@use null
                val text = data.optString("text")
                text.takeIf { it.isNotBlank() }
            }
        }.getOrNull()
    }
}
