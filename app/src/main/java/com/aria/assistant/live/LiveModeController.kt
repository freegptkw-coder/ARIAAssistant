package com.aria.assistant.live

import android.content.Context
import android.content.Intent
import android.os.Build
import android.app.Activity

object LiveModeController {

    fun requestSession(context: Context) {
        val intent = Intent(context, LiveConsentActivity::class.java)
        if (context !is Activity) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    fun startService(context: Context) {
        val intent = Intent(context, LiveModeService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    fun stop(context: Context) {
        ConsentStore.endSession(context)
        ConsentStore.setLiveEnabled(context, false)
        context.stopService(Intent(context, LiveModeService::class.java))
    }
}
