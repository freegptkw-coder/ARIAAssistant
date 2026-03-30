package com.aria.assistant

import java.io.BufferedReader
import java.io.InputStreamReader

class RootCommandExecutor {
    
    fun execute(command: String): String {
        return try {
            // Execute command with root privileges
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
            
            // Read output
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val output = StringBuilder()
            var line: String?
            
            while (reader.readLine().also { line = it } != null) {
                output.append(line).append("\n")
            }
            
            // Wait for process to complete
            process.waitFor()
            
            val result = output.toString().trim()
            result.ifEmpty { "Command executed successfully" }
        } catch (e: Exception) {
            "Error executing command: ${e.message}"
        }
    }
    
    fun checkRootAccess(): Boolean {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val output = reader.readLine()
            process.waitFor()
            
            // Check if output contains "uid=0" (root)
            output?.contains("uid=0") == true
        } catch (e: Exception) {
            false
        }
    }
}
