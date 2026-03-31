package com.aria.assistant.live

import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.aria.assistant.R
import com.aria.assistant.automation.AutomationAuditLogger
import com.google.android.material.button.MaterialButton

class LiveSafetyActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var auditText: TextView
    private lateinit var automationAuditText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_live_safety)

        supportActionBar?.title = "🛡 Live Safety Center"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        statusText = findViewById(R.id.liveSafetyStatus)
        auditText = findViewById(R.id.liveAuditText)
        automationAuditText = findViewById(R.id.automationAuditText)

        findViewById<MaterialButton>(R.id.openLiveConsentButton).setOnClickListener {
            LiveModeController.requestSession(this)
        }

        findViewById<MaterialButton>(R.id.panicStopButton).setOnClickListener {
            LiveModeController.stop(this)
            refreshUi()
        }

        findViewById<MaterialButton>(R.id.refreshLiveAuditButton).setOnClickListener {
            refreshUi()
        }

        findViewById<MaterialButton>(R.id.clearLiveAuditButton).setOnClickListener {
            getSharedPreferences("ARIA_PREFS", MODE_PRIVATE)
                .edit()
                .remove("live_mode_audit")
                .apply()
            AutomationAuditLogger.clear(this)
            refreshUi()
        }

        findViewById<MaterialButton>(R.id.exportLiveAuditButton).setOnClickListener {
            val report = buildString {
                append("ARIA Live Safety Export\n\n")
                append("== Live Audit ==\n")
                append(AuditLogger.read(this@LiveSafetyActivity))
                append("\n\n== Automation Audit ==\n")
                append(AutomationAuditLogger.read(this@LiveSafetyActivity))
            }
            val share = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "ARIA Live Safety Audit")
                putExtra(Intent.EXTRA_TEXT, report)
            }
            startActivity(Intent.createChooser(share, "Export audit"))
        }

        refreshUi()
    }

    override fun onResume() {
        super.onResume()
        refreshUi()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun refreshUi() {
        val liveEnabled = ConsentStore.isLiveEnabled(this)
        val sessionActive = ConsentStore.isSessionActive(this)
        val rem = ConsentStore.getRemainingSessionMs(this)

        statusText.text = buildString {
            append("Live Enabled: ")
            append(if (liveEnabled) "YES" else "NO")
            append("\nSession Active: ")
            append(if (sessionActive) "YES" else "NO")
            append("\nRemaining: ${rem / 60000}m ${(rem % 60000) / 1000}s")
        }

        auditText.text = AuditLogger.read(this)
        automationAuditText.text = AutomationAuditLogger.read(this)
    }
}
