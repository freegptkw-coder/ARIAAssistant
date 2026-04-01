package com.aria.assistant

import android.content.Context
import android.os.Process
import android.util.Log
import com.aria.assistant.live.ConsentStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.system.exitProcess

object ErrorRecoveryManager {

    private const val PREF = "ARIA_PREFS"
    private const val KEY_CRASH_LOG = "aria_crash_log"
    private const val KEY_PENDING_CRASH = "aria_pending_crash"
    private const val KEY_CRASH_LOGGING_ENABLED = "aria_crash_logging_enabled"
    private const val KEY_AUTO_HEAL_ENABLED = "aria_auto_heal_enabled"
    private const val KEY_ROOT_DIAG_ENABLED = "aria_root_diag_enabled"
    private const val MAX_LOG_CHARS = 120_000
    private const val LOG_FILE_PATH = "/sdcard/123/aria_error_log.txt"

    @Volatile
    private var installed = false

    fun install(context: Context) {
        if (installed) return
        val appContext = context.applicationContext
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { handleUncaughtCrash(appContext, thread, throwable) }

            if (previousHandler != null) {
                previousHandler.uncaughtException(thread, throwable)
            } else {
                Process.killProcess(Process.myPid())
                exitProcess(10)
            }
        }
        installed = true
    }

    fun isCrashLoggingEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_CRASH_LOGGING_ENABLED, true)
    }

    fun setCrashLoggingEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_CRASH_LOGGING_ENABLED, enabled).apply()
    }

    fun isAutoHealEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_AUTO_HEAL_ENABLED, true)
    }

    fun setAutoHealEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTO_HEAL_ENABLED, enabled).apply()
    }

    fun isRootDiagnosticsEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_ROOT_DIAG_ENABLED, true)
    }

    fun setRootDiagnosticsEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ROOT_DIAG_ENABLED, enabled).apply()
    }

    fun getCrashLog(context: Context): String {
        val fromPrefs = prefs(context).getString(KEY_CRASH_LOG, "No error logs yet.").orEmpty()
        return fromPrefs.ifBlank { "No error logs yet." }
    }

    fun clearCrashLog(context: Context) {
        prefs(context).edit().remove(KEY_CRASH_LOG).remove(KEY_PENDING_CRASH).apply()
        runCatching { File(LOG_FILE_PATH).delete() }
    }

    fun getCrashLogFilePath(): String = LOG_FILE_PATH

    fun processPendingRecovery(context: Context) {
        val pending = prefs(context).getString(KEY_PENDING_CRASH, "").orEmpty()
        if (pending.isBlank()) return

        val summary = runSelfHealInternal(context, pending, source = "auto")
        appendLog(context, "AUTO_HEAL", summary)
        prefs(context).edit().remove(KEY_PENDING_CRASH).apply()
    }

    fun runSelfHealNow(context: Context): String {
        val pending = prefs(context).getString(KEY_PENDING_CRASH, "manual_trigger").orEmpty()
        val summary = runSelfHealInternal(context, pending.ifBlank { "manual_trigger" }, source = "manual")
        appendLog(context, "MANUAL_HEAL", summary)
        return summary
    }

    private fun handleUncaughtCrash(context: Context, thread: Thread, throwable: Throwable) {
        if (!isCrashLoggingEnabled(context)) return

        val stack = Log.getStackTraceString(throwable).ifBlank { throwable.toString() }
        val signature = "${throwable::class.java.simpleName}:${stack.take(900)}"
        val brief = buildString {
            append("thread=${thread.name}")
            append("\nerror=${throwable::class.java.name}")
            append("\nmessage=${throwable.message.orEmpty()}")
            append("\nstack=\n")
            append(stack.take(5000))
        }

        appendLog(context, "CRASH", brief)
        prefs(context).edit().putString(KEY_PENDING_CRASH, signature).apply()
    }

    private fun runSelfHealInternal(context: Context, signal: String, source: String): String {
        val actions = mutableListOf<String>()
        val lower = signal.lowercase(Locale.getDefault())

        if (!isAutoHealEnabled(context)) {
            actions += "auto_heal_disabled"
        } else {
            var changedLive = false

            if (
                lower.contains("livemodeservice") ||
                lower.contains("audiorecord") ||
                lower.contains("speechrecognizer") ||
                lower.contains("websocket") ||
                lower.contains("memories")
            ) {
                ConsentStore.setLiveEnabled(context, false)
                ConsentStore.setAlwaysOn(context, false)
                actions += "disabled_live_mode"
                changedLive = true
            }

            if (lower.contains("outofmemory") || lower.contains("bitmap")) {
                ConsentStore.setVisionIntervalMs(context, 8000L)
                ConsentStore.setAvatarEnabled(context, false)
                ConsentStore.setMemoriesEnabled(context, false)
                actions += "vision_safe_mode_enabled"
            }

            if (lower.contains("record_audio") || lower.contains("securityexception")) {
                ConsentStore.setLiveEnabled(context, false)
                actions += "mic_guard_applied"
                changedLive = true
            }

            runCatching {
                val deleted = File("/sdcard/aria_live_frame.png").delete()
                if (deleted) actions += "temp_frame_cleaned"
            }

            if (changedLive) {
                runRootCommandIfAllowed(
                    context,
                    "am stopservice com.aria.assistant/.live.LiveModeService",
                    "stop_live_service_for_recovery",
                    actions
                )
            }
        }

        if (isRootDiagnosticsEnabled(context)) {
            val diag = runRootCommandIfAllowed(
                context,
                "logcat -d -t 120 *:E",
                "collect_error_logcat",
                actions
            )
            if (!diag.isNullOrBlank()) {
                appendLog(context, "ROOT_DIAG", diag.take(2500))
            }
        }

        if (actions.isEmpty()) actions += "no_changes"
        return "source=$source | signal=${signal.take(80)} | actions=${actions.joinToString(",")}"
    }

    private fun runRootCommandIfAllowed(
        context: Context,
        command: String,
        label: String,
        actions: MutableList<String>
    ): String? {
        val decision = RootSafetyPolicy.evaluate(context, command)
        if (!decision.allowed) {
            RootSafetyPolicy.appendAudit(context, command, "BLOCKED", "auto_heal:$label:${decision.reason}")
            actions += "root_blocked:$label"
            return null
        }

        val executor = RootCommandExecutor()
        if (!executor.checkRootAccess()) {
            actions += "root_unavailable:$label"
            return null
        }

        val out = executor.execute(command)
        val ok = !out.startsWith("Error", ignoreCase = true)
        RootSafetyPolicy.appendAudit(
            context,
            command,
            if (ok) "OK" else "FAIL",
            "auto_heal:$label:${out.take(140)}"
        )
        actions += if (ok) "root_ok:$label" else "root_fail:$label"
        return out
    }

    private fun appendLog(context: Context, tag: String, body: String) {
        val current = prefs(context).getString(KEY_CRASH_LOG, "").orEmpty()
        val ts = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        val entry = "[$ts] $tag\n${body.trim()}"
        val merged = if (current.isBlank()) entry else "$current\n\n$entry"
        val clipped = merged.takeLast(MAX_LOG_CHARS)
        prefs(context).edit().putString(KEY_CRASH_LOG, clipped).apply()

        runCatching {
            val file = File(LOG_FILE_PATH)
            file.parentFile?.mkdirs()
            file.writeText(clipped)
        }
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
}
