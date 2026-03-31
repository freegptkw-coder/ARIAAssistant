package com.aria.assistant.live

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import java.util.Locale

class LiveLocalTtsSpeaker(context: Context) : TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = TextToSpeech(context.applicationContext, this)
    @Volatile
    private var ready = false

    override fun onInit(status: Int) {
        ready = status == TextToSpeech.SUCCESS
        if (ready) {
            tts?.language = Locale.getDefault()
            tts?.setSpeechRate(1.05f)
            tts?.setPitch(1.02f)
        }
    }

    fun speak(text: String) {
        if (!ready || text.isBlank()) return
        val safe = text.replace("\n", " ").trim().take(220)
        if (safe.isBlank()) return
        tts?.speak(safe, TextToSpeech.QUEUE_FLUSH, Bundle(), "live_${System.currentTimeMillis()}")
    }

    fun shutdown() {
        runCatching { tts?.stop() }
        runCatching { tts?.shutdown() }
        tts = null
        ready = false
    }
}
