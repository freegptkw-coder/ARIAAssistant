package com.aria.assistant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.*

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {
    
    private lateinit var chatContainer: LinearLayout
    private lateinit var chatScrollView: ScrollView
    private lateinit var messageInput: EditText
    private lateinit var voiceButton: MaterialButton
    private lateinit var sendButton: MaterialButton
    private lateinit var settingsButton: MaterialButton
    
    private lateinit var speechRecognizer: SpeechRecognizer
    private lateinit var tts: TextToSpeech
    private var ttsReady = false
    
    private lateinit var lettaService: LettaApiService
    private val rootExecutor = RootCommandExecutor()
    
    private val RECORD_AUDIO_PERMISSION = 100
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        lettaService = LettaApiService(this)
        
        // Initialize views
        chatContainer = findViewById(R.id.chatContainer)
        chatScrollView = findViewById(R.id.chatScrollView)
        messageInput = findViewById(R.id.messageInput)
        voiceButton = findViewById(R.id.voiceButton)
        sendButton = findViewById(R.id.sendButton)
        settingsButton = findViewById(R.id.settingsButton)
        
        // Initialize TTS
        tts = TextToSpeech(this, this)
        
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
        
        // Welcome message
        addAssistantMessage("Hello! I'm ARIA Assistant. How can I help you?")
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
                Toast.makeText(this@MainActivity, "Voice recognition error", Toast.LENGTH_SHORT).show()
            }
            
            override fun onResults(results: Bundle?) {
                voiceButton.text = "🎤"
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (matches != null && matches.isNotEmpty()) {
                    val text = matches[0]
                    messageInput.setText(text)
                    sendMessage(text)
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
            if (ttsReady) {
                tts.speak(commandResponse, TextToSpeech.QUEUE_FLUSH, null, null)
            }
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
                    
                    // Speak response
                    if (ttsReady) {
                        tts.speak(response.text, TextToSpeech.QUEUE_FLUSH, null, null)
                    }
                    
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
        CoroutineScope(Dispatchers.IO).launch {
            val result = rootExecutor.execute(command)
            withContext(Dispatchers.Main) {
                if (result.isNotEmpty()) {
                    addSystemMessage("Command executed: $result")
                }
            }
        }
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
    
    override fun onDestroy() {
        super.onDestroy()
        tts.shutdown()
        speechRecognizer.destroy()
    }
}
