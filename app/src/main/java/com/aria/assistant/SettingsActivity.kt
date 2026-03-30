package com.aria.assistant

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText

class SettingsActivity : AppCompatActivity() {
    
    private lateinit var prefs: SharedPreferences
    
    // Personality & Personal Info
    private lateinit var personalitySpinner: Spinner
    private lateinit var userNameInput: TextInputEditText
    private lateinit var nicknameInput: TextInputEditText
    
    // AI Provider
    private lateinit var providerSpinner: Spinner
    private lateinit var modelSpinner: Spinner
    private lateinit var apiKeyInput: TextInputEditText
    
    // Voice Provider (NEW)
    private lateinit var ttsProviderSpinner: Spinner
    private lateinit var voiceSpinner: Spinner
    private lateinit var voiceApiKeyInput: TextInputEditText
    
    // Other
    private lateinit var proactiveSwitch: SwitchMaterial
    private lateinit var themeSwitch: SwitchMaterial
    private lateinit var saveButton: MaterialButton
    
    private val personalities = arrayOf(
        "💕 Girlfriend (Sweet & Caring)",
        "😊 Friendly (Buddy)",
        "👔 Professional (Formal)",
        "🤖 Assistant (Default)"
    )
    private val personalityValues = arrayOf("girlfriend", "friendly", "professional", "assistant")
    
    private val providers = arrayOf("Groq (Fast & Free)", "OpenRouter", "Google Gemini", "Letta")
    private val providerValues = arrayOf("groq", "openrouter", "gemini", "letta")
    
    private val groqModels = arrayOf("llama-3.3-70b-versatile", "llama-3.1-8b-instant", "mixtral-8x7b-32768")
    private val openrouterModels = arrayOf("meta-llama/llama-3.3-70b-instruct", "anthropic/claude-3.5-sonnet", "google/gemini-pro-1.5")
    private val geminiModels = arrayOf("gemini-1.5-flash", "gemini-1.5-pro", "gemini-pro")
    private val lettaModels = arrayOf("default")
    
    // TTS Providers
    private val ttsProviders = arrayOf("🤖 Android Default", "💎 ElevenLabs", "⚡ Cartesia")
    private val ttsProviderValues = arrayOf("android", "elevenlabs", "cartesia")
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        
        supportActionBar?.title = "⚙️ ARIA Settings"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        
        prefs = getSharedPreferences("ARIA_PREFS", Context.MODE_PRIVATE)
        
        // Initialize views
        personalitySpinner = findViewById(R.id.personalitySpinner)
        userNameInput = findViewById(R.id.userNameInput)
        nicknameInput = findViewById(R.id.nicknameInput)
        providerSpinner = findViewById(R.id.providerSpinner)
        modelSpinner = findViewById(R.id.modelSpinner)
        apiKeyInput = findViewById(R.id.apiKeyInput)
        ttsProviderSpinner = findViewById(R.id.ttsProviderSpinner)
        voiceSpinner = findViewById(R.id.voiceSpinner)
        voiceApiKeyInput = findViewById(R.id.voiceApiKeyInput)
        proactiveSwitch = findViewById(R.id.proactiveSwitch)
        themeSwitch = findViewById(R.id.themeSwitch)
        saveButton = findViewById(R.id.saveButton)
        
        setupSpinners()
        loadSettings()
        
        saveButton.setOnClickListener { saveSettings() }
        themeSwitch.setOnCheckedChangeListener { _, isChecked ->
            AppCompatDelegate.setDefaultNightMode(
                if (isChecked) AppCompatDelegate.MODE_NIGHT_YES 
                else AppCompatDelegate.MODE_NIGHT_NO
            )
        }
    }
    
    private fun setupSpinners() {
        // Personality
        val personalityAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, personalities)
        personalityAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        personalitySpinner.adapter = personalityAdapter
        
        // AI Provider
        val providerAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, providers)
        providerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        providerSpinner.adapter = providerAdapter
        
        providerSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                updateModelSpinner(providerValues[position])
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        
        // TTS Provider
        val ttsAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, ttsProviders)
        ttsAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        ttsProviderSpinner.adapter = ttsAdapter
        
        ttsProviderSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                updateVoiceSpinner(ttsProviderValues[position])
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }
    
    private fun updateModelSpinner(provider: String) {
        val models = when(provider) {
            "groq" -> groqModels
            "openrouter" -> openrouterModels
            "gemini" -> geminiModels
            else -> lettaModels
        }
        
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, models)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        modelSpinner.adapter = adapter
        
        val savedModel = prefs.getString("model", models[0])
        val index = models.indexOf(savedModel)
        if (index >= 0) modelSpinner.setSelection(index)
    }
    
    private fun updateVoiceSpinner(ttsProvider: String) {
        val voices = when(ttsProvider) {
            "elevenlabs" -> TTSProviders.elevenLabsVoices.map { "${it.name} - ${it.description}" }.toTypedArray()
            "cartesia" -> TTSProviders.cartesiaVoices.map { "${it.name} - ${it.description}" }.toTypedArray()
            else -> arrayOf("Android Default")
        }
        
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, voices)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        voiceSpinner.adapter = adapter
        
        // Load saved voice
        val savedVoiceId = prefs.getString("voice_id", "")
        val allVoices = TTSProviders.getAllVoices()
        val savedVoice = allVoices.find { it.id == savedVoiceId }
        
        if (savedVoice != null) {
            val voiceList = when(ttsProvider) {
                "elevenlabs" -> TTSProviders.elevenLabsVoices
                "cartesia" -> TTSProviders.cartesiaVoices
                else -> TTSProviders.androidVoices
            }
            val index = voiceList.indexOf(savedVoice)
            if (index >= 0) voiceSpinner.setSelection(index)
        }
    }
    
    private fun loadSettings() {
        // Load personality
        val savedPersonality = prefs.getString("personality", "girlfriend") ?: "girlfriend"
        val persIndex = personalityValues.indexOf(savedPersonality)
        if (persIndex >= 0) personalitySpinner.setSelection(persIndex)
        
        // Load personal info
        userNameInput.setText(prefs.getString("user_name", ""))
        nicknameInput.setText(prefs.getString("nickname", ""))
        
        // Load AI provider
        val savedProvider = prefs.getString("ai_provider", "groq") ?: "groq"
        val provIndex = providerValues.indexOf(savedProvider)
        if (provIndex >= 0) providerSpinner.setSelection(provIndex)
        
        // Load API key
        apiKeyInput.setText(prefs.getString("api_key", ""))
        
        // Load TTS provider
        val savedTtsProvider = prefs.getString("tts_provider", "android") ?: "android"
        val ttsIndex = ttsProviderValues.indexOf(savedTtsProvider)
        if (ttsIndex >= 0) ttsProviderSpinner.setSelection(ttsIndex)
        
        // Load voice API key
        voiceApiKeyInput.setText(prefs.getString("voice_api_key", ""))
        
        // Load switches
        proactiveSwitch.isChecked = prefs.getBoolean("proactive_enabled", true)
        themeSwitch.isChecked = prefs.getBoolean("dark_mode", true)
        
        updateModelSpinner(savedProvider)
        updateVoiceSpinner(savedTtsProvider)
    }
    
    private fun saveSettings() {
        val personality = personalityValues[personalitySpinner.selectedItemPosition]
        val userName = userNameInput.text.toString()
        val nickname = nicknameInput.text.toString()
        val provider = providerValues[providerSpinner.selectedItemPosition]
        val model = modelSpinner.selectedItem.toString()
        val apiKey = apiKeyInput.text.toString()
        
        val ttsProvider = ttsProviderValues[ttsProviderSpinner.selectedItemPosition]
        val voiceApiKey = voiceApiKeyInput.text.toString()
        
        // Get selected voice ID
        val voiceList = when(ttsProvider) {
            "elevenlabs" -> TTSProviders.elevenLabsVoices
            "cartesia" -> TTSProviders.cartesiaVoices
            else -> TTSProviders.androidVoices
        }
        val selectedVoice = voiceList.getOrNull(voiceSpinner.selectedItemPosition)
        val voiceId = selectedVoice?.id ?: "android_default"
        val voiceName = selectedVoice?.name ?: "Android Default"
        
        // Validation
        if (apiKey.isEmpty()) {
            Toast.makeText(this, "⚠️ AI API Key required!", Toast.LENGTH_SHORT).show()
            return
        }
        
        if ((ttsProvider == "elevenlabs" || ttsProvider == "cartesia") && voiceApiKey.isEmpty()) {
            Toast.makeText(this, "⚠️ Voice API Key required for $ttsProvider!", Toast.LENGTH_LONG).show()
            return
        }
        
        // Save all settings
        prefs.edit().apply {
            putString("personality", personality)
            putString("user_name", userName)
            putString("nickname", nickname)
            putString("ai_provider", provider)
            putString("model", model)
            putString("api_key", apiKey)
            putString("tts_provider", ttsProvider)
            putString("voice_id", voiceId)
            putString("voice_name", voiceName)
            putString("voice_api_key", voiceApiKey)
            putBoolean("proactive_enabled", proactiveSwitch.isChecked)
            putBoolean("dark_mode", themeSwitch.isChecked)
            apply()
        }
        
        // Start/stop proactive service
        val serviceIntent = Intent(this, ProactiveService::class.java)
        if (proactiveSwitch.isChecked && personality == "girlfriend") {
            startService(serviceIntent)
        } else {
            stopService(serviceIntent)
        }
        
        val personalityName = personalities[personalitySpinner.selectedItemPosition]
        Toast.makeText(this, "✅ Settings saved!\nPersonality: $personalityName\nVoice: $voiceName", Toast.LENGTH_LONG).show()
        finish()
    }
    
    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
