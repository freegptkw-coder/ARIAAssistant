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
import com.aria.assistant.live.ConsentStore
import com.aria.assistant.live.LiveModeController
import com.aria.assistant.live.LiveSafetyActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

    // Voice Provider
    private lateinit var ttsProviderSpinner: Spinner
    private lateinit var voiceSpinner: Spinner
    private lateinit var customVoiceIdInput: TextInputEditText
    private lateinit var customVoiceNameInput: TextInputEditText
    private lateinit var voiceApiKeyInput: TextInputEditText
    private lateinit var voiceTestButton: MaterialButton

    // Speech recognition
    private lateinit var speechLanguageSpinner: Spinner

    // Other
    private lateinit var proactiveSwitch: SwitchMaterial
    private lateinit var themeSwitch: SwitchMaterial
    private lateinit var wakeWordSwitch: SwitchMaterial
    private lateinit var banglaModeSwitch: SwitchMaterial
    private lateinit var safeRootSwitch: SwitchMaterial
    private lateinit var liveModeSwitch: SwitchMaterial
    private lateinit var liveAlwaysOnSwitch: SwitchMaterial
    private lateinit var liveVisionSwitch: SwitchMaterial
    private lateinit var rootSafetyCenterButton: MaterialButton
    private lateinit var liveSafetyCenterButton: MaterialButton
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

    private val ttsProviders = arrayOf("🤖 Android Default", "💎 ElevenLabs", "⚡ Cartesia")
    private val ttsProviderValues = arrayOf("android", "elevenlabs", "cartesia")

    private val speechLanguageLabels = arrayOf("Auto (System)", "Bangla (bn-BD)", "English (en-US)")
    private val speechLanguageValues = arrayOf("auto", "bn-BD", "en-US")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        supportActionBar?.title = "⚙️ ARIA Settings"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        prefs = getSharedPreferences("ARIA_PREFS", Context.MODE_PRIVATE)

        personalitySpinner = findViewById(R.id.personalitySpinner)
        userNameInput = findViewById(R.id.userNameInput)
        nicknameInput = findViewById(R.id.nicknameInput)

        providerSpinner = findViewById(R.id.providerSpinner)
        modelSpinner = findViewById(R.id.modelSpinner)
        apiKeyInput = findViewById(R.id.apiKeyInput)

        ttsProviderSpinner = findViewById(R.id.ttsProviderSpinner)
        voiceSpinner = findViewById(R.id.voiceSpinner)
        customVoiceIdInput = findViewById(R.id.customVoiceIdInput)
        customVoiceNameInput = findViewById(R.id.customVoiceNameInput)
        voiceApiKeyInput = findViewById(R.id.voiceApiKeyInput)
        voiceTestButton = findViewById(R.id.voiceTestButton)

        speechLanguageSpinner = findViewById(R.id.speechLanguageSpinner)

        proactiveSwitch = findViewById(R.id.proactiveSwitch)
        themeSwitch = findViewById(R.id.themeSwitch)
        wakeWordSwitch = findViewById(R.id.wakeWordSwitch)
        banglaModeSwitch = findViewById(R.id.banglaModeSwitch)
        safeRootSwitch = findViewById(R.id.safeRootSwitch)
        liveModeSwitch = findViewById(R.id.liveModeSwitch)
        liveAlwaysOnSwitch = findViewById(R.id.liveAlwaysOnSwitch)
        liveVisionSwitch = findViewById(R.id.liveVisionSwitch)
        rootSafetyCenterButton = findViewById(R.id.rootSafetyCenterButton)
        liveSafetyCenterButton = findViewById(R.id.liveSafetyCenterButton)
        saveButton = findViewById(R.id.saveButton)

        setupSpinners()
        loadSettings()

        saveButton.setOnClickListener { saveSettings() }
        voiceTestButton.setOnClickListener { testVoiceConfig() }
        rootSafetyCenterButton.setOnClickListener {
            startActivity(Intent(this, RootSafetyActivity::class.java))
        }
        liveSafetyCenterButton.setOnClickListener {
            startActivity(Intent(this, LiveSafetyActivity::class.java))
        }

        themeSwitch.setOnCheckedChangeListener { _, isChecked ->
            AppCompatDelegate.setDefaultNightMode(
                if (isChecked) AppCompatDelegate.MODE_NIGHT_YES
                else AppCompatDelegate.MODE_NIGHT_NO
            )
        }
    }

    private fun setupSpinners() {
        personalitySpinner.adapter = spinnerAdapter(personalities)
        providerSpinner.adapter = spinnerAdapter(providers)
        ttsProviderSpinner.adapter = spinnerAdapter(ttsProviders)
        speechLanguageSpinner.adapter = spinnerAdapter(speechLanguageLabels)

        providerSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val providerId = providerValues[position]
                updateModelSpinner(providerId)
                loadProviderApiKey(providerId)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        ttsProviderSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                updateVoiceSpinner(ttsProviderValues[position])
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun spinnerAdapter(items: Array<String>): ArrayAdapter<String> {
        return ArrayAdapter(this, android.R.layout.simple_spinner_item, items).also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
    }

    private fun updateModelSpinner(provider: String) {
        val models = when (provider) {
            "groq" -> groqModels
            "openrouter" -> openrouterModels
            "gemini" -> geminiModels
            else -> lettaModels
        }

        modelSpinner.adapter = spinnerAdapter(models)

        val savedModel = prefs.getString("model", models[0])
        val index = models.indexOf(savedModel)
        if (index >= 0) modelSpinner.setSelection(index)
    }

    private fun updateVoiceSpinner(ttsProvider: String) {
        val voices = when (ttsProvider) {
            "elevenlabs" -> TTSProviders.elevenLabsVoices.map { "${it.name} - ${it.description}" }.toTypedArray()
            "cartesia" -> TTSProviders.cartesiaVoices.map { "${it.name} - ${it.description}" }.toTypedArray()
            else -> arrayOf("Android Default")
        }

        voiceSpinner.adapter = spinnerAdapter(voices)

        val savedVoiceId = prefs.getString("voice_id", "")
        val allVoices = TTSProviders.getAllVoices()
        val savedVoice = allVoices.find { it.id == savedVoiceId }

        if (savedVoice != null) {
            val voiceList = when (ttsProvider) {
                "elevenlabs" -> TTSProviders.elevenLabsVoices
                "cartesia" -> TTSProviders.cartesiaVoices
                else -> TTSProviders.androidVoices
            }
            val index = voiceList.indexOf(savedVoice)
            if (index >= 0) voiceSpinner.setSelection(index)
        }
    }

    private fun loadSettings() {
        val savedPersonality = prefs.getString("personality", "girlfriend") ?: "girlfriend"
        personalityValues.indexOf(savedPersonality).takeIf { it >= 0 }?.let { personalitySpinner.setSelection(it) }

        userNameInput.setText(prefs.getString("user_name", ""))
        nicknameInput.setText(prefs.getString("nickname", ""))

        val savedProvider = prefs.getString("ai_provider", "groq") ?: "groq"
        providerValues.indexOf(savedProvider).takeIf { it >= 0 }?.let { providerSpinner.setSelection(it) }

        loadProviderApiKey(savedProvider)

        val savedTtsProvider = prefs.getString("tts_provider", "android") ?: "android"
        ttsProviderValues.indexOf(savedTtsProvider).takeIf { it >= 0 }?.let { ttsProviderSpinner.setSelection(it) }

        val savedVoiceId = prefs.getString("voice_id", "android_default").orEmpty()
        val savedCustomVoiceId = prefs.getString("voice_id_custom", "").orEmpty()
        val savedCustomVoiceName = prefs.getString("voice_name_custom", "").orEmpty()
        val savedVoiceName = prefs.getString("voice_name", "").orEmpty()
        val knownVoice = TTSProviders.getAllVoices().any { it.id == savedVoiceId }
        val isCustomVoice = savedCustomVoiceId.isNotBlank() ||
            (savedVoiceId.isNotBlank() && savedVoiceId != "android_default" && !knownVoice)
        customVoiceIdInput.setText(
            when {
                savedCustomVoiceId.isNotBlank() -> savedCustomVoiceId
                savedVoiceId.isNotBlank() && savedVoiceId != "android_default" && !knownVoice -> savedVoiceId
                else -> ""
            }
        )
        customVoiceNameInput.setText(
            when {
                savedCustomVoiceName.isNotBlank() -> savedCustomVoiceName
                isCustomVoice && savedVoiceName.isNotBlank() && savedVoiceName != "Custom Voice" -> savedVoiceName
                else -> ""
            }
        )

        voiceApiKeyInput.setText(SecurePrefs.getDecryptedString(this, "ARIA_PREFS", "voice_api_key_enc", "voice_api_key"))

        proactiveSwitch.isChecked = prefs.getBoolean("proactive_enabled", true)
        themeSwitch.isChecked = prefs.getBoolean("dark_mode", true)
        wakeWordSwitch.isChecked = prefs.getBoolean("wake_word_enabled", false)
        banglaModeSwitch.isChecked = prefs.getBoolean("bangla_mode", true)
        safeRootSwitch.isChecked = prefs.getBoolean("safe_root_guard", true)
        liveModeSwitch.isChecked = prefs.getBoolean("live_mode_enabled", false)
        liveAlwaysOnSwitch.isChecked = prefs.getBoolean("live_always_on", false)
        liveVisionSwitch.isChecked = prefs.getBoolean("live_vision_enabled", false)

        val lang = prefs.getString("speech_recognition_lang", "auto") ?: "auto"
        speechLanguageValues.indexOf(lang).takeIf { it >= 0 }?.let { speechLanguageSpinner.setSelection(it) }

        updateModelSpinner(savedProvider)
        updateVoiceSpinner(savedTtsProvider)
    }

    private fun loadProviderApiKey(providerId: String) {
        val providerKey = SecurePrefs.getDecryptedString(
            this,
            "ARIA_PREFS",
            "api_key_${providerId}_enc",
            "api_key_${providerId}"
        )
        val fallback = SecurePrefs.getDecryptedString(this, "ARIA_PREFS", "api_key_enc", "api_key")
        apiKeyInput.setText(if (providerKey.isNotBlank()) providerKey else fallback)
    }

    private fun testVoiceConfig() {
        val ttsProvider = ttsProviderValues[ttsProviderSpinner.selectedItemPosition]

        if (ttsProvider == "android") {
            Toast.makeText(this, "Android TTS selected, no API validation needed", Toast.LENGTH_SHORT).show()
            return
        }

        val voiceApiKey = voiceApiKeyInput.text?.toString().orEmpty()
        if (voiceApiKey.isBlank()) {
            Toast.makeText(this, "Voice API key missing", Toast.LENGTH_SHORT).show()
            return
        }

        if (ttsProvider == "cartesia" && customVoiceIdInput.text?.toString().orEmpty().trim().isBlank()) {
            Toast.makeText(this, "Cartesia test er jonno Custom Voice ID din", Toast.LENGTH_LONG).show()
            return
        }

        val (voiceId, _) = resolveSelectedVoice(ttsProvider)

        if (voiceId.isBlank()) {
            Toast.makeText(this, "Voice ID not selected", Toast.LENGTH_SHORT).show()
            return
        }

        Toast.makeText(this, "Testing voice config...", Toast.LENGTH_SHORT).show()

        CoroutineScope(Dispatchers.IO).launch {
            val provider = if (ttsProvider == "elevenlabs") TTSProvider.ELEVENLABS else TTSProvider.CARTESIA
            val result = TTSProviders.validateVoiceConfig(this@SettingsActivity, provider, voiceId, voiceApiKey)

            withContext(Dispatchers.Main) {
                if (result.success) {
                    prefs.edit().putString("voice_last_validation", "ok").apply()
                    Toast.makeText(this@SettingsActivity, "✅ Voice test success", Toast.LENGTH_LONG).show()
                } else {
                    prefs.edit().putString("voice_last_validation", result.reason).apply()
                    Toast.makeText(this@SettingsActivity, "❌ Voice test failed: ${result.reason}", Toast.LENGTH_LONG).show()
                }
            }
        }
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
        val customVoiceId = customVoiceIdInput.text?.toString().orEmpty().trim()
        val (voiceId, voiceName) = resolveSelectedVoice(ttsProvider)

        if (apiKey.isEmpty()) {
            Toast.makeText(this, "⚠️ AI API Key required!", Toast.LENGTH_SHORT).show()
            return
        }

        if ((ttsProvider == "elevenlabs" || ttsProvider == "cartesia") && voiceApiKey.isEmpty()) {
            Toast.makeText(this, "⚠️ Voice API Key required for $ttsProvider!", Toast.LENGTH_LONG).show()
            return
        }

        if (ttsProvider == "cartesia" && customVoiceId.isBlank()) {
            Toast.makeText(this, "⚠️ Cartesia te manual Custom Voice ID din", Toast.LENGTH_LONG).show()
            return
        }

        val speechLang = speechLanguageValues[speechLanguageSpinner.selectedItemPosition]

        prefs.edit().apply {
            putString("personality", personality)
            putString("user_name", userName)
            putString("nickname", nickname)
            putString("ai_provider", provider)
            putString("model", model)
            putString("api_key_enc", SecurePrefs.encrypt(apiKey))
            putString("api_key", "")
            putString("api_key_${provider}_enc", SecurePrefs.encrypt(apiKey))
            putString("api_key_${provider}", "")
            putString("tts_provider", ttsProvider)
            putString("voice_id", voiceId)
            putString("voice_name", voiceName)
            putString("voice_id_custom", if (ttsProvider == "android") "" else customVoiceId)
            putString(
                "voice_name_custom",
                if (ttsProvider == "android" || customVoiceId.isBlank()) "" else voiceName
            )
            putString("voice_api_key_enc", SecurePrefs.encrypt(voiceApiKey))
            putString("voice_api_key", "")
            remove("last_good_voice_provider")
            remove("last_good_voice_id")
            putString("speech_recognition_lang", speechLang)
            putBoolean("proactive_enabled", proactiveSwitch.isChecked)
            putBoolean("dark_mode", themeSwitch.isChecked)
            putBoolean("wake_word_enabled", wakeWordSwitch.isChecked)
            putBoolean("bangla_mode", banglaModeSwitch.isChecked)
            putBoolean("safe_root_guard", safeRootSwitch.isChecked)
            putBoolean("live_mode_enabled", liveModeSwitch.isChecked)
            putBoolean("live_always_on", liveAlwaysOnSwitch.isChecked)
            putBoolean("live_vision_enabled", liveVisionSwitch.isChecked)
            apply()
        }

        if (liveModeSwitch.isChecked) {
            if (liveAlwaysOnSwitch.isChecked) {
                ConsentStore.setLiveEnabled(this, true)
                ConsentStore.setAlwaysOn(this, true)
                if (!ConsentStore.isSessionActive(this)) {
                    ConsentStore.startSession(this, 240)
                }
                LiveModeController.startService(this)
            } else {
                ConsentStore.setAlwaysOn(this, false)
                LiveModeController.requestSession(this)
            }
        } else {
            ConsentStore.setAlwaysOn(this, false)
            LiveModeController.stop(this)
        }

        val serviceIntent = Intent(this, ProactiveService::class.java)
        if (proactiveSwitch.isChecked && personality == "girlfriend") {
            startService(serviceIntent)
        } else {
            stopService(serviceIntent)
        }

        val personalityName = personalities[personalitySpinner.selectedItemPosition]
        Toast.makeText(this, "✅ Saved\nPersonality: $personalityName\nVoice: $voiceName", Toast.LENGTH_LONG).show()
        finish()
    }

    private fun resolveSelectedVoice(ttsProvider: String): Pair<String, String> {
        if (ttsProvider == "android") return "android_default" to "Android Default"

        val customVoiceId = customVoiceIdInput.text?.toString().orEmpty().trim()
        if (customVoiceId.isNotBlank()) {
            val customVoiceName = customVoiceNameInput.text?.toString().orEmpty().trim()
            val defaultName = when (ttsProvider) {
                "cartesia" -> "Cartesia Custom"
                "elevenlabs" -> "ElevenLabs Custom"
                else -> "Custom Voice"
            }
            return customVoiceId to customVoiceName.ifBlank { defaultName }
        }

        val voiceList = when (ttsProvider) {
            "elevenlabs" -> TTSProviders.elevenLabsVoices
            "cartesia" -> TTSProviders.cartesiaVoices
            else -> TTSProviders.androidVoices
        }
        val selectedVoice = voiceList.getOrNull(voiceSpinner.selectedItemPosition)
        return (selectedVoice?.id ?: "android_default") to (selectedVoice?.name ?: "Android Default")
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
