package com.aria.assistant.live

import android.content.Context
import android.content.Intent
import android.os.Build

object LiveModeController {

    fun start(context: Context) {
        val intent = Intent(context, LiveModeService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    fun stop(context: Context) {
        context.stopService(Intent(context, LiveModeService::class.java))
    }
}
