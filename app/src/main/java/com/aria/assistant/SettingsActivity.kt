package com.aria.assistant

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText

class SettingsActivity : AppCompatActivity() {
    
    private lateinit var prefs: SharedPreferences
    
    private lateinit var apiEndpointInput: TextInputEditText
    private lateinit var agentIdInput: TextInputEditText
    private lateinit var apiKeyInput: TextInputEditText
    private lateinit var themeSwitch: SwitchMaterial
    private lateinit var saveButton: MaterialButton
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        
        supportActionBar?.title = "⚙️ ARIA Settings"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        
        prefs = getSharedPreferences("ARIA_PREFS", Context.MODE_PRIVATE)
        
        // Initialize views
        apiEndpointInput = findViewById(R.id.apiEndpointInput)
        agentIdInput = findViewById(R.id.agentIdInput)
        apiKeyInput = findViewById(R.id.apiKeyInput)
        themeSwitch = findViewById(R.id.themeSwitch)
        saveButton = findViewById(R.id.saveButton)
        
        // Load saved settings
        loadSettings()
        
        // Save button click
        saveButton.setOnClickListener {
            saveSettings()
        }
        
        // Theme switch listener
        themeSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            } else {
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            }
        }
    }
    
    private fun loadSettings() {
        apiEndpointInput.setText(prefs.getString("api_endpoint", "https://api.letta.com"))
        agentIdInput.setText(prefs.getString("agent_id", "agent-82cf249d-d11f-47bb-a3d3-92458a13c4ea"))
        apiKeyInput.setText(prefs.getString("api_key", ""))
        
        val isDarkMode = prefs.getBoolean("dark_mode", true)
        themeSwitch.isChecked = isDarkMode
    }
    
    private fun saveSettings() {
        val endpoint = apiEndpointInput.text.toString()
        val agentId = agentIdInput.text.toString()
        val apiKey = apiKeyInput.text.toString()
        
        if (endpoint.isEmpty() || agentId.isEmpty()) {
            Toast.makeText(this, "API Endpoint and Agent ID required!", Toast.LENGTH_SHORT).show()
            return
        }
        
        prefs.edit().apply {
            putString("api_endpoint", endpoint)
            putString("agent_id", agentId)
            putString("api_key", apiKey)
            putBoolean("dark_mode", themeSwitch.isChecked)
            apply()
        }
        
        Toast.makeText(this, "✅ Settings saved!", Toast.LENGTH_SHORT).show()
        finish()
    }
    
    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
