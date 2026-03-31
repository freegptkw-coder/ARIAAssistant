package com.aria.assistant.live

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack

class StreamingTtsPlayer(
    sampleRate: Int = 24000,
    bufferSizeBytes: Int = 24000
) {

    private val audioTrack = AudioTrack.Builder()
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        .setAudioFormat(
            AudioFormat.Builder()
                .setSampleRate(sampleRate)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build()
        )
        .setTransferMode(AudioTrack.MODE_STREAM)
        .setBufferSizeInBytes(bufferSizeBytes)
        .build()

    fun start() {
        audioTrack.play()
    }

    fun playChunk(pcmChunk: ByteArray) {
        audioTrack.write(pcmChunk, 0, pcmChunk.size)
    }

    fun stop() {
        runCatching {
            audioTrack.stop()
            audioTrack.flush()
            audioTrack.release()
        }
    }
}
