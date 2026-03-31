package com.aria.assistant

import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

class RootCommandExecutor {
    
    fun execute(command: String): String {
        return try {
            // Execute command with root privileges
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))

            val completed = process.waitFor(10, TimeUnit.SECONDS)
            if (!completed) {
                process.destroyForcibly()
                return "Error executing command: timeout"
            }

            val output = BufferedReader(InputStreamReader(process.inputStream)).use { it.readText() }.trim()
            val error = BufferedReader(InputStreamReader(process.errorStream)).use { it.readText() }.trim()

            when {
                error.isNotBlank() -> "Error executing command: $error"
                output.isNotBlank() -> output
                else -> "Command executed successfully"
            }
        } catch (e: Exception) {
            "Error executing command: ${e.message}"
        }
    }
    
    fun checkRootAccess(): Boolean {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
            val completed = process.waitFor(5, TimeUnit.SECONDS)
            if (!completed) {
                process.destroyForcibly()
                return false
            }

            val output = BufferedReader(InputStreamReader(process.inputStream)).use { it.readLine() }
            
            // Check if output contains "uid=0" (root)
            output?.contains("uid=0") == true
        } catch (e: Exception) {
            false
        }
    }
}
