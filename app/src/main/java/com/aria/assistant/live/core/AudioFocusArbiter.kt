package com.aria.assistant.live.core

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build

class AudioFocusArbiter(
    context: Context,
    private val onFocusStateChanged: ((AudioFocusState, Int) -> Unit)? = null
) {

    enum class AudioFocusState {
        IDLE,
        GAINED,
        LOSS_TRANSIENT,
        LOSS_TRANSIENT_CAN_DUCK,
        LOSS_PERMANENT,
        FAILED
    }

    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    @Volatile
    private var state: AudioFocusState = AudioFocusState.IDLE

    @Volatile
    private var focusHeld: Boolean = false

    private var request: AudioFocusRequest? = null

    private val listener = AudioManager.OnAudioFocusChangeListener { change ->
        handleFocusChange(change)
    }

    @Synchronized
    fun requestSessionFocus(
        gainType: Int = AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
    ): Boolean {
        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()

            val req = AudioFocusRequest.Builder(gainType)
                .setAudioAttributes(attrs)
                .setAcceptsDelayedFocusGain(true)
                .setWillPauseWhenDucked(false)
                .setOnAudioFocusChangeListener(listener)
                .build()

            request = req
            audioManager.requestAudioFocus(req)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(listener, AudioManager.STREAM_MUSIC, gainType)
        }

        return when (result) {
            AudioManager.AUDIOFOCUS_REQUEST_GRANTED -> {
                updateState(AudioFocusState.GAINED, AudioManager.AUDIOFOCUS_GAIN)
                true
            }

            AudioManager.AUDIOFOCUS_REQUEST_DELAYED -> {
                focusHeld = false
                updateState(AudioFocusState.IDLE, result)
                false
            }

            else -> {
                focusHeld = false
                updateState(AudioFocusState.FAILED, result)
                false
            }
        }
    }

    @Synchronized
    fun abandonFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            request?.let { audioManager.abandonAudioFocusRequest(it) }
            request = null
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(listener)
        }
        focusHeld = false
        updateState(AudioFocusState.IDLE, Int.MIN_VALUE)
    }

    fun isFocusHeld(): Boolean = focusHeld

    fun shouldDuckOutput(): Boolean = state == AudioFocusState.LOSS_TRANSIENT_CAN_DUCK

    fun isOutputSuppressed(): Boolean {
        return state == AudioFocusState.LOSS_TRANSIENT || state == AudioFocusState.LOSS_PERMANENT
    }

    @Synchronized
    fun ensureFocusForOutput(): Boolean {
        if (focusHeld && !isOutputSuppressed()) return true
        return requestSessionFocus()
    }

    private fun handleFocusChange(change: Int) {
        when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                focusHeld = true
                updateState(AudioFocusState.GAINED, change)
            }

            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                focusHeld = false
                updateState(AudioFocusState.LOSS_TRANSIENT, change)
            }

            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                focusHeld = true
                updateState(AudioFocusState.LOSS_TRANSIENT_CAN_DUCK, change)
            }

            AudioManager.AUDIOFOCUS_LOSS -> {
                focusHeld = false
                updateState(AudioFocusState.LOSS_PERMANENT, change)
            }

            else -> Unit
        }
    }

    private fun updateState(newState: AudioFocusState, change: Int) {
        state = newState
        onFocusStateChanged?.invoke(newState, change)
    }
}