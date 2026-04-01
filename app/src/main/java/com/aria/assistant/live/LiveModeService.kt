package com.aria.assistant.live

import android.Manifest
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.aria.assistant.RootCommandExecutor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File

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
    private var localTtsSpeaker: LiveLocalTtsSpeaker? = null
    private var wsClient: RealtimeWsClient? = null
    private var avatarOverlay: LiveAvatarOverlay? = null
    @Volatile
    private var lastAudioChunkAt: Long = 0L

    override fun onCreate() {
        super.onCreate()
        LiveNotification.ensureChannel(this)
        startForeground(LiveNotification.NOTIFICATION_ID, LiveNotification.build(this))
        localTtsSpeaker = LiveLocalTtsSpeaker(this)
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

        val alwaysOn = ConsentStore.isAlwaysOn(this)
        if (!ConsentStore.isSessionActive(this)) {
            if (alwaysOn) {
                ConsentStore.startSession(this, 240)
                AuditLogger.log(this, "live_session_auto_renewed:on_start")
            } else {
                ConsentStore.setLiveEnabled(this, false)
                AuditLogger.log(this, "live_not_started:session_missing_or_expired")
                stopSelf()
                return START_NOT_STICKY
            }
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
        localTtsSpeaker?.shutdown()
        localTtsSpeaker = null
        avatarOverlay?.hide()
        avatarOverlay = null
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

        if (ConsentStore.isAvatarEnabled(this)) {
            avatarOverlay = LiveAvatarOverlay(this).also { it.show() }
            avatarOverlay?.updateMessage("Live session started 🌸")
        }

        val wsUrl = ConsentStore.getWsUrl(this)
        if (wsUrl.isBlank()) {
            AuditLogger.log(this, "live_not_started:ws_url_missing")
            avatarOverlay?.updateMessage("WS URL missing. Set live endpoint.")
            stopSelf()
            return
        }

        wsClient = RealtimeWsClient(
            onBinaryAudioChunk = { chunk ->
                lastAudioChunkAt = System.currentTimeMillis()
                avatarOverlay?.setSpeaking(true)
                serviceScope.launch {
                    delay(650)
                    avatarOverlay?.setSpeaking(false)
                }
                ttsPlayer?.playChunk(chunk)
            },
            onTextEvent = { textEvent ->
                AuditLogger.log(this, "ws_text:${textEvent.take(40)}")
                val speakable = extractSpeakableText(textEvent)
                if (!speakable.isNullOrBlank()) {
                    avatarOverlay?.updateMessage(speakable)
                    if (System.currentTimeMillis() - lastAudioChunkAt > 1400L) {
                        localTtsSpeaker?.speak(speakable)
                    }
                }
                onProactiveTextEvent?.invoke(textEvent)
            },
            onClosed = { reason ->
                AuditLogger.log(this, reason)
                avatarOverlay?.updateMessage("Connection closed: $reason")
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
                    if (ConsentStore.isAlwaysOn(this@LiveModeService)) {
                        ConsentStore.startSession(this@LiveModeService, 240)
                        AuditLogger.log(this@LiveModeService, "live_session_auto_renewed:loop")
                    } else {
                        ConsentStore.setLiveEnabled(this@LiveModeService, false)
                        AuditLogger.log(this@LiveModeService, "live_auto_stop:session_expired")
                        stopSelf()
                        break
                    }
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
                VisionLoopManager(
                    frameProvider,
                    frameUploader,
                    intervalMs = ConsentStore.getVisionIntervalMs(this@LiveModeService)
                ).runLoop()
            }
        } else if (ConsentStore.isVisionEnabled(this)) {
            val fallbackProvider: suspend () -> ByteArray? = {
                captureFrameViaRoot()
            }
            val fallbackUploader: suspend (String) -> Unit = { frame64 ->
                val payload = JSONObject()
                    .put("type", "vision_frame")
                    .put("image_base64", frame64)
                    .toString()
                wsClient?.sendText(payload)
            }

            serviceScope.launch {
                AuditLogger.log(this@LiveModeService, "vision_loop_started:fallback")
                VisionLoopManager(
                    fallbackProvider,
                    fallbackUploader,
                    intervalMs = ConsentStore.getVisionIntervalMs(this@LiveModeService)
                ).runLoop()
            }
        }
    }

    private fun extractSpeakableText(raw: String): String? {
        val text = raw.trim()
        if (text.isBlank()) return null
        if (text.startsWith("ws_")) return null

        if (text.startsWith("{")) {
            runCatching {
                val obj = JSONObject(text)
                val directKeys = listOf("text", "message", "response", "content", "reply")
                directKeys.forEach { key ->
                    val value = obj.optString(key)
                    if (value.isNotBlank()) return value
                }

                val data = obj.optJSONObject("data")
                if (data != null) {
                    directKeys.forEach { key ->
                        val value = data.optString(key)
                        if (value.isNotBlank()) return value
                    }
                }
            }
            return null
        }

        return text.take(220)
    }

    private fun captureFrameViaRoot(): ByteArray? {
        val executor = RootCommandExecutor()
        if (!executor.checkRootAccess()) return null

        val path = "/sdcard/aria_live_frame.png"
        val out = executor.execute("screencap -p $path")
        if (out.contains("Error", ignoreCase = true)) return null

        val file = File(path)
        if (!file.exists() || file.length() <= 0) return null
        return runCatching { file.readBytes() }.getOrNull()
    }
}
