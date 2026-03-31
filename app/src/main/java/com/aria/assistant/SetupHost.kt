package com.aria.assistant

interface SetupHost {
    fun requestAllRuntimePermissions()
    fun requestMissingRuntimePermissions()
    fun openOverlaySettings()
    fun requestDefaultAssistant()
    fun openDefaultAppsSettings()
    fun getAssistantFallbackHint(): String
    fun forceSetAssistantWithRoot()
}
