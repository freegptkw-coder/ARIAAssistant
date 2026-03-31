package com.aria.assistant.live

import android.Manifest
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.IBinder
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class LiveModeService : Service() {

    companion object {
        @Volatile
        var visionFrameProvider: (suspend () -> ByteArray?)? = null

        @Volatile
        var visionFrameUploader: (suspend (String) -> Unit)? = null

        @Volatile
        var onProactiveTextEvent: ((String) -> Unit)? = null
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var running = false

    private var recorder: SafeAudioRecorder? = null
    private var ttsPlayer: StreamingTtsPlayer? = null
    private var wsClient: RealtimeWsClient? = null

    override fun onCreate() {
        super.onCreate()
        LiveNotification.ensureChannel(this)
        startForeground(LiveNotification.NOTIFICATION_ID, LiveNotification.build(this))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == LiveNotification.ACTION_STOP) {
            ConsentStore.endSession(this)
            ConsentStore.setLiveEnabled(this, false)
            AuditLogger.log(this, "live_stopped:panic_action")
            stopSelf()
            return START_NOT_STICKY
        }

        if (!ConsentStore.isLiveEnabled(this)) {
            AuditLogger.log(this, "live_not_started:consent_disabled")
            stopSelf()
            return START_NOT_STICKY
        }

        if (!hasMicPermission()) {
            AuditLogger.log(this, "live_not_started:mic_permission_missing")
            stopSelf()
            return START_NOT_STICKY
        }

        if (!ConsentStore.isSessionActive(this)) {
            ConsentStore.setLiveEnabled(this, false)
            AuditLogger.log(this, "live_not_started:session_missing_or_expired")
            stopSelf()
            return START_NOT_STICKY
        }

        if (!running) {
            running = true
            startPipelines()
        }

        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        recorder?.stop()
        recorder = null
        ttsPlayer?.stop()
        ttsPlayer = null
        wsClient?.close()
        wsClient = null

        serviceScope.cancel()
        AuditLogger.log(this, "live_stopped")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun hasMicPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun startPipelines() {
        AuditLogger.log(this, "live_started")

        recorder = SafeAudioRecorder().also { it.start() }
        ttsPlayer = StreamingTtsPlayer().also { it.start() }

        val wsUrl = ConsentStore.getWsUrl(this)
        if (wsUrl.isBlank()) {
            AuditLogger.log(this, "live_not_started:ws_url_missing")
            stopSelf()
            return
        }

        wsClient = RealtimeWsClient(
            onBinaryAudioChunk = { chunk ->
                ttsPlayer?.playChunk(chunk)
            },
            onTextEvent = { textEvent ->
                AuditLogger.log(this, "ws_text:${textEvent.take(40)}")
                onProactiveTextEvent?.invoke(textEvent)
            },
            onClosed = { reason ->
                AuditLogger.log(this, reason)
            },
            onAuthRefreshRequested = {
                ConsentStore.getWsToken(this@LiveModeService)
            }
        ).also {
            it.connect(
                wsUrl,
                ConsentStore.getWsToken(this),
                ConsentStore.getWsCertPin(this)
            )
        }

        serviceScope.launch {
            while (running && isActive) {
                if (!ConsentStore.isLiveEnabled(this@LiveModeService)) {
                    AuditLogger.log(this@LiveModeService, "live_auto_stop:consent_revoked")
                    stopSelf()
                    break
                }

                if (!ConsentStore.isSessionActive(this@LiveModeService)) {
                    ConsentStore.setLiveEnabled(this@LiveModeService, false)
                    AuditLogger.log(this@LiveModeService, "live_auto_stop:session_expired")
                    stopSelf()
                    break
                }

                val voicedChunk = recorder?.readVoicedChunkOrNull()
                if (voicedChunk != null) {
                    wsClient?.sendAudioPcm(voicedChunk)
                    AuditLogger.log(this@LiveModeService, "audio_chunk_sent")
                }
                delay(40)
            }
        }

        val frameProvider = visionFrameProvider
        val frameUploader = visionFrameUploader
        if (ConsentStore.isVisionEnabled(this) && frameProvider != null && frameUploader != null) {
            serviceScope.launch {
                AuditLogger.log(this@LiveModeService, "vision_loop_started")
                VisionLoopManager(frameProvider, frameUploader).runLoop()
            }
        }
    }
}
