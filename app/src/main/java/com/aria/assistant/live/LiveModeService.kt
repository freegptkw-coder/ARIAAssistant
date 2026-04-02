package com.aria.assistant.live

import android.Manifest
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.aria.assistant.RootCommandExecutor
import com.aria.assistant.live.core.LiveDialogOrchestrator
import com.aria.assistant.live.core.LiveSpeechOutputArbiter
import com.aria.assistant.live.core.AudioFocusArbiter
import com.aria.assistant.live.core.BargeInController
import com.aria.assistant.live.core.ProviderStreamingGateway
import com.aria.assistant.live.core.SttHealthSnapshot
import com.aria.assistant.live.core.SttHealthTracker
import com.aria.assistant.live.core.SttTranscriptEvent
import com.aria.assistant.live.core.StreamingSttGateway
import com.aria.assistant.live.core.VoiceSessionEvent
import com.aria.assistant.live.core.VoiceTurnStateMachine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
    private var speechOutputArbiter: LiveSpeechOutputArbiter? = null
    private var dialogOrchestrator: LiveDialogOrchestrator? = null
    private var providerGateway: ProviderStreamingGateway? = null
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
    @Volatile
    private var assistantOutputTickAt: Long = 0L
    @Volatile
    private var userSpeechActive: Boolean = false
    @Volatile
    private var lastUserSpeechAt: Long = 0L
    @Volatile
    private var lastSttPartialText: String = ""
    @Volatile
    private var suppressAssistantAudioByFocus: Boolean = false
    @Volatile
    private var duckAssistantAudioByFocus: Boolean = false
    @Volatile
    private var pushToTalkPressed: Boolean = false
    @Volatile
    private var activeVoiceMode: String = "hands_free"

    private lateinit var voiceStateMachine: VoiceTurnStateMachine
    private lateinit var bargeInController: BargeInController
    private lateinit var audioFocusArbiter: AudioFocusArbiter
    private var sttGateway: StreamingSttGateway? = null
    private val sttHealthTracker = SttHealthTracker()
    private var sttRetryJob: Job? = null
    @Volatile
    private var sttAvailabilityStatus: String = "idle"
    @Volatile
    private var sttFailureCount: Int = 0
    @Volatile
    private var sttTimeoutCount: Int = 0
    @Volatile
    private var sttUnavailableCount: Int = 0
    @Volatile
    private var sttLastRetryDelayMs: Long = 0L
    @Volatile
    private var sttLastSuccessAtMs: Long = 0L

    override fun onCreate() {
        super.onCreate()
        voiceStateMachine = VoiceTurnStateMachine { from, to, event ->
            val toLabel = to.name.lowercase()
            AuditLogger.log(
                this,
                "voice_state:${from.name.lowercase()}->${to.name.lowercase()}:${event.compactName()}"
            )
            publishSttDebugStatus(voiceStateOverride = toLabel)
        }
        bargeInController = BargeInController()
        audioFocusArbiter = AudioFocusArbiter(this) { state, rawChange ->
            when (state) {
                AudioFocusArbiter.AudioFocusState.GAINED -> {
                    suppressAssistantAudioByFocus = false
                    duckAssistantAudioByFocus = false
                    AuditLogger.log(this, "audio_focus:gained:$rawChange")
                    voiceStateMachine.onEvent(VoiceSessionEvent.RecoverableWarning("audio_focus_gained"))
                }

                AudioFocusArbiter.AudioFocusState.LOSS_TRANSIENT -> {
                    suppressAssistantAudioByFocus = true
                    duckAssistantAudioByFocus = false
                    AuditLogger.log(this, "audio_focus:loss_transient:$rawChange")
                    voiceStateMachine.onEvent(VoiceSessionEvent.RecoverableWarning("audio_focus_loss_transient"))
                    if (running) interruptAssistantSpeech("audio_focus_loss_transient")
                }

                AudioFocusArbiter.AudioFocusState.LOSS_TRANSIENT_CAN_DUCK -> {
                    suppressAssistantAudioByFocus = false
                    duckAssistantAudioByFocus = true
                    AuditLogger.log(this, "audio_focus:duck:$rawChange")
                    voiceStateMachine.onEvent(VoiceSessionEvent.RecoverableWarning("audio_focus_loss_can_duck"))
                }

                AudioFocusArbiter.AudioFocusState.LOSS_PERMANENT -> {
                    suppressAssistantAudioByFocus = true
                    duckAssistantAudioByFocus = false
                    AuditLogger.log(this, "audio_focus:loss_permanent:$rawChange")
                    voiceStateMachine.onEvent(VoiceSessionEvent.BackendFailure("audio_focus_loss_permanent"))
                    if (running) interruptAssistantSpeech("audio_focus_loss_permanent")
                }

                AudioFocusArbiter.AudioFocusState.FAILED -> {
                    suppressAssistantAudioByFocus = false
                    duckAssistantAudioByFocus = false
                    AuditLogger.log(this, "audio_focus:request_failed:$rawChange")
                    voiceStateMachine.onEvent(VoiceSessionEvent.RecoverableWarning("audio_focus_request_failed"))
                }

                AudioFocusArbiter.AudioFocusState.IDLE -> {
                    suppressAssistantAudioByFocus = false
                    duckAssistantAudioByFocus = false
                    AuditLogger.log(this, "audio_focus:idle:$rawChange")
                }
            }
        }
        LiveNotification.ensureChannel(this)
        startForeground(LiveNotification.NOTIFICATION_ID, LiveNotification.build(this))
        localTtsSpeaker = LiveLocalTtsSpeaker(this)
        sttGateway = StreamingSttGateway.createDefault(this) { event ->
            handleSttTranscriptEvent(event)
        }
        publishSttDebugStatus(voiceStateOverride = "idle")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            LiveModeController.ACTION_PUSH_TO_TALK_DOWN -> {
                pushToTalkPressed = true
            }
            LiveModeController.ACTION_PUSH_TO_TALK_UP -> {
                pushToTalkPressed = false
            }
        }

        if (intent?.action == LiveNotification.ACTION_STOP) {
            ConsentStore.endSession(this)
            ConsentStore.setLiveEnabled(this, false)
            cancelPendingSttRetry("panic_action")
            AuditLogger.log(this, "live_stopped:panic_action")
            stopSelf()
            return START_NOT_STICKY
        }

        if (!ConsentStore.isLiveEnabled(this)) {
            AuditLogger.log(this, "live_not_started:consent_disabled")
            cancelPendingSttRetry("consent_disabled")
            stopSelf()
            return START_NOT_STICKY
        }

        if (!hasMicPermission()) {
            AuditLogger.log(this, "live_not_started:mic_permission_missing")
            cancelPendingSttRetry("mic_permission_missing")
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
                cancelPendingSttRetry("session_missing_or_expired")
                stopSelf()
                return START_NOT_STICKY
            }
        }

        if (!running) {
            activeVoiceMode = ConsentStore.getLiveVoiceMode(this)
            running = true
            startPipelines()
        } else {
            activeVoiceMode = ConsentStore.getLiveVoiceMode(this)
        }

        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        if (this::voiceStateMachine.isInitialized) {
            voiceStateMachine.onEvent(VoiceSessionEvent.SessionStopped)
        }
        if (this::audioFocusArbiter.isInitialized) {
            audioFocusArbiter.abandonFocus()
        }
        recorder?.stop()
        recorder = null
        speechOutputArbiter?.shutdown()
        speechOutputArbiter = null
        ttsPlayer = null
        localTtsSpeaker = null
        avatarOverlay?.hide()
        avatarOverlay = null
        cancelPendingSttRetry("service_destroy")
        wsClient?.close()
        wsClient = null
        dialogOrchestrator?.onUserInterrupted()
        dialogOrchestrator = null
        providerGateway = null
        sttGateway?.stop()
        applySttHealthSnapshot(sttHealthTracker.resetAll())
        sttAvailabilityStatus = "stopped"
        publishSttDebugStatus(voiceStateOverride = "idle")

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
        voiceStateMachine.onEvent(VoiceSessionEvent.SessionStarted)
        userSpeechActive = false
        lastUserSpeechAt = 0L
        lastSttPartialText = ""
        applySttHealthSnapshot(sttHealthTracker.resetAll())
        sttAvailabilityStatus = "starting"
        sttLastRetryDelayMs = 0L
        sttLastSuccessAtMs = 0L
        cancelPendingSttRetry("session_start")
        publishSttDebugStatus(voiceStateOverride = voiceStateMachine.currentState.name.lowercase())

        val focusGranted = audioFocusArbiter.requestSessionFocus()
        if (!focusGranted) {
            AuditLogger.log(this, "audio_focus:initial_request_failed")
            voiceStateMachine.onEvent(VoiceSessionEvent.RecoverableWarning("audio_focus_initial_request_failed"))
        }

        activeVoiceMode = ConsentStore.getLiveVoiceMode(this)
        providerGateway = ProviderStreamingGateway(this)

        recorder = SafeAudioRecorder().also { it.start() }
        ttsPlayer = StreamingTtsPlayer().also { it.start() }
        if (localTtsSpeaker == null) {
            localTtsSpeaker = LiveLocalTtsSpeaker(this)
        }

        val localSpeaker = localTtsSpeaker
        val pcmSpeaker = ttsPlayer
        if (localSpeaker != null && pcmSpeaker != null) {
            speechOutputArbiter = LiveSpeechOutputArbiter(
                localSpeaker = localSpeaker,
                pcmPlayer = pcmSpeaker,
                onSpeechStarted = { source ->
                    val now = System.currentTimeMillis()
                    assistantOutputTickAt = now
                    bargeInController.markAssistantOutputStarted(now)
                    voiceStateMachine.onEvent(VoiceSessionEvent.AssistantAudioStarted(source))
                    avatarOverlay?.setSpeaking(true)
                },
                onSpeechFinished = { _ ->
                    assistantOutputTickAt = 0L
                    bargeInController.markAssistantOutputStopped()
                    voiceStateMachine.onEvent(VoiceSessionEvent.AssistantAudioFinished)
                    avatarOverlay?.setSpeaking(false)
                }
            )
        }

        dialogOrchestrator = providerGateway?.let { gateway ->
            LiveDialogOrchestrator(
                context = this,
                scope = serviceScope,
                llmGateway = gateway,
                listener = object : LiveDialogOrchestrator.Listener {
                    override fun onLlmRequestStarted(turnId: Long, text: String) {
                        AuditLogger.log(this@LiveModeService, "llm_request_started:$turnId")
                        voiceStateMachine.onEvent(VoiceSessionEvent.LlmRequestStarted)
                    }

                    override fun onLlmChunk(turnId: Long, chunk: String, source: String) {
                        if (source.startsWith("provider_fallback")) {
                            AuditLogger.log(this@LiveModeService, "llm_fallback:$source")
                            return
                        }
                        val speakable = chunk.trim()
                        if (speakable.isBlank()) return

                        voiceStateMachine.onEvent(VoiceSessionEvent.LlmResponseChunk(speakable))
                        avatarOverlay?.updateMessage(speakable)

                        if (duckAssistantAudioByFocus) {
                            AuditLogger.log(this@LiveModeService, "audio_focus:duck_skip_provider_tts")
                            return
                        }
                        if (!canRenderAssistantAudio()) {
                            AuditLogger.log(this@LiveModeService, "audio_focus:suppress_provider_tts")
                            return
                        }
                        speechOutputArbiter?.speakText(speakable)
                    }

                    override fun onLlmCompleted(turnId: Long, fullText: String, source: String) {
                        AuditLogger.log(this@LiveModeService, "llm_completed:$turnId:$source")
                        if (fullText.isNotBlank()) {
                            avatarOverlay?.updateMessage(fullText.take(240))
                        }
                    }

                    override fun onLlmFailed(turnId: Long, reason: String) {
                        AuditLogger.log(this@LiveModeService, "llm_failed:$turnId:$reason")
                        voiceStateMachine.onEvent(VoiceSessionEvent.BackendFailure(reason))
                        val fallbackMsg = "Connection issue. Trying backup provider."
                        avatarOverlay?.updateMessage(fallbackMsg)
                        speechOutputArbiter?.speakText(fallbackMsg, flush = true)
                    }

                    override fun onUiAcknowledgement(text: String) {
                        avatarOverlay?.updateMessage(text)
                        speechOutputArbiter?.speakText(text, flush = true)
                    }

                    override fun onConfirmationRequired(prompt: String) {
                        voiceStateMachine.onEvent(VoiceSessionEvent.ConfirmationRequested(prompt))
                        avatarOverlay?.updateMessage(prompt)
                        speechOutputArbiter?.speakText(prompt, flush = true)
                    }

                    override fun onAutomationStarted(action: String) {
                        voiceStateMachine.onEvent(VoiceSessionEvent.ActionExecutionStarted(action))
                    }

                    override fun onAutomationFinished(result: com.aria.assistant.automation.AutomationExecutionResult) {
                        voiceStateMachine.onEvent(
                            VoiceSessionEvent.ActionExecutionFinished(
                                action = "live_automation",
                                success = result.executed > 0 && result.blocked == 0
                            )
                        )
                        val summary = result.summary.ifBlank { "Automation completed." }
                        avatarOverlay?.updateMessage(summary)
                        speechOutputArbiter?.speakText(summary, flush = true)
                    }
                }
            )
        }

        runCatching { sttGateway?.start() }
            .onFailure {
                AuditLogger.log(this, "stt_start_error:${it.javaClass.simpleName}")
                val snapshot = sttHealthTracker.onRecoverableError()
                applySttHealthSnapshot(snapshot)
                sttAvailabilityStatus = "start_error"
                publishSttDebugStatus()
                if (snapshot.shouldScheduleRetry) {
                    scheduleSttRetry(snapshot.cooldownMs, "initial_start_error")
                }
            }

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
            AuditLogger.log(this, "live_backend:provider_fallback_only")
            avatarOverlay?.updateMessage("Live provider fallback active")
        }

        if (!wsEnabled && memoriesEnabledForSession && !visionEnabled) {
            AuditLogger.log(this, "memories_only_disabled:vision_required")
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
                    if (!canRenderAssistantAudio()) {
                        AuditLogger.log(this, "audio_chunk_drop:audio_focus_suppressed")
                    } else {
                        val now = System.currentTimeMillis()
                        lastAudioChunkAt = now
                        wsLastInboundAt = now
                        assistantOutputTickAt = now
                        dialogOrchestrator?.onWsAssistantActivity()
                        bargeInController.markAssistantOutputStarted(now)
                        voiceStateMachine.onEvent(VoiceSessionEvent.AssistantAudioChunk(source = "ws_binary"))
                        speechOutputArbiter?.playPcmChunk(chunk)
                    }
                },
                onTextEvent = { textEvent ->
                    AuditLogger.log(this, "ws_text:${textEvent.take(40)}")
                    val low = textEvent.lowercase()
                    if (!low.startsWith("ws_open") && !low.startsWith("ws_ping") && !low.startsWith("ws_pong")) {
                        wsLastInboundAt = System.currentTimeMillis()
                    }
                    val speakable = extractSpeakableText(textEvent)
                    if (!speakable.isNullOrBlank()) {
                        dialogOrchestrator?.onWsAssistantActivity()
                        voiceStateMachine.onEvent(VoiceSessionEvent.LlmResponseChunk(speakable))
                        avatarOverlay?.updateMessage(speakable)
                        if (System.currentTimeMillis() - lastAudioChunkAt > 1400L) {
                            if (duckAssistantAudioByFocus) {
                                AuditLogger.log(this, "audio_focus:duck_skip_local_tts")
                            } else if (!canRenderAssistantAudio()) {
                                AuditLogger.log(this, "audio_focus:suppress_local_tts")
                            } else {
                                speechOutputArbiter?.speakText(speakable)
                            }
                        }
                    }
                    onProactiveTextEvent?.invoke(textEvent)
                },
                onClosed = { reason ->
                    AuditLogger.log(this, reason)
                    voiceStateMachine.onEvent(VoiceSessionEvent.BackendFailure(reason))
                    val low = reason.lowercase()
                    if (
                        low.contains("1002") ||
                        low.contains("protocol error") ||
                        low.contains("ws_failure")
                    ) {
                        wsProtocolBroken = true
                        wsForceMemoriesFallback = true
                        if (memoriesEnabledForSession) {
                            AuditLogger.log(this, "ws_failure:fallback_to_memories")
                            if (!visionEnabled) {
                                AuditLogger.log(this, "memories_fallback_blocked:vision_off")
                                avatarOverlay?.updateMessage("Turn ON Live Vision for Memories fallback")
                                speechOutputArbiter?.speakText("Turn on live vision for memories fallback", flush = true)
                            }
                        } else {
                            AuditLogger.log(this, "ws_failure:fallback_to_provider")
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
            AuditLogger.log(this, "live_ws_disabled:using_provider_fallback")
        }

        serviceScope.launch {
            while (running && isActive) {
                if (!ConsentStore.isLiveEnabled(this@LiveModeService)) {
                    AuditLogger.log(this@LiveModeService, "live_auto_stop:consent_revoked")
                    cancelPendingSttRetry("consent_revoked")
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
                        cancelPendingSttRetry("session_expired")
                        stopSelf()
                        break
                    }
                }

                val voiceFrame = recorder?.readVoiceFrameOrNull()
                val now = System.currentTimeMillis()
                val voicedChunk = voiceFrame?.voicedChunk
                sttGateway?.onVoiceActivity(voiceFrame?.speechActive == true)

                if (voiceFrame != null) {
                    if (voiceFrame.speechStarted) {
                        lastUserSpeechAt = now
                        if (!userSpeechActive) {
                            userSpeechActive = true
                            voiceStateMachine.onEvent(VoiceSessionEvent.UserSpeechDetected)
                        }
                    }

                    if (voiceFrame.speechActive) {
                        lastUserSpeechAt = now
                        if (!userSpeechActive) {
                            userSpeechActive = true
                            voiceStateMachine.onEvent(VoiceSessionEvent.UserSpeechDetected)
                        }
                    }

                    if (voiceFrame.speechEnded) {
                        if (userSpeechActive) {
                            userSpeechActive = false
                            voiceStateMachine.onEvent(VoiceSessionEvent.UserSpeechEnded)
                        }
                    }

                    if (voiceFrame.usedFallbackVad) {
                        if (voicedChunk != null) {
                            lastUserSpeechAt = now
                            if (!userSpeechActive) {
                                userSpeechActive = true
                                voiceStateMachine.onEvent(VoiceSessionEvent.UserSpeechDetected)
                            }
                        } else if (userSpeechActive && now - lastUserSpeechAt > 700L) {
                            userSpeechActive = false
                            voiceStateMachine.onEvent(VoiceSessionEvent.UserSpeechEnded)
                        }
                    }
                } else if (userSpeechActive && now - lastUserSpeechAt > 700L) {
                    userSpeechActive = false
                    voiceStateMachine.onEvent(VoiceSessionEvent.UserSpeechEnded)
                }

                if (voicedChunk != null) {
                    val decision = bargeInController.evaluateUserSpeechInterruption(
                        currentState = voiceStateMachine.currentState,
                        nowMs = now
                    )
                    if (decision.shouldInterrupt) {
                        interruptAssistantSpeech(decision.reason)
                    }

                    val shouldSendAudioToWs = wsClient != null && ConsentStore.getLiveBackendMode(this@LiveModeService) == "ws"
                    if (shouldSendAudioToWs) {
                        val sent = wsClient?.sendAudioPcm(voicedChunk) == true
                        if (sent) {
                            wsAudioSentCount += 1
                            AuditLogger.log(this@LiveModeService, "audio_chunk_sent")
                        } else {
                            AuditLogger.log(this@LiveModeService, "audio_chunk_drop:ws_not_ready")
                        }
                    }
                }

                speechOutputArbiter?.checkIdle(now)
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
                        AuditLogger.log(this@LiveModeService, "ws_no_reply:fallback_to_provider")
                        avatarOverlay?.updateMessage("WS no response, switching to provider fallback…")
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
                        wsForceMemoriesFallback = true
                        AuditLogger.log(this@LiveModeService, "ws_no_reply:force_provider_fallback")
                        avatarOverlay?.updateMessage("WS no response. Provider fallback active.")
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

    private fun handleSttTranscriptEvent(event: SttTranscriptEvent) {
        when (event) {
            SttTranscriptEvent.ListeningStarted -> {
                AuditLogger.log(this, "stt_listening_started")
                sttAvailabilityStatus = "listening"
                publishSttDebugStatus()
            }

            SttTranscriptEvent.ListeningStopped -> {
                AuditLogger.log(this, "stt_listening_stopped")
                sttAvailabilityStatus = "stopped"
                publishSttDebugStatus()
            }

            is SttTranscriptEvent.Partial -> {
                activeVoiceMode = ConsentStore.getLiveVoiceMode(this)
                if (activeVoiceMode == "push_to_talk" && !pushToTalkPressed) {
                    return
                }

                val text = event.text.trim().take(260)
                if (text.isBlank()) return
                if (text.equals(lastSttPartialText, ignoreCase = true)) return

                val now = System.currentTimeMillis()
                lastSttPartialText = text
                lastUserSpeechAt = now
                sttLastSuccessAtMs = now
                applySttHealthSnapshot(sttHealthTracker.resetOnSuccess(now))
                sttAvailabilityStatus = "active"
                if (sttRetryJob?.isActive == true) {
                    cancelPendingSttRetry("stt_partial_recovered")
                    AuditLogger.log(this, "stt_health:recovered:partial")
                }
                if (!userSpeechActive) {
                    userSpeechActive = true
                    voiceStateMachine.onEvent(VoiceSessionEvent.UserSpeechDetected)
                }
                voiceStateMachine.onEvent(VoiceSessionEvent.SttPartial(text))
                avatarOverlay?.updateMessage("You: $text")
                AuditLogger.log(this, "stt_partial:${text.take(60)}")
                publishSttDebugStatus()
            }

            is SttTranscriptEvent.Final -> {
                activeVoiceMode = ConsentStore.getLiveVoiceMode(this)
                if (activeVoiceMode == "push_to_talk" && !pushToTalkPressed) {
                    return
                }

                val text = event.text.trim().take(320)
                if (text.isBlank()) return

                val now = System.currentTimeMillis()
                lastSttPartialText = ""
                lastUserSpeechAt = now
                sttLastSuccessAtMs = now
                applySttHealthSnapshot(sttHealthTracker.resetOnSuccess(now))
                sttAvailabilityStatus = "active"
                if (sttRetryJob?.isActive == true) {
                    cancelPendingSttRetry("stt_final_recovered")
                    AuditLogger.log(this, "stt_health:recovered:final")
                }
                if (!userSpeechActive) {
                    userSpeechActive = true
                    voiceStateMachine.onEvent(VoiceSessionEvent.UserSpeechDetected)
                }
                voiceStateMachine.onEvent(VoiceSessionEvent.SttFinal(text))
                val wsPreferred = wsClient != null && !wsForceMemoriesFallback && !wsProtocolBroken
                dialogOrchestrator?.handleFinalTranscript(
                    transcript = text,
                    voiceMode = activeVoiceMode,
                    wsPreferred = wsPreferred,
                    wsSender = { _, spokenText ->
                        val payload = JSONObject()
                            .put("type", "user_text")
                            .put("text", spokenText)
                            .put("mode", activeVoiceMode)
                            .toString()
                        wsClient?.sendText(payload) == true
                    }
                )
                userSpeechActive = false
                if (activeVoiceMode == "push_to_talk") {
                    pushToTalkPressed = false
                }
                AuditLogger.log(this, "stt_final:${text.take(80)}")
                publishSttDebugStatus()
            }

            is SttTranscriptEvent.Error -> {
                AuditLogger.log(this, "stt_error:${event.reason}:${event.code}")
                if (event.recoverable) {
                    val snapshot = sttHealthTracker.onRecoverableError()
                    applySttHealthSnapshot(snapshot)
                    sttAvailabilityStatus = "degraded"
                    voiceStateMachine.onEvent(VoiceSessionEvent.RecoverableWarning("stt_error_${event.reason}"))
                    publishSttDebugStatus()
                    if (snapshot.shouldScheduleRetry) {
                        scheduleSttRetry(snapshot.cooldownMs, "recoverable_${event.reason}")
                    }
                } else {
                    val snapshot = sttHealthTracker.onUnrecoverableError()
                    applySttHealthSnapshot(snapshot)
                    sttAvailabilityStatus = "unavailable"
                    voiceStateMachine.onEvent(VoiceSessionEvent.RecoverableWarning("stt_unavailable_${event.reason}"))
                    publishSttDebugStatus()
                    scheduleSttRetry(snapshot.cooldownMs, "unrecoverable_${event.reason}")
                }
            }

            SttTranscriptEvent.Timeout -> {
                AuditLogger.log(this, "stt_timeout")
                val snapshot = sttHealthTracker.onTimeout()
                applySttHealthSnapshot(snapshot)
                sttAvailabilityStatus = "timeout"
                if (userSpeechActive) {
                    userSpeechActive = false
                    voiceStateMachine.onEvent(VoiceSessionEvent.UserSpeechEnded)
                }
                voiceStateMachine.onEvent(VoiceSessionEvent.RecoverableWarning("stt_timeout"))
                publishSttDebugStatus()
                if (snapshot.shouldScheduleRetry) {
                    scheduleSttRetry(snapshot.cooldownMs, "timeout_pattern")
                }
            }

            SttTranscriptEvent.Unavailable -> {
                AuditLogger.log(this, "stt_unavailable")
                val snapshot = sttHealthTracker.onUnavailable()
                applySttHealthSnapshot(snapshot)
                sttAvailabilityStatus = "unavailable"
                voiceStateMachine.onEvent(VoiceSessionEvent.RecoverableWarning("stt_unavailable"))
                publishSttDebugStatus()
                scheduleSttRetry(snapshot.cooldownMs, "gateway_unavailable")
            }
        }
    }

    private fun scheduleSttRetry(delayMs: Long, reason: String) {
        if (!running) return

        val boundedDelayMs = delayMs.coerceIn(1500L, 60_000L)
        val existing = sttRetryJob
        if (existing?.isActive == true) {
            AuditLogger.log(this, "stt_retry:already_scheduled:$reason")
            publishSttDebugStatus()
            return
        }

        sttLastRetryDelayMs = boundedDelayMs
        sttAvailabilityStatus = "retry_scheduled"
        AuditLogger.log(this, "stt_retry:scheduled:${boundedDelayMs}ms:$reason")
        val job = serviceScope.launch {
            publishSttDebugStatus(retryActiveOverride = true)
            delay(boundedDelayMs)
            if (!running || !ConsentStore.isLiveEnabled(this@LiveModeService)) {
                sttAvailabilityStatus = "retry_aborted"
                publishSttDebugStatus(retryActiveOverride = false)
                AuditLogger.log(this@LiveModeService, "stt_retry:aborted:not_running")
                return@launch
            }

            sttAvailabilityStatus = "retry_trigger"
            publishSttDebugStatus(retryActiveOverride = false)
            AuditLogger.log(this@LiveModeService, "stt_retry:trigger:$reason")
            runCatching { sttGateway?.start() }
                .onFailure {
                    AuditLogger.log(this@LiveModeService, "stt_retry:start_failed:${it.javaClass.simpleName}")
                    val snapshot = sttHealthTracker.onRecoverableError()
                    applySttHealthSnapshot(snapshot)
                    sttAvailabilityStatus = "retry_start_failed"
                    publishSttDebugStatus(retryActiveOverride = false)
                    if (snapshot.shouldScheduleRetry) {
                        sttRetryJob = null
                        scheduleSttRetry(snapshot.cooldownMs, "retry_start_failed")
                    }
                }
        }
        sttRetryJob = job
        publishSttDebugStatus(retryActiveOverride = true)
        job.invokeOnCompletion {
            if (sttRetryJob == job) {
                sttRetryJob = null
            }
            publishSttDebugStatus(retryActiveOverride = false)
        }
    }

    private fun cancelPendingSttRetry(reason: String) {
        val existing = sttRetryJob
        if (existing?.isActive == true) {
            AuditLogger.log(this, "stt_retry:cancel:$reason")
            existing.cancel()
        }
        sttRetryJob = null
        publishSttDebugStatus(retryActiveOverride = false)
    }

    private fun applySttHealthSnapshot(snapshot: SttHealthSnapshot) {
        sttFailureCount = snapshot.consecutiveFailures
        sttTimeoutCount = snapshot.consecutiveTimeouts
        sttUnavailableCount = snapshot.consecutiveUnavailable
    }

    private fun publishSttDebugStatus(
        retryActiveOverride: Boolean? = null,
        voiceStateOverride: String? = null
    ) {
        val voiceState = voiceStateOverride
            ?: if (this::voiceStateMachine.isInitialized) {
                voiceStateMachine.currentState.name.lowercase()
            } else {
                "idle"
            }
        val retryActive = retryActiveOverride ?: (sttRetryJob?.isActive == true)
        LiveSttDebugStore.write(
            this,
            SttDebugStatus(
                availabilityStatus = sttAvailabilityStatus,
                failureCount = sttFailureCount,
                timeoutCount = sttTimeoutCount,
                unavailableCount = sttUnavailableCount,
                lastRetryDelayMs = sttLastRetryDelayMs,
                retryActive = retryActive,
                lastSuccessAtMs = sttLastSuccessAtMs,
                voiceState = voiceState,
                updatedAtMs = System.currentTimeMillis()
            )
        )
    }

    private fun canRenderAssistantAudio(): Boolean {
        if (suppressAssistantAudioByFocus) return false

        if (!this::audioFocusArbiter.isInitialized) return true
        if (audioFocusArbiter.isOutputSuppressed()) return false

        return if (audioFocusArbiter.isFocusHeld()) {
            true
        } else {
            val ok = audioFocusArbiter.ensureFocusForOutput()
            if (!ok) {
                voiceStateMachine.onEvent(
                    VoiceSessionEvent.RecoverableWarning("audio_focus_not_granted_for_output")
                )
            }
            ok
        }
    }

    private fun interruptAssistantSpeech(reason: String) {
        AuditLogger.log(this, "barge_in:interrupt:$reason")
        avatarOverlay?.setSpeaking(false)
        avatarOverlay?.updateMessage("Interrupted. Listening...")

        // TODO: If upstream WS protocol supports explicit cancel event, replace this payload.
        runCatching { wsClient?.sendText("{\"type\":\"assistant_interrupt\"}") }
        voiceStateMachine.onEvent(VoiceSessionEvent.UserInterruptedAssistant(reason))
        dialogOrchestrator?.onUserInterrupted()
        speechOutputArbiter?.stopNow(reason)

        assistantOutputTickAt = 0L
        bargeInController.markAssistantOutputStopped()
        bargeInController.markInterrupted()
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
                if (duckAssistantAudioByFocus) {
                    AuditLogger.log(this@LiveModeService, "audio_focus:duck_skip_memories_tts")
                    return
                }
                if (!canRenderAssistantAudio()) {
                    AuditLogger.log(this@LiveModeService, "audio_focus:suppress_memories_tts")
                    return
                }
                lastMemoriesNormalizedText = normalized
                lastMemoriesSpeechAt = now
                speechOutputArbiter?.speakText(trimmed)
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
