package com.aria.assistant

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader

object AdvancedPhoneControl {
    
    // Execute root command
    suspend fun executeRootCommand(command: String): String {
        return withContext(Dispatchers.IO) {
            try {
                val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
                val reader = BufferedReader(InputStreamReader(process.inputStream))
                val output = reader.readText()
                process.waitFor()
                output
            } catch (e: Exception) {
                "Error: ${e.message}"
            }
        }
    }
    
    // Screen control
    suspend fun turnScreenOn(): String {
        return executeRootCommand("input keyevent 26 && input keyevent 82")
    }
    
    suspend fun turnScreenOff(): String {
        return executeRootCommand("input keyevent 26")
    }
    
    suspend fun setBrightness(level: Int): String {
        val brightness = level.coerceIn(0, 255)
        return executeRootCommand("settings put system screen_brightness $brightness")
    }
    
    // Network control
    suspend fun toggleWifi(enable: Boolean): String {
        return if (enable) {
            executeRootCommand("svc wifi enable")
        } else {
            executeRootCommand("svc wifi disable")
        }
    }
    
    suspend fun toggleMobileData(enable: Boolean): String {
        return if (enable) {
            executeRootCommand("svc data enable")
        } else {
            executeRootCommand("svc data disable")
        }
    }
    
    suspend fun toggleBluetooth(enable: Boolean): String {
        return if (enable) {
            executeRootCommand("service call bluetooth_manager 6")
        } else {
            executeRootCommand("service call bluetooth_manager 8")
        }
    }
    
    // Volume control
    suspend fun setVolume(stream: String, level: Int): String {
        val streamType = when(stream.lowercase()) {
            "media", "music" -> 3
            "ring", "ringtone" -> 2
            "notification" -> 5
            "alarm" -> 4
            else -> 3
        }
        return executeRootCommand("media volume --stream $streamType --set $level")
    }
    
    suspend fun getVolume(stream: String): String {
        val streamType = when(stream.lowercase()) {
            "media", "music" -> 3
            "ring", "ringtone" -> 2
            "notification" -> 5
            "alarm" -> 4
            else -> 3
        }
        return executeRootCommand("media volume --stream $streamType --get")
    }
    
    // Battery info
    suspend fun getBatteryLevel(): String {
        return executeRootCommand("dumpsys battery | grep level")
    }
    
    suspend fun getBatteryStatus(): String {
        return executeRootCommand("dumpsys battery")
    }
    
    // App control
    suspend fun forceStopApp(packageName: String): String {
        return executeRootCommand("am force-stop $packageName")
    }
    
    suspend fun clearAppData(packageName: String): String {
        return executeRootCommand("pm clear $packageName")
    }
    
    suspend fun grantPermission(packageName: String, permission: String): String {
        return executeRootCommand("pm grant $packageName $permission")
    }
    
    // System info
    suspend fun getInstalledApps(): String {
        return executeRootCommand("pm list packages")
    }
    
    suspend fun getCurrentApp(): String {
        return executeRootCommand("dumpsys window | grep mCurrentFocus")
    }
    
    // Screenshot
    suspend fun takeScreenshot(path: String): String {
        return executeRootCommand("screencap -p $path")
    }
    
    // Tap coordinates
    suspend fun tapScreen(x: Int, y: Int): String {
        return executeRootCommand("input tap $x $y")
    }
    
    suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, duration: Int = 300): String {
        return executeRootCommand("input swipe $x1 $y1 $x2 $y2 $duration")
    }
    
    // Accessibility actions via shell
    suspend fun clickByText(text: String): Boolean {
        val service = ARIAAccessibilityService.instance
        return service?.clickButton(text) ?: false
    }
    
    suspend fun typeText(text: String): Boolean {
        val service = ARIAAccessibilityService.instance
        return service?.inputText(text) ?: false
    }
    
    suspend fun readCurrentScreen(): String {
        val service = ARIAAccessibilityService.instance
        return service?.readScreen() ?: "Accessibility service not enabled"
    }
    
    // Parse natural commands
    fun parseAdvancedCommand(text: String): Pair<String, () -> Unit>? {
        val lower = text.lowercase()
        
        return when {
            lower.contains("screen off") || lower.contains("turn off screen") -> 
                "Turning screen off" to { /* will be handled async */ }
            
            lower.contains("screen on") || lower.contains("turn on screen") ->
                "Turning screen on" to { }
            
            lower.contains("wifi on") || lower.contains("enable wifi") ->
                "Enabling WiFi" to { }
            
            lower.contains("wifi off") || lower.contains("disable wifi") ->
                "Disabling WiFi" to { }
            
            lower.contains("read screen") || lower.contains("what's on screen") ->
                "Reading screen" to { }
            
            lower.contains("brightness") -> {
                val level = extractNumber(text)
                if (level != null) {
                    "Setting brightness to $level" to { }
                } else null
            }
            
            lower.contains("volume") -> {
                val level = extractNumber(text)
                if (level != null) {
                    "Setting volume to $level" to { }
                } else null
            }
            
            else -> null
        }
    }
    
    private fun extractNumber(text: String): Int? {
        val regex = """\d+""".toRegex()
        return regex.find(text)?.value?.toIntOrNull()
    }
}
