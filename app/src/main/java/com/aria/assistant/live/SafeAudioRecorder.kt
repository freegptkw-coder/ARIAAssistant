package com.aria.assistant.live

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlin.math.sqrt

class SafeAudioRecorder(
    private val sampleRate: Int = 16000,
    private val vadThresholdRms: Double = 950.0
) {

    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val encoding = AudioFormat.ENCODING_PCM_16BIT
    private val minBuffer = AudioRecord.getMinBufferSize(sampleRate, channelConfig, encoding)
        .coerceAtLeast(4096)

    private var audioRecord: AudioRecord? = null

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
    }

    fun stop() {
        runCatching { audioRecord?.stop() }
        runCatching { audioRecord?.release() }
        audioRecord = null
    }

    fun readVoicedChunkOrNull(): ByteArray? {
        val recorder = audioRecord ?: return null
        val buffer = ByteArray(minBuffer)
        val read = recorder.read(buffer, 0, buffer.size)
        if (read <= 0) return null

        return if (calculateRms(buffer, read) >= vadThresholdRms) {
            buffer.copyOf(read)
        } else {
            null
        }
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
