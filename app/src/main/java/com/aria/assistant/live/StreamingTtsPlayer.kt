package com.aria.assistant.live

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.util.concurrent.atomic.AtomicBoolean

class StreamingTtsPlayer(
    sampleRate: Int = 24000,
    bufferSizeBytes: Int = 24000
) {

    interface Listener {
        fun onPlaybackStarted()
        fun onPlaybackStopped()
    }

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

    private val started = AtomicBoolean(false)
    private val speaking = AtomicBoolean(false)

    @Volatile
    private var listener: Listener? = null

    fun setListener(listener: Listener?) {
        this.listener = listener
    }

    fun start() {
        if (started.compareAndSet(false, true)) {
            audioTrack.play()
        }
    }

    fun playChunk(pcmChunk: ByteArray) {
        if (pcmChunk.isEmpty()) return
        if (!started.get()) start()

        if (speaking.compareAndSet(false, true)) {
            listener?.onPlaybackStarted()
        }
        audioTrack.write(pcmChunk, 0, pcmChunk.size)
    }

    fun stopNow(reason: String = "stop_now") {
        runCatching {
            audioTrack.pause()
            audioTrack.flush()
            audioTrack.play()
        }
        if (speaking.compareAndSet(true, false)) {
            listener?.onPlaybackStopped()
        }
    }

    fun markIdleFromInactivity() {
        if (speaking.compareAndSet(true, false)) {
            listener?.onPlaybackStopped()
        }
    }

    fun stop() {
        runCatching {
            audioTrack.stop()
            audioTrack.flush()
            audioTrack.release()
        }
        started.set(false)
        if (speaking.compareAndSet(true, false)) {
            listener?.onPlaybackStopped()
        }
    }
}
