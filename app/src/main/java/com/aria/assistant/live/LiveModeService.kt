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
import kotlin.math.max
import kotlin.math.min

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
    @Volatile
    private var lastMemoriesSpeechAt: Long = 0L
    @Volatile
    private var lastMemoriesNormalizedText: String = ""
    @Volatile
    private var memoriesNoResponseStreak: Int = 0
    @Volatile
    private var wsProtocolBroken: Boolean = false
    @Volatile
    private var wsForceMemoriesFallback: Boolean = false
    @Volatile
    private var wsOpenedAt: Long = 0L
    @Volatile
    private var wsLastInboundAt: Long = 0L
    @Volatile
    private var wsAudioSentCount: Int = 0
    @Volatile
    private var wsLastNoReplyAlertAt: Long = 0L

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
        val backendMode = ConsentStore.getLiveBackendMode(this)
        val wsConfigured = wsUrl.isNotBlank()
        val wsAllowedByMode = backendMode == "auto" || backendMode == "ws" || backendMode == "hybrid"
        val memoriesAllowedByMode = backendMode == "auto" || backendMode == "memories" || backendMode == "hybrid"
        val visionEnabled = ConsentStore.isVisionEnabled(this)
        val memoriesEnabled = ConsentStore.isMemoriesEnabled(this)
        val memoriesApiKey = ConsentStore.getMemoriesApiKey(this)
        val memoriesReady = memoriesEnabled && memoriesApiKey.isNotBlank()
        val wsEnabled = wsConfigured && wsAllowedByMode
        val memoriesEnabledForSession = memoriesReady && memoriesAllowedByMode
        AuditLogger.log(
            this,
            "live_config:mode=$backendMode,ws=${if (wsEnabled) "on" else "off"},vision=$visionEnabled,memories=${if (memoriesEnabledForSession) "on" else "off"}"
        )

        if (!wsEnabled && !memoriesEnabledForSession) {
            AuditLogger.log(this, "live_not_started:no_backend_configured")
            avatarOverlay?.updateMessage("No backend set. Configure WS or Memories.ai API key.")
            localTtsSpeaker?.speak("Live backend missing. Configure WS or Memories key.")
            stopSelf()
            return
        }

        if (!wsEnabled && memoriesEnabledForSession && !visionEnabled) {
            AuditLogger.log(this, "live_not_started:memories_requires_vision")
            avatarOverlay?.updateMessage("Enable Live Vision for Memories mode.")
            localTtsSpeaker?.speak("Memories mode needs live vision on.")
            stopSelf()
            return
        }

        if (memoriesEnabled && memoriesApiKey.isBlank()) {
            AuditLogger.log(this, "memories_disabled:key_missing")
        }

        if (wsEnabled) {
            wsProtocolBroken = false
            wsForceMemoriesFallback = false
            wsOpenedAt = System.currentTimeMillis()
            wsLastInboundAt = wsOpenedAt
            wsAudioSentCount = 0
            wsLastNoReplyAlertAt = 0L
            wsClient = RealtimeWsClient(
                onBinaryAudioChunk = { chunk ->
                    lastAudioChunkAt = System.currentTimeMillis()
                    wsLastInboundAt = System.currentTimeMillis()
                    avatarOverlay?.setSpeaking(true)
                    serviceScope.launch {
                        delay(650)
                        avatarOverlay?.setSpeaking(false)
                    }
                    ttsPlayer?.playChunk(chunk)
                },
                onTextEvent = { textEvent ->
                    AuditLogger.log(this, "ws_text:${textEvent.take(40)}")
                    val low = textEvent.lowercase()
                    if (!low.startsWith("ws_open") && !low.startsWith("ws_ping") && !low.startsWith("ws_pong")) {
                        wsLastInboundAt = System.currentTimeMillis()
                    }
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
                    val low = reason.lowercase()
                    if (
                        low.contains("1002") ||
                        low.contains("protocol error") ||
                        low.contains("ws_failure")
                    ) {
                        wsProtocolBroken = true
                        if (memoriesEnabledForSession) {
                            wsForceMemoriesFallback = true
                            AuditLogger.log(this, "ws_failure:fallback_to_memories")
                            if (!visionEnabled) {
                                AuditLogger.log(this, "memories_fallback_blocked:vision_off")
                                avatarOverlay?.updateMessage("Turn ON Live Vision for Memories fallback")
                                localTtsSpeaker?.speak("Turn on live vision for memories fallback")
                            }
                        }
                    }
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
        } else if (wsConfigured && !wsAllowedByMode) {
            AuditLogger.log(this, "live_ws_disabled:mode_$backendMode")
        } else {
            AuditLogger.log(this, "live_ws_disabled:using_memories")
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
                    if (wsClient != null) {
                        val sent = wsClient?.sendAudioPcm(voicedChunk) == true
                        if (sent) {
                            wsAudioSentCount += 1
                            AuditLogger.log(this@LiveModeService, "audio_chunk_sent")
                        } else {
                            AuditLogger.log(this@LiveModeService, "audio_chunk_drop:ws_not_ready")
                        }
                    }
                }
                delay(40)
            }
        }

        if (wsEnabled && memoriesEnabledForSession) {
            serviceScope.launch {
                while (running && isActive) {
                    val now = System.currentTimeMillis()
                    val staleInbound = now - wsLastInboundAt > 12_000L
                    val openedLongEnough = now - wsOpenedAt > 15_000L
                    if (!wsForceMemoriesFallback && wsAudioSentCount >= 8 && openedLongEnough && staleInbound) {
                        wsForceMemoriesFallback = true
                        AuditLogger.log(this@LiveModeService, "ws_no_reply:fallback_to_memories")
                        avatarOverlay?.updateMessage("WS no response, switching to Memories…")
                    }
                    delay(2500L)
                }
            }
        } else if (wsEnabled) {
            serviceScope.launch {
                while (running && isActive) {
                    val now = System.currentTimeMillis()
                    val staleInbound = now - wsLastInboundAt > 12_000L
                    val openedLongEnough = now - wsOpenedAt > 15_000L
                    val cooldownDone = now - wsLastNoReplyAlertAt > 20_000L
                    if (wsAudioSentCount >= 8 && openedLongEnough && staleInbound && cooldownDone) {
                        wsLastNoReplyAlertAt = now
                        AuditLogger.log(this@LiveModeService, "ws_no_reply:no_fallback_available")
                        avatarOverlay?.updateMessage("WS no response. Enable Memories for backup.")
                    }
                    delay(2500L)
                }
            }
        }

        val frameProvider = visionFrameProvider
        val frameUploader = visionFrameUploader
        if (visionEnabled && frameProvider != null && frameUploader != null) {
            serviceScope.launch {
                AuditLogger.log(this@LiveModeService, "vision_loop_started")
                VisionLoopManager(
                    frameProvider,
                    frameUploader,
                    intervalMs = ConsentStore.getVisionIntervalMs(this@LiveModeService),
                    adaptiveMode = true
                ).runLoop()
            }
        } else if (visionEnabled && wsClient != null) {
            val memoriesClient = if (memoriesEnabledForSession) MemoriesImageCaptionClient(memoriesApiKey) else null
            val prompt = ConsentStore.getMemoriesPrompt(this)
            val fallbackProvider: suspend () -> ByteArray? = {
                captureFrameViaRoot()
            }
            val fallbackUploader: suspend (String) -> Unit = { frame64 ->
                val shouldUseMemories = wsForceMemoriesFallback || wsProtocolBroken
                if (!shouldUseMemories || memoriesClient == null) {
                    val payload = JSONObject()
                        .put("type", "vision_frame")
                        .put("image_base64", frame64)
                        .toString()
                    val sent = wsClient?.sendText(payload) == true
                    if (!sent && memoriesClient != null) {
                        wsForceMemoriesFallback = true
                        AuditLogger.log(this@LiveModeService, "vision_ws_send_failed:fallback_to_memories")
                        processMemoriesFrame(memoriesClient, prompt, frame64)
                    }
                } else {
                    processMemoriesFrame(memoriesClient, prompt, frame64)
                }
            }

            serviceScope.launch {
                AuditLogger.log(this@LiveModeService, "vision_loop_started:fallback")
                VisionLoopManager(
                    fallbackProvider,
                    fallbackUploader,
                    intervalMs = ConsentStore.getVisionIntervalMs(this@LiveModeService),
                    adaptiveMode = true
                ).runLoop()
            }
        } else if (visionEnabled && memoriesEnabledForSession) {
            val memoriesClient = MemoriesImageCaptionClient(memoriesApiKey)
            val prompt = ConsentStore.getMemoriesPrompt(this)

            val fallbackProvider: suspend () -> ByteArray? = {
                captureFrameViaRoot()
            }
            val fallbackUploader: suspend (String) -> Unit = { frame64 ->
                processMemoriesFrame(memoriesClient, prompt, frame64)
            }

            serviceScope.launch {
                AuditLogger.log(this@LiveModeService, "vision_loop_started:memories")
                VisionLoopManager(
                    fallbackProvider,
                    fallbackUploader,
                    intervalMs = ConsentStore.getVisionIntervalMs(this@LiveModeService),
                    adaptiveMode = true
                ).runLoop()
            }
        } else if (visionEnabled) {
            AuditLogger.log(this@LiveModeService, "vision_loop_not_started:no_backend")
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
        if (!executor.checkRootAccess()) {
            AuditLogger.log(this, "vision_capture_failed:root_unavailable")
            return null
        }

        val path = "/sdcard/aria_live_frame.png"
        val out = executor.execute("screencap -p $path")
        if (out.contains("Error", ignoreCase = true)) {
            AuditLogger.log(this, "vision_capture_failed:screencap_error:${out.take(80)}")
            return null
        }

        val file = File(path)
        if (!file.exists() || file.length() <= 0) {
            AuditLogger.log(this, "vision_capture_failed:file_missing")
            return null
        }
        return runCatching { file.readBytes() }.getOrNull()
    }

    private fun processMemoriesFrame(memoriesClient: MemoriesImageCaptionClient, prompt: String, frame64: String) {
        val text = memoriesClient.describeFrameBase64(frame64, prompt)
        if (!text.isNullOrBlank()) {
            memoriesNoResponseStreak = 0
            val trimmed = text.trim().take(240)
            val now = System.currentTimeMillis()
            val normalized = normalizeForSimilarity(trimmed)
            val shouldSpeak = shouldSpeakMemories(normalized, now)
            val shouldUpdateBubble = !isSemanticallySimilar(normalized, lastMemoriesNormalizedText)

            if (shouldUpdateBubble) {
                avatarOverlay?.updateMessage(trimmed)
            }

            if (shouldSpeak && now - lastAudioChunkAt > 1600L) {
                lastMemoriesNormalizedText = normalized
                lastMemoriesSpeechAt = now
                localTtsSpeaker?.speak(trimmed)
                AuditLogger.log(this@LiveModeService, "memories_text:${trimmed.take(60)}")
            }
        } else {
            memoriesNoResponseStreak += 1
            if (memoriesNoResponseStreak % 3 == 0) {
                val err = memoriesClient.getLastError().ifBlank { "unknown" }
                AuditLogger.log(this@LiveModeService, "memories_no_response:$err")
                if (memoriesNoResponseStreak % 6 == 0) {
                    avatarOverlay?.updateMessage("Memories no response ($err)")
                }
            }
        }
    }

    private fun shouldSpeakMemories(normalizedText: String, nowMs: Long): Boolean {
        if (normalizedText.isBlank()) return false

        val sinceLast = nowMs - lastMemoriesSpeechAt
        if (sinceLast < 3500L) return false

        if (lastMemoriesNormalizedText.isBlank()) return true

        val isSimilar = isSemanticallySimilar(normalizedText, lastMemoriesNormalizedText)
        if (isSimilar && sinceLast < 12_000L) return false

        return true
    }

    private fun normalizeForSimilarity(text: String): String {
        return text
            .lowercase()
            .replace(Regex("[^a-z0-9\\u0980-\\u09FF\\u0900-\\u097F\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun isSemanticallySimilar(a: String, b: String): Boolean {
        if (a.isBlank() || b.isBlank()) return false
        if (a == b) return true

        if (a.contains(b) || b.contains(a)) {
            val shorter = min(a.length, b.length).toDouble()
            val longer = max(a.length, b.length).toDouble().coerceAtLeast(1.0)
            if (shorter / longer >= 0.78) return true
        }

        val tokensA = a.split(" ").filter { it.length > 2 }.toSet()
        val tokensB = b.split(" ").filter { it.length > 2 }.toSet()
        if (tokensA.isEmpty() || tokensB.isEmpty()) return false

        val intersection = tokensA.intersect(tokensB).size.toDouble()
        val union = tokensA.union(tokensB).size.toDouble().coerceAtLeast(1.0)
        return (intersection / union) >= 0.72
    }
}
