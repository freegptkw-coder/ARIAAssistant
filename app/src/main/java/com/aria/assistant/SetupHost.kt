package com.aria.assistant

interface SetupHost {
    fun requestAllRuntimePermissions()
    fun openOverlaySettings()
    fun requestDefaultAssistant()
}
