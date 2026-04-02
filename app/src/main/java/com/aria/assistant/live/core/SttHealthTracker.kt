package com.aria.assistant.live.core

data class SttHealthSnapshot(
    val consecutiveFailures: Int,
    val consecutiveTimeouts: Int,
    val consecutiveUnavailable: Int,
    val retryLevel: Int,
    val cooldownMs: Long,
    val shouldScheduleRetry: Boolean
)

class SttHealthTracker(
    private val baseCooldownMs: Long = 1500L,
    private val maxCooldownMs: Long = 60_000L,
    private val resetWindowMs: Long = 45_000L,
    private val timeoutThresholdForRetry: Int = 2,
    private val recoverableFailureThresholdForRetry: Int = 3,
    private val maxRetryLevel: Int = 8
) {

    private var consecutiveFailures: Int = 0
    private var consecutiveTimeouts: Int = 0
    private var consecutiveUnavailable: Int = 0
    private var retryLevel: Int = 0
    private var lastFailureAtMs: Long = 0L

    @Synchronized
    fun resetOnSuccess(nowMs: Long = System.currentTimeMillis()): SttHealthSnapshot {
        consecutiveFailures = 0
        consecutiveTimeouts = 0
        consecutiveUnavailable = 0
        retryLevel = 0
        lastFailureAtMs = nowMs
        return snapshot(shouldScheduleRetry = false)
    }

    @Synchronized
    fun resetAll(): SttHealthSnapshot {
        consecutiveFailures = 0
        consecutiveTimeouts = 0
        consecutiveUnavailable = 0
        retryLevel = 0
        lastFailureAtMs = 0L
        return snapshot(shouldScheduleRetry = false)
    }

    @Synchronized
    fun onTimeout(nowMs: Long = System.currentTimeMillis()): SttHealthSnapshot {
        registerFailure(nowMs)
        consecutiveTimeouts += 1
        val shouldRetry = consecutiveTimeouts >= timeoutThresholdForRetry ||
            consecutiveFailures >= recoverableFailureThresholdForRetry
        return snapshot(shouldScheduleRetry = shouldRetry)
    }

    @Synchronized
    fun onRecoverableError(nowMs: Long = System.currentTimeMillis()): SttHealthSnapshot {
        registerFailure(nowMs)
        val shouldRetry = consecutiveFailures >= recoverableFailureThresholdForRetry ||
            consecutiveTimeouts >= timeoutThresholdForRetry
        return snapshot(shouldScheduleRetry = shouldRetry)
    }

    @Synchronized
    fun onUnavailable(nowMs: Long = System.currentTimeMillis()): SttHealthSnapshot {
        registerFailure(nowMs, levelBump = 2)
        consecutiveUnavailable += 1
        return snapshot(shouldScheduleRetry = true)
    }

    @Synchronized
    fun onUnrecoverableError(nowMs: Long = System.currentTimeMillis()): SttHealthSnapshot {
        registerFailure(nowMs, levelBump = 2)
        consecutiveUnavailable += 1
        return snapshot(shouldScheduleRetry = true)
    }

    private fun registerFailure(nowMs: Long, levelBump: Int = 1) {
        val stale = lastFailureAtMs > 0L && (nowMs - lastFailureAtMs) > resetWindowMs
        if (stale) {
            consecutiveFailures = 0
            consecutiveTimeouts = 0
            consecutiveUnavailable = 0
            retryLevel = 0
        }
        lastFailureAtMs = nowMs
        consecutiveFailures += 1
        retryLevel = (retryLevel + levelBump).coerceAtMost(maxRetryLevel)
    }

    private fun snapshot(shouldScheduleRetry: Boolean): SttHealthSnapshot {
        return SttHealthSnapshot(
            consecutiveFailures = consecutiveFailures,
            consecutiveTimeouts = consecutiveTimeouts,
            consecutiveUnavailable = consecutiveUnavailable,
            retryLevel = retryLevel,
            cooldownMs = computeCooldownMs(retryLevel),
            shouldScheduleRetry = shouldScheduleRetry
        )
    }

    private fun computeCooldownMs(level: Int): Long {
        if (level <= 0) return baseCooldownMs
        val shift = (level - 1).coerceIn(0, 16)
        val scaled = baseCooldownMs * (1L shl shift)
        return scaled.coerceAtMost(maxCooldownMs)
    }
}