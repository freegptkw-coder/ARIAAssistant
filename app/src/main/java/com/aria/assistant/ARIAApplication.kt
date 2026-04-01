package com.aria.assistant

import android.app.Application

class ARIAApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        ErrorRecoveryManager.install(this)

        Thread {
            ErrorRecoveryManager.processPendingRecovery(this)
        }.start()
    }
}
