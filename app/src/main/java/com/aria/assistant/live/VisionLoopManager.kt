package com.aria.assistant.live

import android.util.Base64
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

class VisionLoopManager(
    private val frameProvider: suspend () -> ByteArray?,
    private val frameUploader: suspend (String) -> Unit,
    private val intervalMs: Long = 10_000L
) {

    suspend fun runLoop() {
        while (currentCoroutineContext().isActive) {
            val frame = runCatching { frameProvider() }.getOrNull()
            if (frame != null && frame.isNotEmpty()) {
                val base64 = Base64.encodeToString(frame, Base64.NO_WRAP)
                runCatching { frameUploader(base64) }
            }
            delay(intervalMs)
        }
    }
}
