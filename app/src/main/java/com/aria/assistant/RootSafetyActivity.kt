package com.aria.assistant

import android.animation.ObjectAnimator
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
    private lateinit var runHardeningScanButton: MaterialButton
    private lateinit var applyHardeningButton: MaterialButton
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
        runHardeningScanButton = findViewById(R.id.runHardeningScanButton)
        applyHardeningButton = findViewById(R.id.applyHardeningButton)

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
        runHardeningScanButton.setOnClickListener {
            runHardeningScan()
        }
        applyHardeningButton.setOnClickListener {
            applyHardeningPack()
        }

        strictModeSwitch.setOnCheckedChangeListener { _, isChecked ->
            updatePolicyBadge(isChecked)
        }

        loadPolicy()
        refreshAudit()
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
