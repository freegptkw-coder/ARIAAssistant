package com.aria.assistant.live

import android.util.Base64
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

class VisionLoopManager(
    private val frameProvider: suspend () -> ByteArray?,
    private val frameUploader: suspend (String) -> Unit,
    private val intervalMs: Long = 10_000L,
    private val adaptiveMode: Boolean = true,
    private val minIntervalMs: Long = 1200L,
    private val maxIntervalMs: Long = 15_000L
) {

    suspend fun runLoop() {
        var dynamicIntervalMs = intervalMs.coerceIn(minIntervalMs, maxIntervalMs)
        var lastFingerprint = 0L
        var hasLast = false
        var steadyFrames = 0

        while (currentCoroutineContext().isActive) {
            val startedAt = System.currentTimeMillis()
            val frame = runCatching { frameProvider() }.getOrNull()
            if (frame != null && frame.isNotEmpty()) {
                val fingerprint = fingerprint(frame)
                val similar = hasLast && fingerprint == lastFingerprint
                val shouldUpload = !similar || !adaptiveMode || steadyFrames % 4 == 0

                if (shouldUpload) {
                    val base64 = Base64.encodeToString(frame, Base64.NO_WRAP)
                    runCatching { frameUploader(base64) }
                }

                if (adaptiveMode) {
                    dynamicIntervalMs = if (similar) {
                        (dynamicIntervalMs * 1.25f).toLong().coerceAtMost(maxIntervalMs)
                    } else {
                        (dynamicIntervalMs * 0.72f).toLong().coerceAtLeast(minIntervalMs)
                    }
                }

                steadyFrames = if (similar) steadyFrames + 1 else 0
                lastFingerprint = fingerprint
                hasLast = true
            }
            val elapsed = System.currentTimeMillis() - startedAt
            val waitMs = (dynamicIntervalMs - elapsed).coerceAtLeast(120L)
            delay(waitMs)
        }
    }

    private fun fingerprint(frame: ByteArray): Long {
        if (frame.isEmpty()) return 0L
        val step = (frame.size / 32).coerceAtLeast(1)
        var acc = frame.size.toLong()
        var i = 0
        while (i < frame.size) {
            acc = (acc * 131L) xor (frame[i].toInt() and 0xFF).toLong()
            i += step
        }
        return acc
    }
}
