package com.aria.assistant.live.core

import com.aria.assistant.live.LiveLocalTtsSpeaker
import com.aria.assistant.live.StreamingTtsPlayer

class LiveSpeechOutputArbiter(
    private val localSpeaker: LiveLocalTtsSpeaker,
    private val pcmPlayer: StreamingTtsPlayer,
    private val onSpeechStarted: (source: String) -> Unit,
    private val onSpeechFinished: (source: String) -> Unit
) {

    @Volatile
    private var localSpeaking = false

    @Volatile
    private var pcmSpeaking = false

    @Volatile
    private var lastPcmChunkAtMs: Long = 0L

    init {
        localSpeaker.setListener(object : LiveLocalTtsSpeaker.Listener {
            override fun onUtteranceStarted(utteranceId: String) {
                if (!localSpeaking) {
                    localSpeaking = true
                    onSpeechStarted("local_tts")
                }
            }

            override fun onUtteranceCompleted(utteranceId: String) = Unit

            override fun onUtteranceError(utteranceId: String) = Unit

            override fun onQueueIdle() {
                if (localSpeaking) {
                    localSpeaking = false
                    onSpeechFinished("local_tts")
                }
            }
        })

        pcmPlayer.setListener(object : StreamingTtsPlayer.Listener {
            override fun onPlaybackStarted() {
                if (!pcmSpeaking) {
                    pcmSpeaking = true
                    onSpeechStarted("pcm_stream")
                }
            }

            override fun onPlaybackStopped() {
                if (pcmSpeaking) {
                    pcmSpeaking = false
                    onSpeechFinished("pcm_stream")
                }
            }
        })
    }

    fun playPcmChunk(chunk: ByteArray) {
        if (chunk.isEmpty()) return
        lastPcmChunkAtMs = System.currentTimeMillis()
        pcmPlayer.playChunk(chunk)
    }

    fun speakText(text: String, flush: Boolean = false) {
        localSpeaker.speak(text, flushQueue = flush)
    }

    fun stopNow(reason: String = "interrupt") {
        localSpeaker.stopNow()
        pcmPlayer.stopNow(reason)
        localSpeaking = false
        if (pcmSpeaking) {
            pcmSpeaking = false
            onSpeechFinished("pcm_stream")
        }
    }

    fun checkIdle(nowMs: Long = System.currentTimeMillis(), pcmInactivityMs: Long = 700L) {
        if (pcmSpeaking && (nowMs - lastPcmChunkAtMs) > pcmInactivityMs) {
            pcmPlayer.markIdleFromInactivity()
        }
    }

    fun shutdown() {
        stopNow("shutdown")
        pcmPlayer.stop()
        localSpeaker.shutdown()
    }
}
