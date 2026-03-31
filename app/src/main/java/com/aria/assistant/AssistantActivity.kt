package com.aria.assistant

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.util.*

class AssistantActivity : AppCompatActivity(), TextToSpeech.OnInitListener {
    
    private lateinit var chatContainer: LinearLayout
    private lateinit var chatScrollView: ScrollView
    private lateinit var messageInput: EditText
    private lateinit var voiceButton: MaterialButton
    private lateinit var stopSpeakButton: MaterialButton
    private lateinit var sendButton: MaterialButton
    private lateinit var settingsButton: MaterialButton
    private lateinit var voiceStatusText: TextView
    
    private lateinit var speechRecognizer: SpeechRecognizer
    private lateinit var tts: TextToSpeech
    private var ttsReady = false
    private var mediaPlayer: MediaPlayer? = null
    private val speechQueue: ArrayDeque<String> = ArrayDeque()
    private var isSpeakingNow = false
    private var currentSpeakJob: Job? = null
    private val healthHandler = Handler(Looper.getMainLooper())
    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            AppHealthMonitor.markAlive(this@AssistantActivity)
            healthHandler.postDelayed(this, 60_000)
        }
    }
    
    private lateinit var lettaService: LettaApiService
    private val rootExecutor = RootCommandExecutor()
    
    private val RECORD_AUDIO_PERMISSION = 100
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_assistant)
        
        lettaService = LettaApiService(this)
        AppHealthMonitor.installCrashHandler(applicationContext)
        AppHealthMonitor.markAppStart(this)
        
        // Initialize views
        chatContainer = findViewById(R.id.chatContainer)
        chatScrollView = findViewById(R.id.chatScrollView)
        messageInput = findViewById(R.id.messageInput)
        voiceButton = findViewById(R.id.voiceButton)
        stopSpeakButton = findViewById(R.id.stopSpeakButton)
        sendButton = findViewById(R.id.sendButton)
        settingsButton = findViewById(R.id.settingsButton)
        voiceStatusText = findViewById(R.id.voiceStatusText)

        AppHealthMonitor.consumeLastCrashSummary(this)?.let {
            addSystemMessage("Recovered from previous crash: $it")
        }
        
        // Initialize TTS
        tts = TextToSpeech(this, this)
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { runOnUiThread { onSpeechFinished() } }
            override fun onError(utteranceId: String?) { runOnUiThread { onSpeechFinished() } }
        })
        
        // Initialize Speech Recognizer
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        setupSpeechRecognizer()
        
        // Check permissions
        checkPermissions()
        
        // Set up button listeners
        sendButton.setOnClickListener {
            val message = messageInput.text.toString()
            if (message.isNotEmpty()) {
                sendMessage(message)
                messageInput.text.clear()
            }
        }
        
        settingsButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        
        voiceButton.setOnClickListener {
            startVoiceRecognition()
        }

        stopSpeakButton.setOnClickListener {
            stopCurrentSpeech()
        }
        
        // Welcome message
        addAssistantMessage("Hello! I'm ARIA Assistant. How can I help you?")

        // Health heartbeat
        healthHandler.postDelayed(heartbeatRunnable, 60_000)
    }
    
    private fun checkPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) 
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                RECORD_AUDIO_PERMISSION
            )
        }
    }
    
    private fun setupSpeechRecognizer() {
        speechRecognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                voiceButton.text = "🔴"
            }
            
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {
                voiceButton.text = "🎤"
            }
            
            override fun onError(error: Int) {
                voiceButton.text = "🎤"
                Toast.makeText(this@AssistantActivity, "Voice recognition error", Toast.LENGTH_SHORT).show()
            }
            
            override fun onResults(results: Bundle?) {
                voiceButton.text = "🎤"
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (matches != null && matches.isNotEmpty()) {
                    val rawText = matches[0]
                    val prefs = getSharedPreferences("ARIA_PREFS", Context.MODE_PRIVATE)
                    val wakeWordEnabled = prefs.getBoolean("wake_word_enabled", true)

                    var finalText = rawText
                    if (wakeWordEnabled) {
                        val lower = rawText.lowercase(Locale.getDefault())
                        val hasWakeWord = lower.contains("hey aria") || lower.contains("hi aria") || lower.startsWith("aria")
                        if (!hasWakeWord) {
                            Toast.makeText(this@AssistantActivity, "Wake word on: bolo 'Hey ARIA'", Toast.LENGTH_SHORT).show()
                            return
                        }
                        finalText = rawText
                            .replace(Regex("(?i)^\\s*(hey|hi)?\\s*aria[,! ]*"), "")
                            .trim()
                        if (finalText.isBlank()) {
                            Toast.makeText(this@AssistantActivity, "Bolo: Hey ARIA, then command", Toast.LENGTH_SHORT).show()
                            return
                        }
                    }

                    messageInput.setText(finalText)
                    sendMessage(finalText)
                }
            }
            
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
    }
    
    private fun startVoiceRecognition() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak now...")
        }
        speechRecognizer.startListening(intent)
    }
    
    private fun sendMessage(message: String) {
        // Add user message to chat
        addUserMessage(message)
        
        // Check for voice commands first
        val voiceCommand = VoiceCommandParser.parseCommand(message)
        
        if (voiceCommand.shouldExecute && voiceCommand.type != CommandType.NONE) {
            // Execute command and get response
            val commandResponse = VoiceCommandParser.executeCommand(this, voiceCommand)
            addAssistantMessage(commandResponse)
            
            // Also speak it
            enqueueSpeech(commandResponse)
            return
        }
        
        // Show processing indicator
        addAssistantMessage("Processing...")
        
        // Send to AI API
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val response = lettaService.sendMessage(message)
                
                withContext(Dispatchers.Main) {
                    // Remove "Processing..." message
                    if (chatContainer.childCount > 0) {
                        chatContainer.removeViewAt(chatContainer.childCount - 1)
                    }
                    
                    // Add assistant response
                    addAssistantMessage(response.text)
                    
                    // Speak response with selected provider
                    enqueueSpeech(response.text)
                    
                    // Execute root command if present
                    response.rootCommand?.let { command ->
                        executeRootCommand(command)
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    // Remove "Processing..." message
                    if (chatContainer.childCount > 0) {
                        chatContainer.removeViewAt(chatContainer.childCount - 1)
                    }
                    addAssistantMessage("Error: ${e.message}")
                }
            }
        }
    }
    
    private fun executeRootCommand(command: String) {
        val prefs = getSharedPreferences("ARIA_PREFS", Context.MODE_PRIVATE)
        val safeGuardEnabled = prefs.getBoolean("safe_root_guard", true)

        if (safeGuardEnabled && isDangerousRootCommand(command)) {
            AlertDialog.Builder(this)
                .setTitle("⚠️ Dangerous root command")
                .setMessage("Command:\n$command\n\nRun anyway?")
                .setNegativeButton("Cancel") { d, _ ->
                    d.dismiss()
                    addSystemMessage("Blocked dangerous command")
                }
                .setPositiveButton("Run") { _, _ ->
                    runRootCommand(command)
                }
                .show()
        } else {
            runRootCommand(command)
        }
    }

    private fun runRootCommand(command: String) {
        CoroutineScope(Dispatchers.IO).launch {
            val result = rootExecutor.execute(command)
            withContext(Dispatchers.Main) {
                if (result.isNotEmpty()) {
                    addSystemMessage("Command executed: $result")
                }
            }
        }
    }

    private fun isDangerousRootCommand(command: String): Boolean {
        val lower = command.lowercase(Locale.getDefault())
        val riskyPatterns = listOf(
            "rm -rf /",
            "mkfs",
            "dd if=",
            "reboot",
            "shutdown",
            "setenforce 0",
            "chmod 777 /system",
            "wipe",
            "format"
        )
        return riskyPatterns.any { lower.contains(it) }
    }
    
    private fun addUserMessage(text: String) {
        val textView = TextView(this).apply {
            this.text = text
            textSize = 16f
            setTextColor(ContextCompat.getColor(context, R.color.white))
            setBackgroundColor(ContextCompat.getColor(context, R.color.message_user))
            setPadding(24, 16, 24, 16)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.END
                setMargins(100, 8, 8, 8)
            }
        }
        chatContainer.addView(textView)
        scrollToBottom()
    }
    
    private fun addAssistantMessage(text: String) {
        val textView = TextView(this).apply {
            this.text = text
            textSize = 16f
            setTextColor(ContextCompat.getColor(context, R.color.white))
            setBackgroundColor(ContextCompat.getColor(context, R.color.message_assistant))
            setPadding(24, 16, 24, 16)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.START
                setMargins(8, 8, 100, 8)
            }
        }
        chatContainer.addView(textView)
        scrollToBottom()
    }
    
    private fun addSystemMessage(text: String) {
        val textView = TextView(this).apply {
            this.text = text
            textSize = 12f
            setTextColor(ContextCompat.getColor(context, android.R.color.darker_gray))
            gravity = Gravity.CENTER
            setPadding(16, 8, 16, 8)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(8, 4, 8, 4)
            }
        }
        chatContainer.addView(textView)
        scrollToBottom()
    }
    
    private fun scrollToBottom() {
        chatScrollView.post {
            chatScrollView.fullScroll(ScrollView.FOCUS_DOWN)
        }
    }
    
    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            ttsReady = true
            tts.language = Locale.US
        }
    }
    


    private fun enqueueSpeech(text: String) {
        if (text.isBlank()) return
        speechQueue.addLast(text)
        if (!isSpeakingNow) processSpeechQueue()
    }

    private fun processSpeechQueue() {
        if (isSpeakingNow || speechQueue.isEmpty()) return
        val next = speechQueue.removeFirst()
        isSpeakingNow = true
        speakWithVoiceProvider(next)
    }

    private fun onSpeechFinished() {
        isSpeakingNow = false
        setVoiceStatus("🔇 Idle")
        processSpeechQueue()
    }

    private fun setVoiceStatus(status: String) {
        voiceStatusText.text = status
    }

    private fun canUseElevenLabs(charCount: Int): Boolean {
        val prefs = getSharedPreferences("ARIA_PREFS", Context.MODE_PRIVATE)
        val cal = Calendar.getInstance()
        val monthKey = "${cal.get(Calendar.YEAR)}-${cal.get(Calendar.MONTH)}"
        val savedMonth = prefs.getString("elevenlabs_month", monthKey)
        var used = prefs.getInt("elevenlabs_chars_used", 0)

        if (savedMonth != monthKey) {
            used = 0
            prefs.edit().putString("elevenlabs_month", monthKey).putInt("elevenlabs_chars_used", 0).apply()
        }

        return used + charCount <= 9800
    }

    private fun addElevenLabsUsage(charCount: Int) {
        val prefs = getSharedPreferences("ARIA_PREFS", Context.MODE_PRIVATE)
        val used = prefs.getInt("elevenlabs_chars_used", 0)
        prefs.edit().putInt("elevenlabs_chars_used", used + charCount).apply()
    }

    private fun stopCurrentSpeech() {
        currentSpeakJob?.cancel()
        mediaPlayer?.stop()
        mediaPlayer?.release()
        mediaPlayer = null
        if (ttsReady) tts.stop()
        speechQueue.clear()
        isSpeakingNow = false
        setVoiceStatus("⏹ Stopped")
    }

    private fun speakWithVoiceProvider(text: String) {
        val prefs = getSharedPreferences("ARIA_PREFS", Context.MODE_PRIVATE)
        val ttsProvider = prefs.getString("tts_provider", "android") ?: "android"
        val voiceId = prefs.getString("voice_id", "android_default") ?: "android_default"
        val voiceApiKey = SecurePrefs.getDecryptedString(this, "ARIA_PREFS", "voice_api_key_enc", "voice_api_key")

        when (ttsProvider) {
            "android" -> {
                setVoiceStatus("🤖 Android speaking...")
                if (ttsReady) {
                    val utteranceId = "aria_${System.currentTimeMillis()}"
                    tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
                } else {
                    onSpeechFinished()
                }
            }
            "elevenlabs", "cartesia" -> {
                if (voiceApiKey.isEmpty()) {
                    setVoiceStatus("⚠️ Voice key missing, fallback")
                    if (ttsReady) {
                        val utteranceId = "aria_${System.currentTimeMillis()}"
                        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
                    } else {
                        onSpeechFinished()
                    }
                    return
                }

                if (ttsProvider == "elevenlabs" && !canUseElevenLabs(text.length)) {
                    setVoiceStatus("⚠️ ElevenLabs limit near, Android fallback")
                    if (ttsReady) {
                        val utteranceId = "aria_${System.currentTimeMillis()}"
                        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
                    } else {
                        onSpeechFinished()
                    }
                    return
                }

                setVoiceStatus("🎙️ Generating voice...")
                currentSpeakJob = CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val provider = if (ttsProvider == "elevenlabs") TTSProvider.ELEVENLABS else TTSProvider.CARTESIA
                        var audioFile: File? = null

                        for (attempt in 1..2) {
                            audioFile = TTSProviders.generateSpeech(this@AssistantActivity, text, provider, voiceId, voiceApiKey)
                            if (audioFile != null && audioFile.exists()) break
                            if (attempt < 2) delay(700)
                        }

                        if (audioFile != null && audioFile.exists()) {
                            if (ttsProvider == "elevenlabs") addElevenLabsUsage(text.length)
                            withContext(Dispatchers.Main) {
                                setVoiceStatus("🔊 Playing premium voice...")
                                playAudioFile(audioFile, text)
                            }
                        } else {
                            withContext(Dispatchers.Main) {
                                setVoiceStatus("⚠️ Premium failed, Android fallback")
                                if (ttsReady) {
                                    val utteranceId = "aria_${System.currentTimeMillis()}"
                                    tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
                                } else {
                                    onSpeechFinished()
                                }
                            }
                        }
                    } catch (_: Exception) {
                        withContext(Dispatchers.Main) {
                            setVoiceStatus("⚠️ Voice error, Android fallback")
                            if (ttsReady) {
                                val utteranceId = "aria_${System.currentTimeMillis()}"
                                tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
                            } else {
                                onSpeechFinished()
                            }
                        }
                    }
                }
            }
            else -> {
                setVoiceStatus("🤖 Android speaking...")
                if (ttsReady) {
                    val utteranceId = "aria_${System.currentTimeMillis()}"
                    tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
                } else {
                    onSpeechFinished()
                }
            }
        }
    }

    private fun playAudioFile(audioFile: File, fallbackText: String) {
        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()

            mediaPlayer = MediaPlayer().apply {
                setDataSource(audioFile.absolutePath)
                prepare()
                start()
                setOnCompletionListener {
                    release()
                    mediaPlayer = null
                    audioFile.delete()
                    onSpeechFinished()
                }
                setOnErrorListener { _, _, _ ->
                    release()
                    mediaPlayer = null
                    audioFile.delete()
                    if (ttsReady) {
                        val utteranceId = "aria_${System.currentTimeMillis()}"
                        tts.speak(fallbackText, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
                    } else {
                        onSpeechFinished()
                    }
                    true
                }
            }
        } catch (_: Exception) {
            audioFile.delete()
            if (ttsReady) {
                val utteranceId = "aria_${System.currentTimeMillis()}"
                tts.speak(fallbackText, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
            } else {
                onSpeechFinished()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        healthHandler.removeCallbacks(heartbeatRunnable)
        AppHealthMonitor.markCleanExit(this)
        currentSpeakJob?.cancel()
        mediaPlayer?.release()
        tts.stop()
        tts.shutdown()
        speechRecognizer.destroy()
    }
}
