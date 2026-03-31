package com.aria.assistant.automation

import com.google.gson.Gson
import com.google.gson.JsonParser
import java.util.Locale

object SafeIntentParser {

    private val gson = Gson()

    private val appAliases = mapOf(
        "whatsapp" to "whatsapp",
        "wa" to "whatsapp",
        "youtube" to "youtube",
        "yt" to "youtube",
        "chrome" to "chrome",
        "google" to "chrome",
        "facebook" to "facebook",
        "fb" to "facebook",
        "messenger" to "messenger",
        "instagram" to "instagram",
        "insta" to "instagram",
        "telegram" to "telegram",
        "calc" to "calculator",
        "calculator" to "calculator",
        "notepad" to "notepad",
        "notes" to "notepad",
        "settings" to "settings"
    )

    fun parseFromAssistantText(text: String): ParsedAutomationCommand? {
        val json = extractJsonBlock(text) ?: return null
        val obj = runCatching { JsonParser.parseString(json).asJsonObject }.getOrNull() ?: return null

        val action = obj.get("action")?.asString ?: return null
        val envelope = gson.fromJson(obj, SafeIntentEnvelope::class.java)

        val cleanText = text.replace(json, "").replace("```json", "").replace("```", "").trim()
        val ack = cleanText.ifBlank {
            when (action) {
                SafeIntentActions.LAUNCH_MULTIPLE_APPS -> "Thik ache, ami safe mode e app gulo launch kortesi."
                else -> "Thik ache, safe automation request receive hoyeche."
            }
        }

        return ParsedAutomationCommand(envelope = envelope, acknowledgement = ack)
    }

    fun parseFromUserVoiceOrText(input: String): ParsedAutomationCommand? {
        val text = input.lowercase(Locale.getDefault())

        if (isEmpathicStatusOnly(text)) {
            return ParsedAutomationCommand(
                envelope = SafeIntentEnvelope(action = SafeIntentActions.AUTOMATION_REQUEST, tasks = emptyList(), source = "voice"),
                acknowledgement = "Thik ache, apni nishchinte jaan. Ami standby te thakchi, fire ashle ami ready 💙",
                fromVoiceHeuristic = true
            )
        }

        val apps = detectApps(text)
        val wantsOpen = listOf("open", "launch", "khulo", "open kore", "chalu", "start").any { text.contains(it) }

        val tasks = mutableListOf<SafeTask>()

        if (wantsOpen && apps.isNotEmpty()) {
            tasks += SafeTask(
                type = SafeTaskTypes.LAUNCH_MULTIPLE_APPS,
                targetApps = apps,
                riskLevel = "low",
                requireConfirmation = false
            )
        }

        if (text.contains("sms") && (text.contains("read") || text.contains("shunao") || text.contains("sunao") || text.contains("poro"))) {
            tasks += SafeTask(
                type = SafeTaskTypes.READ_INCOMING_SMS,
                enabled = true,
                riskLevel = "low",
                requireConfirmation = false
            )
        }

        if (tasks.isEmpty()) return null

        val ack = buildString {
            append("Thik ache, ami sob control e niye nicchi. ")
            if (apps.isNotEmpty()) append("${apps.joinToString(", ")} open korte request pathacchi. ")
            if (tasks.any { it.type == SafeTaskTypes.READ_INCOMING_SMS }) append("Incoming SMS read mode-o on korchi.")
        }.trim()

        val envelope = if (tasks.size == 1 && tasks.first().type == SafeTaskTypes.LAUNCH_MULTIPLE_APPS) {
            SafeIntentEnvelope(
                action = SafeIntentActions.LAUNCH_MULTIPLE_APPS,
                targetApps = tasks.first().targetApps,
                source = "voice"
            )
        } else {
            SafeIntentEnvelope(
                action = SafeIntentActions.AUTOMATION_REQUEST,
                tasks = tasks,
                source = "voice"
            )
        }

        return ParsedAutomationCommand(envelope, ack, fromVoiceHeuristic = true)
    }

    fun toPrettyJson(envelope: SafeIntentEnvelope): String = gson.toJson(envelope)

    private fun extractJsonBlock(text: String): String? {
        // Avoid fragile regex parsing for fenced blocks; use deterministic scanning.
        val lower = text.lowercase(Locale.getDefault())
        val marker = "```json"
        val markerIndex = lower.indexOf(marker)

        if (markerIndex >= 0) {
            val lineEnd = text.indexOf('\n', markerIndex)
            val contentStart = if (lineEnd >= 0) lineEnd + 1 else markerIndex + marker.length
            val fenceEnd = text.indexOf("```", contentStart)
            if (fenceEnd > contentStart) {
                val fencedContent = text.substring(contentStart, fenceEnd).trim()
                extractFirstJsonObject(fencedContent)?.let { return it }
            }
        }

        return extractFirstJsonObject(text)
    }

    private fun extractFirstJsonObject(input: String): String? {
        var start = -1
        var depth = 0
        var inString = false
        var escaped = false

        input.forEachIndexed { index, ch ->
            if (inString) {
                when {
                    escaped -> escaped = false
                    ch == '\' -> escaped = true
                    ch == '"' -> inString = false
                }
                return@forEachIndexed
            }

            when (ch) {
                '"' -> inString = true
                '{' -> {
                    if (depth == 0) start = index
                    depth++
                }
                '}' -> {
                    if (depth > 0) {
                        depth--
                        if (depth == 0 && start >= 0) {
                            val candidate = input.substring(start, index + 1)
                            val valid = runCatching {
                                JsonParser.parseString(candidate).asJsonObject
                            }.isSuccess
                            if (valid) return candidate
                            start = -1
                        }
                    }
                }
            }
        }

        return null
    }

    private fun detectApps(lowerText: String): List<String> {
        val found = linkedSetOf<String>()
        appAliases.forEach { (alias, canonical) ->
            val pattern = Regex("(^|[^a-z0-9])${Regex.escape(alias)}([^a-z0-9]|$)")
            if (pattern.containsMatchIn(lowerText)) {
                found += canonical
            }
        }
        return found.toList()
    }

    private fun isEmpathicStatusOnly(text: String): Boolean {
        val cues = listOf("baire jach", "busy", "offline", "ashchi", "aschi", "2 minute", "ektu pore", "gtg")
        val hasCue = cues.any { text.contains(it) }
        val hasAction = listOf("open", "launch", "sms", "call", "message", "post").any { text.contains(it) }
        return hasCue && !hasAction
    }
}
