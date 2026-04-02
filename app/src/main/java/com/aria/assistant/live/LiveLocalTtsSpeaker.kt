package com.aria.assistant.live

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

class LiveLocalTtsSpeaker(context: Context) : TextToSpeech.OnInitListener {

    interface Listener {
        fun onUtteranceStarted(utteranceId: String)
        fun onUtteranceCompleted(utteranceId: String)
        fun onUtteranceError(utteranceId: String)
        fun onQueueIdle()
    }

    private var tts: TextToSpeech? = TextToSpeech(context.applicationContext, this)

    @Volatile
    private var ready = false

    @Volatile
    private var listener: Listener? = null

    private val pendingUtterances = AtomicInteger(0)

    override fun onInit(status: Int) {
        ready = status == TextToSpeech.SUCCESS
        if (!ready) return

        tts?.language = Locale.getDefault()
        tts?.setSpeechRate(1.06f)
        tts?.setPitch(1.02f)
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                utteranceId ?: return
                listener?.onUtteranceStarted(utteranceId)
            }

            override fun onDone(utteranceId: String?) {
                utteranceId ?: return
                listener?.onUtteranceCompleted(utteranceId)
                val remaining = (pendingUtterances.decrementAndGet()).coerceAtLeast(0)
                if (remaining == 0) {
                    listener?.onQueueIdle()
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                utteranceId ?: return
                listener?.onUtteranceError(utteranceId)
                val remaining = (pendingUtterances.decrementAndGet()).coerceAtLeast(0)
                if (remaining == 0) {
                    listener?.onQueueIdle()
                }
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                utteranceId ?: return
                listener?.onUtteranceError("$utteranceId:$errorCode")
                val remaining = (pendingUtterances.decrementAndGet()).coerceAtLeast(0)
                if (remaining == 0) {
                    listener?.onQueueIdle()
                }
            }
        })
    }

    fun setListener(listener: Listener?) {
        this.listener = listener
    }

    fun speak(text: String, flushQueue: Boolean = false) {
        if (!ready || text.isBlank()) return

        val safe = text
            .replace("\n", " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(260)
        if (safe.isBlank()) return

        if (flushQueue) {
            pendingUtterances.set(0)
        }

        val queueMode = if (flushQueue) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        val utteranceId = "live_${System.currentTimeMillis()}_${pendingUtterances.incrementAndGet()}"
        tts?.speak(safe, queueMode, Bundle(), utteranceId)
    }

    fun clearQueue() {
        pendingUtterances.set(0)
        runCatching { tts?.stop() }
        listener?.onQueueIdle()
    }

    fun stopNow() {
        clearQueue()
    }

    fun shutdown() {
        pendingUtterances.set(0)
        runCatching { tts?.stop() }
        runCatching { tts?.shutdown() }
        tts = null
        ready = false
    }
}
