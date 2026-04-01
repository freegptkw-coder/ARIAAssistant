package com.aria.assistant

import android.animation.ObjectAnimator
import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class RootSafetyActivity : AppCompatActivity() {

    private lateinit var policyBadge: TextView
    private lateinit var allowlistInput: TextInputEditText
    private lateinit var denylistInput: TextInputEditText
    private lateinit var strictModeSwitch: SwitchMaterial
    private lateinit var hardeningReportText: TextView
    private lateinit var auditLogText: TextView
    private lateinit var crashLogText: TextView
    private lateinit var runHardeningScanButton: MaterialButton
    private lateinit var applyHardeningButton: MaterialButton
    private lateinit var crashLoggingSwitch: SwitchMaterial
    private lateinit var autoHealSwitch: SwitchMaterial
    private lateinit var rootDiagSwitch: SwitchMaterial
    private val rootExecutor = RootCommandExecutor()
    private var busyAnimator: ObjectAnimator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_root_safety)

        supportActionBar?.title = "🛡 Root Safety Center"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        policyBadge = findViewById(R.id.rootPolicyBadge)
        allowlistInput = findViewById(R.id.allowlistInput)
        denylistInput = findViewById(R.id.denylistInput)
        strictModeSwitch = findViewById(R.id.strictModeSwitch)
        hardeningReportText = findViewById(R.id.hardeningReportText)
        auditLogText = findViewById(R.id.auditLogText)
        crashLogText = findViewById(R.id.crashLogText)
        runHardeningScanButton = findViewById(R.id.runHardeningScanButton)
        applyHardeningButton = findViewById(R.id.applyHardeningButton)
        crashLoggingSwitch = findViewById(R.id.crashLoggingSwitch)
        autoHealSwitch = findViewById(R.id.autoHealSwitch)
        rootDiagSwitch = findViewById(R.id.rootDiagSwitch)

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
        findViewById<MaterialButton>(R.id.runSelfHealButton).setOnClickListener {
            val result = ErrorRecoveryManager.runSelfHealNow(this)
            Toast.makeText(this, "Self-heal done", Toast.LENGTH_SHORT).show()
            hardeningReportText.text = "Last self-heal summary:\n$result"
            refreshCrashLog()
            refreshAudit()
        }
        findViewById<MaterialButton>(R.id.shareCrashLogButton).setOnClickListener {
            val report = buildString {
                append("ARIA Error Log\n")
                append("File: ${ErrorRecoveryManager.getCrashLogFilePath()}\n\n")
                append(ErrorRecoveryManager.getCrashLog(this@RootSafetyActivity))
            }
            val share = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "ARIA Error Log")
                putExtra(Intent.EXTRA_TEXT, report)
            }
            startActivity(Intent.createChooser(share, "Share error log"))
        }
        findViewById<MaterialButton>(R.id.clearCrashLogButton).setOnClickListener {
            ErrorRecoveryManager.clearCrashLog(this)
            refreshCrashLog()
            Toast.makeText(this, "Crash log cleared", Toast.LENGTH_SHORT).show()
        }
        runHardeningScanButton.setOnClickListener {
            runHardeningScan()
        }
        applyHardeningButton.setOnClickListener {
            applyHardeningPack()
        }

        strictModeSwitch.setOnCheckedChangeListener { _, isChecked ->
            updatePolicyBadge(isChecked)
        }

        crashLoggingSwitch.setOnCheckedChangeListener { _, isChecked ->
            ErrorRecoveryManager.setCrashLoggingEnabled(this, isChecked)
        }

        autoHealSwitch.setOnCheckedChangeListener { _, isChecked ->
            ErrorRecoveryManager.setAutoHealEnabled(this, isChecked)
        }

        rootDiagSwitch.setOnCheckedChangeListener { _, isChecked ->
            ErrorRecoveryManager.setRootDiagnosticsEnabled(this, isChecked)
        }

        loadPolicy()
        loadCrashSettings()
        refreshAudit()
        refreshCrashLog()
        hardeningReportText.text = "No hardening scan yet."
    }

    private fun loadPolicy() {
        allowlistInput.setText(RootSafetyPolicy.getAllowlist(this))
        denylistInput.setText(RootSafetyPolicy.getDenylist(this))
        val strict = RootSafetyPolicy.isStrictMode(this)
        strictModeSwitch.isChecked = strict
        updatePolicyBadge(strict)
    }

    private fun savePolicy() {
        RootSafetyPolicy.savePolicy(
            this,
            allowlist = allowlistInput.text?.toString().orEmpty(),
            denylist = denylistInput.text?.toString().orEmpty(),
            strict = strictModeSwitch.isChecked
        )
        updatePolicyBadge(strictModeSwitch.isChecked)
        Toast.makeText(this, "Root safety policy saved", Toast.LENGTH_SHORT).show()
    }

    private fun refreshAudit() {
        auditLogText.text = RootSafetyPolicy.getAuditLog(this)
    }

    private fun loadCrashSettings() {
        crashLoggingSwitch.isChecked = ErrorRecoveryManager.isCrashLoggingEnabled(this)
        autoHealSwitch.isChecked = ErrorRecoveryManager.isAutoHealEnabled(this)
        rootDiagSwitch.isChecked = ErrorRecoveryManager.isRootDiagnosticsEnabled(this)
    }

    private fun refreshCrashLog() {
        crashLogText.text = ErrorRecoveryManager.getCrashLog(this)
    }

    private fun runHardeningScan() {
        if (!rootExecutor.checkRootAccess()) {
            Toast.makeText(this, "Root access not available", Toast.LENGTH_SHORT).show()
            return
        }

        setBusyState(true)
        hardeningReportText.text = "Running security hardening scan..."
        CoroutineScope(Dispatchers.IO).launch {
            val report = SecurityHardeningManager.runScan(rootExecutor)
            withContext(Dispatchers.Main) {
                setBusyState(false)
                hardeningReportText.text = SecurityHardeningManager.toDisplayText(report)
                val unsafe = report.results.count { it.state == HardeningState.UNSAFE }
                Toast.makeText(
                    this@RootSafetyActivity,
                    "Hardening scan complete. Unsafe: $unsafe",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun updatePolicyBadge(strict: Boolean) {
        if (strict) {
            policyBadge.text = "STRICT"
            policyBadge.setBackgroundResource(R.drawable.bg_security_badge_strict)
        } else {
            policyBadge.text = "BALANCED"
            policyBadge.setBackgroundResource(R.drawable.bg_security_badge_balanced)
        }
    }

    private fun setBusyState(busy: Boolean) {
        runHardeningScanButton.isEnabled = !busy
        applyHardeningButton.isEnabled = !busy
        if (busy) {
            if (busyAnimator?.isRunning != true) {
                busyAnimator = ObjectAnimator.ofFloat(hardeningReportText, "alpha", 1f, 0.5f, 1f).apply {
                    duration = 900L
                    repeatCount = ObjectAnimator.INFINITE
                    start()
                }
            }
        } else {
            busyAnimator?.cancel()
            busyAnimator = null
            hardeningReportText.alpha = 1f
        }
    }

    override fun onDestroy() {
        setBusyState(false)
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        refreshAudit()
        refreshCrashLog()
    }

    private fun applyHardeningPack() {
        if (!rootExecutor.checkRootAccess()) {
            Toast.makeText(this, "Root access not available", Toast.LENGTH_SHORT).show()
            return
        }

        setBusyState(true)
        hardeningReportText.text = "Applying safe hardening fixes..."
        CoroutineScope(Dispatchers.IO).launch {
            val applyReport = SecurityHardeningManager.applyRecommended(this@RootSafetyActivity, rootExecutor)
            val scanAfter = SecurityHardeningManager.runScan(rootExecutor)

            withContext(Dispatchers.Main) {
                setBusyState(false)
                hardeningReportText.text = SecurityHardeningManager.toDisplayText(scanAfter) +
                    "\n\nApply summary: applied=${applyReport.applied}, skipped=${applyReport.skipped}, failed=${applyReport.failed}" +
                    if (applyReport.notes.isNotEmpty()) "\n${applyReport.notes.joinToString("\n")}" else ""
                refreshAudit()
                Toast.makeText(
                    this@RootSafetyActivity,
                    "Hardening applied: ${applyReport.applied}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
