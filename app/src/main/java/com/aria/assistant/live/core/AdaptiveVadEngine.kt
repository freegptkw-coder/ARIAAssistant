package com.aria.assistant.live.core

import kotlin.math.max

data class AdaptiveVadDecision(
    val speechStarted: Boolean,
    val speechEnded: Boolean,
    val speechActive: Boolean,
    val shouldTransmitAudio: Boolean,
    val noiseFloorRms: Double,
    val startThresholdRms: Double,
    val continueThresholdRms: Double,
    val useFallback: Boolean,
    val uncertain: Boolean
)

class AdaptiveVadEngine(
    private val minCalibrationFrames: Int = 8,
    private val minStartRms: Double = 620.0,
    private val minContinueRms: Double = 500.0,
    private val startMultiplier: Double = 2.0,
    private val continueMultiplier: Double = 1.45,
    private val startConfirmFrames: Int = 2,
    private val endConfirmFrames: Int = 6,
    private val hangoverFrames: Int = 8,
    private val toggleDebounceMs: Long = 220L,
    private val maxNoiseFloorRms: Double = 4200.0,
    private val minNoiseFloorRms: Double = 120.0,
    private val noiseRiseAlpha: Double = 0.08,
    private val noiseDropAlpha: Double = 0.20
) {

    @Volatile
    private var noiseFloorRms: Double = 300.0

    @Volatile
    private var speaking: Boolean = false

    private var framesSeen: Int = 0
    private var aboveStartCount: Int = 0
    private var belowContinueCount: Int = 0
    private var hangoverRemaining: Int = 0
    private var lastToggleAtMs: Long = 0L

    @Synchronized
    fun processFrame(rms: Double, nowMs: Long = System.currentTimeMillis()): AdaptiveVadDecision {
        if (!rms.isFinite() || rms < 0.0) {
            return buildFallbackDecision(uncertain = true)
        }

        framesSeen += 1
        val uncertain = framesSeen < minCalibrationFrames

        if (!speaking || uncertain) {
            updateNoiseFloor(rms)
        } else {
            val conservativeUpdateLimit = max(minContinueRms, noiseFloorRms * continueMultiplier * 0.80)
            if (rms <= conservativeUpdateLimit) {
                updateNoiseFloor(rms)
            }
        }

        val boundedNoise = noiseFloorRms.coerceIn(minNoiseFloorRms, maxNoiseFloorRms)
        val startThreshold = max(minStartRms, boundedNoise * startMultiplier)
        val continueThreshold = max(minContinueRms, boundedNoise * continueMultiplier)

        var speechStarted = false
        var speechEnded = false

        if (uncertain) {
            return AdaptiveVadDecision(
                speechStarted = false,
                speechEnded = false,
                speechActive = false,
                shouldTransmitAudio = false,
                noiseFloorRms = boundedNoise,
                startThresholdRms = startThreshold,
                continueThresholdRms = continueThreshold,
                useFallback = true,
                uncertain = true
            )
        }

        val debounceOk = (nowMs - lastToggleAtMs) >= toggleDebounceMs

        if (speaking) {
            if (rms >= continueThreshold) {
                belowContinueCount = 0
                hangoverRemaining = hangoverFrames
            } else {
                belowContinueCount += 1
                if (hangoverRemaining > 0) hangoverRemaining -= 1
            }

            if (belowContinueCount >= endConfirmFrames && hangoverRemaining <= 0 && debounceOk) {
                speaking = false
                speechEnded = true
                lastToggleAtMs = nowMs
                aboveStartCount = 0
                belowContinueCount = 0
            }
        } else {
            if (rms >= startThreshold) {
                aboveStartCount += 1
            } else {
                aboveStartCount = 0
            }

            if (aboveStartCount >= startConfirmFrames && debounceOk) {
                speaking = true
                speechStarted = true
                lastToggleAtMs = nowMs
                belowContinueCount = 0
                hangoverRemaining = hangoverFrames
            }
        }

        return AdaptiveVadDecision(
            speechStarted = speechStarted,
            speechEnded = speechEnded,
            speechActive = speaking,
            shouldTransmitAudio = speaking,
            noiseFloorRms = boundedNoise,
            startThresholdRms = startThreshold,
            continueThresholdRms = continueThreshold,
            useFallback = false,
            uncertain = false
        )
    }

    @Synchronized
    fun reset() {
        noiseFloorRms = 300.0
        speaking = false
        framesSeen = 0
        aboveStartCount = 0
        belowContinueCount = 0
        hangoverRemaining = 0
        lastToggleAtMs = 0L
    }

    private fun updateNoiseFloor(rms: Double) {
        if (rms <= 0.0) return
        val alpha = if (rms > noiseFloorRms) noiseRiseAlpha else noiseDropAlpha
        noiseFloorRms = (1.0 - alpha) * noiseFloorRms + alpha * rms
    }

    private fun buildFallbackDecision(uncertain: Boolean): AdaptiveVadDecision {
        val boundedNoise = noiseFloorRms.coerceIn(minNoiseFloorRms, maxNoiseFloorRms)
        return AdaptiveVadDecision(
            speechStarted = false,
            speechEnded = false,
            speechActive = false,
            shouldTransmitAudio = false,
            noiseFloorRms = boundedNoise,
            startThresholdRms = max(minStartRms, boundedNoise * startMultiplier),
            continueThresholdRms = max(minContinueRms, boundedNoise * continueMultiplier),
            useFallback = true,
            uncertain = uncertain
        )
    }
}