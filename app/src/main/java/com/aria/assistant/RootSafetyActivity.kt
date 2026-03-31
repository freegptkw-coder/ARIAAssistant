package com.aria.assistant

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

    private lateinit var allowlistInput: TextInputEditText
    private lateinit var denylistInput: TextInputEditText
    private lateinit var strictModeSwitch: SwitchMaterial
    private lateinit var hardeningReportText: TextView
    private lateinit var auditLogText: TextView
    private val rootExecutor = RootCommandExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_root_safety)

        supportActionBar?.title = "🛡 Root Safety Center"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        allowlistInput = findViewById(R.id.allowlistInput)
        denylistInput = findViewById(R.id.denylistInput)
        strictModeSwitch = findViewById(R.id.strictModeSwitch)
        hardeningReportText = findViewById(R.id.hardeningReportText)
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
        findViewById<MaterialButton>(R.id.runHardeningScanButton).setOnClickListener {
            runHardeningScan()
        }
        findViewById<MaterialButton>(R.id.applyHardeningButton).setOnClickListener {
            applyHardeningPack()
        }

        loadPolicy()
        refreshAudit()
        hardeningReportText.text = "No hardening scan yet."
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

    private fun runHardeningScan() {
        if (!rootExecutor.checkRootAccess()) {
            Toast.makeText(this, "Root access not available", Toast.LENGTH_SHORT).show()
            return
        }

        hardeningReportText.text = "Running security hardening scan..."
        CoroutineScope(Dispatchers.IO).launch {
            val report = SecurityHardeningManager.runScan(rootExecutor)
            withContext(Dispatchers.Main) {
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

    private fun applyHardeningPack() {
        if (!rootExecutor.checkRootAccess()) {
            Toast.makeText(this, "Root access not available", Toast.LENGTH_SHORT).show()
            return
        }

        hardeningReportText.text = "Applying safe hardening fixes..."
        CoroutineScope(Dispatchers.IO).launch {
            val applyReport = SecurityHardeningManager.applyRecommended(this@RootSafetyActivity, rootExecutor)
            val scanAfter = SecurityHardeningManager.runScan(rootExecutor)

            withContext(Dispatchers.Main) {
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
