package com.aria.assistant

import android.content.Context
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
    private lateinit var providerSpinner: Spinner
    private lateinit var modelSpinner: Spinner
    private lateinit var apiKeyInput: TextInputEditText
    private lateinit var themeSwitch: SwitchMaterial
    private lateinit var saveButton: MaterialButton
    
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
        
        providerSpinner = findViewById(R.id.providerSpinner)
        modelSpinner = findViewById(R.id.modelSpinner)
        apiKeyInput = findViewById(R.id.apiKeyInput)
        themeSwitch = findViewById(R.id.themeSwitch)
        saveButton = findViewById(R.id.saveButton)
        
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
        val savedProvider = prefs.getString("ai_provider", "groq") ?: "groq"
        val index = providerValues.indexOf(savedProvider)
        if (index >= 0) providerSpinner.setSelection(index)
        
        apiKeyInput.setText(prefs.getString("api_key", ""))
        themeSwitch.isChecked = prefs.getBoolean("dark_mode", true)
        
        updateModelSpinner(savedProvider)
    }
    
    private fun saveSettings() {
        val provider = providerValues[providerSpinner.selectedItemPosition]
        val model = modelSpinner.selectedItem.toString()
        val apiKey = apiKeyInput.text.toString()
        
        if (apiKey.isEmpty()) {
            Toast.makeText(this, "⚠️ API Key required!", Toast.LENGTH_SHORT).show()
            return
        }
        
        prefs.edit().apply {
            putString("ai_provider", provider)
            putString("model", model)
            putString("api_key", apiKey)
            putBoolean("dark_mode", themeSwitch.isChecked)
            apply()
        }
        
        Toast.makeText(this, "✅ Settings saved! Provider: ${providers[providerSpinner.selectedItemPosition]}", Toast.LENGTH_LONG).show()
        finish()
    }
    
    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
