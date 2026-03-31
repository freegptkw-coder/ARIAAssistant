package com.aria.assistant.automation

import android.content.Context
import com.aria.assistant.RootCommandExecutor
import java.util.Locale

data class SensitiveScreenCheckResult(
    val blocked: Boolean,
    val reason: String,
    val evidence: String = ""
)

object SensitiveScreenGuard {

    private const val PREF = "ARIA_PREFS"
    private const val KEY_GUARD_ENABLED = "sensitive_screen_guard_enabled"

    private val sensitiveKeywords = listOf(
        "password",
        "passcode",
        "verification code",
        "otp",
        "cvv",
        "card number",
        "upi pin",
        "net banking",
        "bank account",
        "confirm payment",
        "pay now",
        "2fa",
        "two-factor"
    )

    private val sensitivePackageHints = listOf(
        "paytm",
        "phonepe",
        "paisa",
        "wallet",
        ".bank",
        "authenticator",
        "password"
    )

    fun isEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getBoolean(KEY_GUARD_ENABLED, true)
    }

    fun evaluate(context: Context): SensitiveScreenCheckResult {
        if (!isEnabled(context)) {
            return SensitiveScreenCheckResult(blocked = false, reason = "guard_disabled")
        }

        val executor = RootCommandExecutor()
        if (!executor.checkRootAccess()) {
            return SensitiveScreenCheckResult(blocked = false, reason = "root_unavailable")
        }

        val focusRaw = executor.execute("dumpsys window windows | grep -E 'mCurrentFocus|mFocusedApp' | head -n 4")
        val focus = focusRaw.lowercase(Locale.getDefault())

        val focusPkgHit = sensitivePackageHints.firstOrNull { focus.contains(it) }
        if (focusPkgHit != null) {
            return SensitiveScreenCheckResult(
                blocked = true,
                reason = "sensitive_app_focus:$focusPkgHit",
                evidence = focusRaw.lineSequence().take(2).joinToString(" | ")
            )
        }

        val dumpRaw = executor.execute(
            "uiautomator dump /sdcard/aria_window_dump.xml >/dev/null 2>&1 && head -c 12000 /sdcard/aria_window_dump.xml"
        )
        val dump = dumpRaw.lowercase(Locale.getDefault())

        val keywordHit = sensitiveKeywords.firstOrNull { dump.contains(it) || focus.contains(it) }
        if (keywordHit != null) {
            return SensitiveScreenCheckResult(
                blocked = true,
                reason = "sensitive_keyword:$keywordHit",
                evidence = "keyword=$keywordHit"
            )
        }

        return SensitiveScreenCheckResult(blocked = false, reason = "clean")
    }
}
