package com.aria.assistant.live

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.aria.assistant.live.core.AdaptiveVadEngine
import kotlin.math.sqrt

data class RecorderVoiceFrame(
    val voicedChunk: ByteArray?,
    val rms: Double,
    val speechStarted: Boolean,
    val speechEnded: Boolean,
    val speechActive: Boolean,
    val usedFallbackVad: Boolean,
    val uncertainVad: Boolean
)

class SafeAudioRecorder(
    private val sampleRate: Int = 16000,
    private val vadThresholdRms: Double = 950.0,
    private val enableAdaptiveVad: Boolean = true
) {

    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val encoding = AudioFormat.ENCODING_PCM_16BIT
    private val minBuffer = AudioRecord.getMinBufferSize(sampleRate, channelConfig, encoding)
        .coerceAtLeast(4096)

    private var audioRecord: AudioRecord? = null
    private var adaptiveVad: AdaptiveVadEngine? = null

    fun start() {
        if (audioRecord != null) return
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            sampleRate,
            channelConfig,
            encoding,
            minBuffer
        )
        audioRecord?.startRecording()
        adaptiveVad = if (enableAdaptiveVad) AdaptiveVadEngine() else null
    }

    fun stop() {
        runCatching { audioRecord?.stop() }
        runCatching { audioRecord?.release() }
        audioRecord = null
        adaptiveVad = null
    }

    fun readVoicedChunkOrNull(): ByteArray? {
        return readVoiceFrameOrNull()?.voicedChunk
    }

    fun readVoiceFrameOrNull(): RecorderVoiceFrame? {
        val recorder = audioRecord ?: return null
        val buffer = ByteArray(minBuffer)
        val read = recorder.read(buffer, 0, buffer.size)
        if (read <= 0) return null

        val rms = calculateRms(buffer, read)
        val fixedVoiced = rms >= vadThresholdRms
        val chunk = buffer.copyOf(read)

        val decision = runCatching {
            adaptiveVad?.processFrame(rms)
        }.getOrNull()

        if (decision == null || decision.useFallback) {
            return RecorderVoiceFrame(
                voicedChunk = if (fixedVoiced) chunk else null,
                rms = rms,
                speechStarted = false,
                speechEnded = false,
                speechActive = fixedVoiced,
                usedFallbackVad = true,
                uncertainVad = decision?.uncertain ?: true
            )
        }

        val adaptiveVoiced = decision.shouldTransmitAudio
        return RecorderVoiceFrame(
            voicedChunk = if (adaptiveVoiced) chunk else null,
            rms = rms,
            speechStarted = decision.speechStarted,
            speechEnded = decision.speechEnded,
            speechActive = decision.speechActive,
            usedFallbackVad = false,
            uncertainVad = decision.uncertain
        )
    }

    private fun calculateRms(bytes: ByteArray, length: Int): Double {
        var i = 0
        var sum = 0.0
        var count = 0

        while (i + 1 < length) {
            val sample = ((bytes[i + 1].toInt() shl 8) or (bytes[i].toInt() and 0xFF)).toShort().toInt()
            sum += (sample * sample).toDouble()
            count++
            i += 2
        }

        if (count == 0) return 0.0
        return sqrt(sum / count)
    }
}
