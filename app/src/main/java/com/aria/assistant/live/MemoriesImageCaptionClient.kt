package com.aria.assistant.live

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

class MemoriesImageCaptionClient(
    private val apiKey: String
) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(12, TimeUnit.SECONDS)
        .build()

    fun describeFrameBase64(imageBase64: String, userPrompt: String): String? {
        if (apiKey.isBlank() || imageBase64.isBlank()) return null

        val optimizedBase64 = optimizeForRealtime(imageBase64)

        val payload = JSONObject()
            .put("user_prompt", userPrompt)
            .put("image_base64", optimizedBase64)
            .put("img_type", "image/jpeg")
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

    private fun optimizeForRealtime(base64Image: String): String {
        return runCatching {
            val raw = Base64.decode(base64Image, Base64.DEFAULT)

            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)

            val maxDim = maxOf(bounds.outWidth, bounds.outHeight).coerceAtLeast(1)
            var sampleSize = 1
            while (maxDim / sampleSize > 960) {
                sampleSize *= 2
            }

            val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            val bitmap = BitmapFactory.decodeByteArray(raw, 0, raw.size, decodeOpts)
                ?: return@runCatching base64Image

            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 70, out)
            bitmap.recycle()

            Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        }.getOrDefault(base64Image)
    }
}
