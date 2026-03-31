package com.aria.assistant

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class HardeningState {
    SAFE, UNSAFE, UNKNOWN
}

data class HardeningCheckResult(
    val title: String,
    val state: HardeningState,
    val currentValue: String,
    val expectedValue: String,
    val fixCommand: String?
)

data class HardeningScanReport(
    val scannedAt: Long,
    val results: List<HardeningCheckResult>
)

data class HardeningApplyReport(
    val applied: Int,
    val skipped: Int,
    val failed: Int,
    val notes: List<String>
)

object SecurityHardeningManager {

    private data class Rule(
        val title: String,
        val getCommand: String,
        val expectedValue: String,
        val fixCommand: String?,
        val allowUnknown: Boolean = false
    )

    private val rules = listOf(
        Rule(
            title = "ADB debugging disabled",
            getCommand = "settings get global adb_enabled",
            expectedValue = "0",
            fixCommand = "settings put global adb_enabled 0"
        ),
        Rule(
            title = "Developer options disabled",
            getCommand = "settings get global development_settings_enabled",
            expectedValue = "0",
            fixCommand = "settings put global development_settings_enabled 0"
        ),
        Rule(
            title = "Package verifier enabled",
            getCommand = "settings get global package_verifier_enable",
            expectedValue = "1",
            fixCommand = "settings put global package_verifier_enable 1",
            allowUnknown = true
        ),
        Rule(
            title = "Verify ADB installs enabled",
            getCommand = "settings get global verifier_verify_adb_installs",
            expectedValue = "1",
            fixCommand = "settings put global verifier_verify_adb_installs 1",
            allowUnknown = true
        ),
        Rule(
            title = "Install unknown apps baseline hardened",
            getCommand = "settings get secure install_non_market_apps",
            expectedValue = "0",
            fixCommand = "settings put secure install_non_market_apps 0",
            allowUnknown = true
        ),
        Rule(
            title = "Stay awake while charging disabled",
            getCommand = "settings get global stay_on_while_plugged_in",
            expectedValue = "0",
            fixCommand = "settings put global stay_on_while_plugged_in 0"
        )
    )

    fun runScan(executor: RootCommandExecutor): HardeningScanReport {
        val results = rules.map { rule ->
            val raw = executor.execute(rule.getCommand)
            val value = normalizeSettingValue(raw)

            val state = when {
                value.equals(rule.expectedValue, ignoreCase = true) -> HardeningState.SAFE
                value == "unknown" && rule.allowUnknown -> HardeningState.UNKNOWN
                value == "unknown" -> HardeningState.UNKNOWN
                else -> HardeningState.UNSAFE
            }

            HardeningCheckResult(
                title = rule.title,
                state = state,
                currentValue = value,
                expectedValue = rule.expectedValue,
                fixCommand = rule.fixCommand
            )
        }

        return HardeningScanReport(System.currentTimeMillis(), results)
    }

    fun applyRecommended(context: Context, executor: RootCommandExecutor): HardeningApplyReport {
        val scan = runScan(executor)
        var applied = 0
        var skipped = 0
        var failed = 0
        val notes = mutableListOf<String>()

        scan.results.forEach { result ->
            if (result.state != HardeningState.UNSAFE || result.fixCommand.isNullOrBlank()) {
                skipped++
                return@forEach
            }

            val decision = RootSafetyPolicy.evaluate(context, result.fixCommand)
            if (!decision.allowed) {
                skipped++
                val msg = "Skipped ${result.title}: ${decision.reason}"
                notes += msg
                RootSafetyPolicy.appendAudit(context, result.fixCommand, "BLOCKED", decision.reason)
                return@forEach
            }

            val output = executor.execute(result.fixCommand)
            val failedExec = output.contains("Error", ignoreCase = true)

            if (failedExec) {
                failed++
                notes += "Failed ${result.title}: ${output.take(120)}"
                RootSafetyPolicy.appendAudit(context, result.fixCommand, "FAILED", output.take(200))
                return@forEach
            }

            val recheckRaw = executor.execute(findGetCommandFor(result.title))
            val recheck = normalizeSettingValue(recheckRaw)
            if (recheck == result.expectedValue) {
                applied++
                val okMsg = "Applied ${result.title}"
                notes += okMsg
                RootSafetyPolicy.appendAudit(context, result.fixCommand, "OK", "hardening applied")
            } else {
                failed++
                val failMsg = "Verify failed ${result.title}: got '$recheck'"
                notes += failMsg
                RootSafetyPolicy.appendAudit(context, result.fixCommand, "FAILED", failMsg)
            }
        }

        return HardeningApplyReport(applied, skipped, failed, notes)
    }

    fun toDisplayText(report: HardeningScanReport): String {
        val ts = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date(report.scannedAt))
        val safe = report.results.count { it.state == HardeningState.SAFE }
        val unsafe = report.results.count { it.state == HardeningState.UNSAFE }
        val unknown = report.results.count { it.state == HardeningState.UNKNOWN }

        val details = report.results.joinToString("\n") { r ->
            val badge = when (r.state) {
                HardeningState.SAFE -> "✅"
                HardeningState.UNSAFE -> "⚠️"
                HardeningState.UNKNOWN -> "❔"
            }
            "$badge ${r.title} | current=${r.currentValue}, expected=${r.expectedValue}"
        }

        return buildString {
            append("Last scan: $ts\n")
            append("Safe: $safe | Unsafe: $unsafe | Unknown: $unknown\n\n")
            append(details)
        }
    }

    private fun findGetCommandFor(title: String): String {
        return rules.firstOrNull { it.title == title }?.getCommand.orEmpty()
    }

    private fun normalizeSettingValue(raw: String): String {
        val line = raw.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        if (line.isBlank()) return "unknown"
        if (line.equals("command executed successfully", ignoreCase = true)) return "unknown"
        if (line.contains("Error", ignoreCase = true)) return "unknown"
        if (line.equals("null", ignoreCase = true)) return "unknown"
        return line
    }
}