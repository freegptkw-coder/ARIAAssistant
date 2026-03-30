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
    private lateinit var personalitySpinner: Spinner
    private lateinit var userNameInput: TextInputEditText
    private lateinit var nicknameInput: TextInputEditText
    private lateinit var providerSpinner: Spinner
    private lateinit var modelSpinner: Spinner
    private lateinit var apiKeyInput: TextInputEditText
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
        proactiveSwitch = findViewById(R.id.proactiveSwitch)
        themeSwitch = findViewById(R.id.themeSwitch)
        saveButton = findViewById(R.id.saveButton)
        
        setupPersonalitySpinner()
        setupProviderSpinner()
        loadSettings()
        
        saveButton.setOnClickListener { saveSettings() }
        themeSwitch.setOnCheckedChangeListener { _, isChecked ->
            AppCompatDelegate.setDefaultNightMode(
                if (isChecked) AppCompatDelegate.MODE_NIGHT_YES 
                else AppCompatDelegate.MODE_NIGHT_NO
            )
        }
    }
    
    private fun setupPersonalitySpinner() {
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, personalities)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        personalitySpinner.adapter = adapter
    }
    
    private fun setupProviderSpinner() {
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, providers)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        providerSpinner.adapter = adapter
        
        providerSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                updateModelSpinner(providerValues[position])
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
    
    private fun loadSettings() {
        // Load personality
        val savedPersonality = prefs.getString("personality", "girlfriend") ?: "girlfriend"
        val persIndex = personalityValues.indexOf(savedPersonality)
        if (persIndex >= 0) personalitySpinner.setSelection(persIndex)
        
        // Load personal info
        userNameInput.setText(prefs.getString("user_name", ""))
        nicknameInput.setText(prefs.getString("nickname", ""))
        
        // Load provider
        val savedProvider = prefs.getString("ai_provider", "groq") ?: "groq"
        val provIndex = providerValues.indexOf(savedProvider)
        if (provIndex >= 0) providerSpinner.setSelection(provIndex)
        
        // Load API key
        apiKeyInput.setText(prefs.getString("api_key", ""))
        
        // Load switches
        proactiveSwitch.isChecked = prefs.getBoolean("proactive_enabled", true)
        themeSwitch.isChecked = prefs.getBoolean("dark_mode", true)
        
        updateModelSpinner(savedProvider)
    }
    
    private fun saveSettings() {
        val personality = personalityValues[personalitySpinner.selectedItemPosition]
        val userName = userNameInput.text.toString()
        val nickname = nicknameInput.text.toString()
        val provider = providerValues[providerSpinner.selectedItemPosition]
        val model = modelSpinner.selectedItem.toString()
        val apiKey = apiKeyInput.text.toString()
        val proactiveEnabled = proactiveSwitch.isChecked
        
        if (apiKey.isEmpty()) {
            Toast.makeText(this, "⚠️ API Key required!", Toast.LENGTH_SHORT).show()
            return
        }
        
        prefs.edit().apply {
            putString("personality", personality)
            putString("user_name", userName)
            putString("nickname", nickname)
            putString("ai_provider", provider)
            putString("model", model)
            putString("api_key", apiKey)
            putBoolean("proactive_enabled", proactiveEnabled)
            putBoolean("dark_mode", themeSwitch.isChecked)
            apply()
        }
        
        // Start/stop proactive service
        val serviceIntent = Intent(this, ProactiveService::class.java)
        if (proactiveEnabled && personality == "girlfriend") {
            startService(serviceIntent)
        } else {
            stopService(serviceIntent)
        }
        
        val personalityName = personalities[personalitySpinner.selectedItemPosition]
        Toast.makeText(this, "✅ Settings saved!\nMode: $personalityName", Toast.LENGTH_LONG).show()
        finish()
    }
    
    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
