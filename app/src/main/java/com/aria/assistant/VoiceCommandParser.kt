package com.aria.assistant

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.AlarmClock
import android.widget.Toast

data class VoiceCommand(
    val type: CommandType,
    val parameters: Map<String, String> = emptyMap(),
    val shouldExecute: Boolean = true
)

enum class CommandType {
    CALL, MESSAGE, OPEN_APP, SET_ALARM, SLEEP_MODE, STUDY_MODE, MEETING_MODE, NONE
}

object VoiceCommandParser {
    
    fun parseCommand(text: String): VoiceCommand {
        val lowerText = text.lowercase()
        
        // Call commands
        when {
            lowerText.contains("call") || lowerText.contains("phone") || lowerText.contains("dial") -> {
                val name = extractNameAfter(text, listOf("call", "phone", "dial"))
                if (name.isNotEmpty()) {
                    return VoiceCommand(CommandType.CALL, mapOf("name" to name))
                }
            }
            
            // Message/SMS commands
            lowerText.contains("message") || lowerText.contains("text") || lowerText.contains("sms") -> {
                val name = extractNameAfter(text, listOf("message", "text", "sms"))
                if (name.isNotEmpty()) {
                    return VoiceCommand(CommandType.MESSAGE, mapOf("name" to name))
                }
            }
            
            // Open app commands
            lowerText.contains("open") || lowerText.contains("launch") || lowerText.contains("start") -> {
                val appName = extractNameAfter(text, listOf("open", "launch", "start"))
                if (appName.isNotEmpty()) {
                    return VoiceCommand(CommandType.OPEN_APP, mapOf("app" to appName))
                }
            }
            
            // Alarm commands
            lowerText.contains("alarm") || lowerText.contains("wake me") -> {
                val time = extractTime(text)
                if (time != null) {
                    return VoiceCommand(CommandType.SET_ALARM, mapOf("hour" to time.first.toString(), "minute" to time.second.toString()))
                }
            }

            // Routine shortcuts
            lowerText.contains("sleep mode") -> {
                return VoiceCommand(CommandType.SLEEP_MODE)
            }
            lowerText.contains("study mode") -> {
                return VoiceCommand(CommandType.STUDY_MODE)
            }
            lowerText.contains("meeting mode") -> {
                return VoiceCommand(CommandType.MEETING_MODE)
            }
        }
        
        return VoiceCommand(CommandType.NONE, shouldExecute = false)
    }
    
    fun executeCommand(context: Context, command: VoiceCommand): String {
        return when(command.type) {
            CommandType.CALL -> {
                val name = command.parameters["name"] ?: ""
                val intent = Intent(Intent.ACTION_DIAL).apply {
                    // In real implementation, search contacts for number
                    // For now, just open dialer
                }
                context.startActivity(intent)
                "Opening dialer for $name জান 📞"
            }
            
            CommandType.MESSAGE -> {
                val name = command.parameters["name"] ?: ""
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    data = Uri.parse("sms:")
                }
                context.startActivity(intent)
                "Opening messages for $name জান 💬"
            }
            
            CommandType.OPEN_APP -> {
                val appName = command.parameters["app"] ?: ""
                val packageName = getPackageForApp(appName)
                
                if (packageName != null) {
                    try {
                        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
                        if (intent != null) {
                            context.startActivity(intent)
                            "Opening $appName জান! 📱"
                        } else {
                            "Sorry জান, $appName install nai 😔"
                        }
                    } catch (e: Exception) {
                        "Couldn't open $appName, জান 😔"
                    }
                } else {
                    "Sorry জান, ami $appName app ta chinina 🤔"
                }
            }
            
            CommandType.SET_ALARM -> {
                val hour = command.parameters["hour"]?.toIntOrNull() ?: 7
                val minute = command.parameters["minute"]?.toIntOrNull() ?: 0
                
                val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                    putExtra(AlarmClock.EXTRA_HOUR, hour)
                    putExtra(AlarmClock.EXTRA_MINUTES, minute)
                    putExtra(AlarmClock.EXTRA_SKIP_UI, false)
                }
                
                try {
                    context.startActivity(intent)
                    "Alarm set korchi ${hour}:${minute.toString().padStart(2, '0')} te জান! ⏰"
                } catch (e: Exception) {
                    "Alarm set korte parlam na জান 😔"
                }
            }
            
            CommandType.SLEEP_MODE -> {
                try {
                    val root = RootCommandExecutor()
                    root.execute("settings put global zen_mode 1 && svc wifi disable && settings put system screen_brightness 10")
                    "Sleep mode on, jaan 🌙 WiFi off, DND on, brightness low."
                } catch (e: Exception) {
                    "Sleep mode on korte parlam na 😔"
                }
            }

            CommandType.STUDY_MODE -> {
                try {
                    val root = RootCommandExecutor()
                    root.execute("settings put global zen_mode 1 && settings put system screen_brightness 140 && svc wifi enable")
                    "Study mode active 📚 DND on, medium brightness, WiFi on."
                } catch (e: Exception) {
                    "Study mode start korte parlam na 😔"
                }
            }

            CommandType.MEETING_MODE -> {
                try {
                    val root = RootCommandExecutor()
                    root.execute("settings put global zen_mode 1 && settings put system screen_off_timeout 60000")
                    "Meeting mode active 🤝 DND on, screen timeout short."
                } catch (e: Exception) {
                    "Meeting mode start korte parlam na 😔"
                }
            }
            
            CommandType.NONE -> ""
        }
    }
    
    private fun extractNameAfter(text: String, keywords: List<String>): String {
        val lowerText = text.lowercase()
        for (keyword in keywords) {
            val index = lowerText.indexOf(keyword)
            if (index != -1) {
                val afterKeyword = text.substring(index + keyword.length).trim()
                // Take first word or two words
                val words = afterKeyword.split(" ").take(2)
                return words.joinToString(" ").trim()
            }
        }
        return ""
    }
    
    private fun extractTime(text: String): Pair<Int, Int>? {
        // Simple time extraction: looks for patterns like "7 AM", "7:30", "19:00"
        val timeRegex = """(\d{1,2}):?(\d{2})?\s*(am|pm)?""".toRegex(RegexOption.IGNORE_CASE)
        val match = timeRegex.find(text)
        
        if (match != null) {
            var hour = match.groupValues[1].toIntOrNull() ?: return null
            val minute = match.groupValues[2].toIntOrNull() ?: 0
            val ampm = match.groupValues[3].lowercase()
            
            if (ampm == "pm" && hour < 12) hour += 12
            if (ampm == "am" && hour == 12) hour = 0
            
            return Pair(hour, minute)
        }
        
        return null
    }
    
    private fun getPackageForApp(appName: String): String? {
        val commonApps = mapOf(
            "chrome" to "com.android.chrome",
            "youtube" to "com.google.android.youtube",
            "whatsapp" to "com.whatsapp",
            "facebook" to "com.facebook.katana",
            "instagram" to "com.instagram.android",
            "twitter" to "com.twitter.android",
            "gmail" to "com.google.android.gm",
            "maps" to "com.google.android.apps.maps",
            "camera" to "com.android.camera2",
            "gallery" to "com.google.android.apps.photos",
            "settings" to "com.android.settings",
            "calculator" to "com.google.android.calculator",
            "clock" to "com.google.android.deskclock",
            "calendar" to "com.google.android.calendar",
            "contacts" to "com.google.android.contacts"
        )
        
        return commonApps[appName.lowercase()]
    }
}
