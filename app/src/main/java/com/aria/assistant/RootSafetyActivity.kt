package com.aria.assistant

import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText

class RootSafetyActivity : AppCompatActivity() {

    private lateinit var allowlistInput: TextInputEditText
    private lateinit var denylistInput: TextInputEditText
    private lateinit var strictModeSwitch: SwitchMaterial
    private lateinit var auditLogText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_root_safety)

        supportActionBar?.title = "🛡 Root Safety Center"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        allowlistInput = findViewById(R.id.allowlistInput)
        denylistInput = findViewById(R.id.denylistInput)
        strictModeSwitch = findViewById(R.id.strictModeSwitch)
        auditLogText = findViewById(R.id.auditLogText)

        findViewById<MaterialButton>(R.id.savePolicyButton).setOnClickListener {
            savePolicy()
        }
        findViewById<MaterialButton>(R.id.refreshAuditButton).setOnClickListener {
            refreshAudit()
        }
        findViewById<MaterialButton>(R.id.clearAuditButton).setOnClickListener {
            RootSafetyPolicy.clearAuditLog(this)
            refreshAudit()
        }

        loadPolicy()
        refreshAudit()
    }

    private fun loadPolicy() {
        allowlistInput.setText(RootSafetyPolicy.getAllowlist(this))
        denylistInput.setText(RootSafetyPolicy.getDenylist(this))
        strictModeSwitch.isChecked = RootSafetyPolicy.isStrictMode(this)
    }

    private fun savePolicy() {
        RootSafetyPolicy.savePolicy(
            this,
            allowlist = allowlistInput.text?.toString().orEmpty(),
            denylist = denylistInput.text?.toString().orEmpty(),
            strict = strictModeSwitch.isChecked
        )
        Toast.makeText(this, "Root safety policy saved", Toast.LENGTH_SHORT).show()
    }

    private fun refreshAudit() {
        auditLogText.text = RootSafetyPolicy.getAuditLog(this)
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
