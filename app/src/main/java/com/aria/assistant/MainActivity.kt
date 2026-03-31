package com.aria.assistant

import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.aria.assistant.setup.SetupChecks
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator

class MainActivity : AppCompatActivity() {

    private lateinit var setupProgress: LinearProgressIndicator
    private lateinit var setupProgressText: TextView

    private lateinit var permissionsStatus: TextView
    private lateinit var overlayStatus: TextView
    private lateinit var assistantStatus: TextView

    private lateinit var openSetupButton: MaterialButton
    private lateinit var startAriaButton: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        setupProgress = findViewById(R.id.setupProgress)
        setupProgressText = findViewById(R.id.setupProgressText)

        permissionsStatus = findViewById(R.id.permissionsChecklistStatus)
        overlayStatus = findViewById(R.id.overlayChecklistStatus)
        assistantStatus = findViewById(R.id.assistantChecklistStatus)

        openSetupButton = findViewById(R.id.openSetupButton)
        startAriaButton = findViewById(R.id.startAriaButton)

        openSetupButton.setOnClickListener {
            startActivity(Intent(this, SetupActivity::class.java))
        }

        startAriaButton.setOnClickListener {
            startActivity(Intent(this, AssistantActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        renderSetupState()
    }

    private fun renderSetupState() {
        val state = SetupChecks.evaluate(this)

        setupProgress.max = state.totalPermissions
        setupProgress.progress = state.grantedPermissions
        setupProgressText.text = "${state.grantedPermissions}/${state.totalPermissions} permissions"

        permissionsStatus.text = if (state.permissionsDone) "✅ Completed" else "❌ Pending"
        overlayStatus.text = if (state.overlayDone) "✅ Completed" else "❌ Pending"
        assistantStatus.text = if (state.assistantDone) "✅ Completed" else "❌ Pending"

        startAriaButton.isEnabled = state.allDone
        startAriaButton.alpha = if (state.allDone) 1f else 0.5f
    }
}
