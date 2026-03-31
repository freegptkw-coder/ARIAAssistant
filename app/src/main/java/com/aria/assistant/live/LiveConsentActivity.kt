package com.aria.assistant.live

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.aria.assistant.R
import com.google.android.material.button.MaterialButton

class LiveConsentActivity : AppCompatActivity() {

    companion object {
        private const val REQ_MIC = 8301
    }

    private lateinit var statusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_live_consent)

        supportActionBar?.title = "Live Mode Consent"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        statusText = findViewById(R.id.liveConsentStatus)

        findViewById<MaterialButton>(R.id.startLive15mButton).setOnClickListener {
            startSession(15)
        }

        findViewById<MaterialButton>(R.id.startLive60mButton).setOnClickListener {
            startSession(60)
        }

        findViewById<MaterialButton>(R.id.cancelLiveButton).setOnClickListener {
            ConsentStore.setLiveEnabled(this, false)
            ConsentStore.endSession(this)
            Toast.makeText(this, "Live mode cancelled", Toast.LENGTH_SHORT).show()
            finish()
        }

        refreshStatus()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun startSession(minutes: Int) {
        if (!hasMicPermission()) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
            return
        }

        ConsentStore.setLiveEnabled(this, true)
        ConsentStore.startSession(this, minutes)
        LiveModeController.startService(this)
        AuditLogger.log(this, "live_session_started:${minutes}m")
        Toast.makeText(this, "Live session started for $minutes minutes", Toast.LENGTH_LONG).show()
        finish()
    }

    private fun hasMicPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_MIC) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startSession(ConsentStore.getDefaultSessionMinutes(this))
            } else {
                Toast.makeText(this, "Microphone permission required for Live Mode", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun refreshStatus() {
        val active = ConsentStore.isSessionActive(this)
        val remaining = ConsentStore.getRemainingSessionMs(this)
        statusText.text = if (active) {
            "Active session running. Remaining: ${remaining / 60000}m ${(remaining % 60000) / 1000}s"
        } else {
            "No active Live session. Choose a duration to start."
        }
    }
}
