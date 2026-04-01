package com.aria.assistant.live

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class LiveBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action.orEmpty()
        val shouldStart = action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_MY_PACKAGE_REPLACED
        if (!shouldStart) return

        if (!ConsentStore.isLiveEnabled(context)) return

        if (ConsentStore.isAlwaysOn(context) && !ConsentStore.isSessionActive(context)) {
            ConsentStore.startSession(context, 240)
        }

        LiveModeController.startService(context)
        AuditLogger.log(context, "live_autostart:$action")
    }
}
