package com.aria.assistant.live

import android.animation.ObjectAnimator
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.aria.assistant.AiReliabilityLogger
import com.aria.assistant.R
import com.aria.assistant.automation.AutomationAuditLogger
import com.google.android.material.button.MaterialButton

class LiveSafetyActivity : AppCompatActivity() {

    private lateinit var stateBadge: TextView
    private lateinit var statusText: TextView
    private lateinit var auditText: TextView
    private lateinit var automationAuditText: TextView
    private lateinit var toggleAvatarButton: MaterialButton
    private lateinit var toggleVisionSpeedButton: MaterialButton
    private var badgePulseAnimator: ObjectAnimator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_live_safety)

        supportActionBar?.title = "🛡 Live Safety Center"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        stateBadge = findViewById(R.id.liveStateBadge)
        statusText = findViewById(R.id.liveSafetyStatus)
        auditText = findViewById(R.id.liveAuditText)
        automationAuditText = findViewById(R.id.automationAuditText)
        toggleAvatarButton = findViewById(R.id.toggleLiveAvatarButton)
        toggleVisionSpeedButton = findViewById(R.id.toggleVisionSpeedButton)

        findViewById<MaterialButton>(R.id.openLiveConsentButton).setOnClickListener {
            LiveModeController.requestSession(this)
        }

        findViewById<MaterialButton>(R.id.panicStopButton).setOnClickListener {
            LiveModeController.stop(this)
            refreshUi()
        }

        toggleAvatarButton.setOnClickListener {
            val next = !ConsentStore.isAvatarEnabled(this)
            ConsentStore.setAvatarEnabled(this, next)
            Toast.makeText(this, if (next) "Anime overlay ON" else "Anime overlay OFF", Toast.LENGTH_SHORT).show()
            refreshUi()
        }

        toggleVisionSpeedButton.setOnClickListener {
            val current = ConsentStore.getVisionIntervalMs(this)
            val next = if (current <= 3000L) 8000L else 2500L
            ConsentStore.setVisionIntervalMs(this, next)
            Toast.makeText(
                this,
                if (next <= 3000L) "Vision speed: FAST" else "Vision speed: BALANCED",
                Toast.LENGTH_SHORT
            ).show()
            refreshUi()
        }

        findViewById<MaterialButton>(R.id.openOverlayPermissionButton).setOnClickListener {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
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
            AiReliabilityLogger.clear(this)
            refreshUi()
        }

        findViewById<MaterialButton>(R.id.exportLiveAuditButton).setOnClickListener {
            val report = buildString {
                append("ARIA Live Safety Export\n\n")
                append("== Live Audit ==\n")
                append(AuditLogger.read(this@LiveSafetyActivity))
                append("\n\n== Automation Audit ==\n")
                append(AutomationAuditLogger.read(this@LiveSafetyActivity))
                append("\n\n== AI Reliability ==\n")
                append(AiReliabilityLogger.read(this@LiveSafetyActivity))
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
        val active = liveEnabled && sessionActive

        statusText.text = buildString {
            append("Live Enabled: ")
            append(if (liveEnabled) "YES" else "NO")
            append("\nSession Active: ")
            append(if (sessionActive) "YES" else "NO")
            append("\nRemaining: ${rem / 60000}m ${(rem % 60000) / 1000}s")
            append("\nAvatar Overlay: ")
            append(if (ConsentStore.isAvatarEnabled(this@LiveSafetyActivity)) "ON" else "OFF")
            append("\nVision Interval: ")
            append("${ConsentStore.getVisionIntervalMs(this@LiveSafetyActivity)}ms")
        }

        val avatarOn = ConsentStore.isAvatarEnabled(this)
        toggleAvatarButton.text = if (avatarOn) "Anime Overlay: ON" else "Anime Overlay: OFF"

        val fastVision = ConsentStore.getVisionIntervalMs(this) <= 3000L
        toggleVisionSpeedButton.text = if (fastVision) {
            "Vision Speed: FAST (~2.5s)"
        } else {
            "Vision Speed: BALANCED (~8s)"
        }

        when {
            active -> {
                stateBadge.text = "ACTIVE"
                stateBadge.setBackgroundResource(R.drawable.bg_security_badge_active)
                startBadgePulse()
            }
            liveEnabled -> {
                stateBadge.text = "ARMED"
                stateBadge.setBackgroundResource(R.drawable.bg_security_badge_warn)
                stopBadgePulse()
            }
            else -> {
                stateBadge.text = "OFF"
                stateBadge.setBackgroundResource(R.drawable.bg_security_badge_off)
                stopBadgePulse()
            }
        }

        auditText.text = AuditLogger.read(this)
        automationAuditText.text = AutomationAuditLogger.read(this)
    }

    private fun startBadgePulse() {
        if (badgePulseAnimator?.isRunning == true) return
        badgePulseAnimator = ObjectAnimator.ofFloat(stateBadge, "alpha", 1f, 0.45f, 1f).apply {
            duration = 900L
            repeatCount = ObjectAnimator.INFINITE
            start()
        }
    }

    private fun stopBadgePulse() {
        badgePulseAnimator?.cancel()
        badgePulseAnimator = null
        stateBadge.alpha = 1f
    }

    override fun onPause() {
        super.onPause()
        stopBadgePulse()
    }
}
