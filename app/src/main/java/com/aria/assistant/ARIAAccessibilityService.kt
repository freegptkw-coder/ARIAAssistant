package com.aria.assistant

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.os.Build

class ARIAAccessibilityService : AccessibilityService() {
    
    companion object {
        var instance: ARIAAccessibilityService? = null
        
        fun isEnabled(): Boolean {
            return instance != null
        }
    }
    
    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        
        val info = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_VIEW_CLICKED or
                        AccessibilityEvent.TYPE_VIEW_FOCUSED or
                        AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                        AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                        AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED
            
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                   AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            notificationTimeout = 100
        }
        
        serviceInfo = info
    }
    
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Can be used to monitor UI events if needed
    }
    
    override fun onInterrupt() {
        // Service interrupted
    }
    
    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }
    
    // ARIA Control Functions
    
    fun clickButton(text: String): Boolean {
        val rootNode = rootInActiveWindow ?: return false
        val node = findNodeByText(rootNode, text)
        
        if (node != null) {
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            node.recycle()
            return true
        }
        
        return false
    }
    
    fun inputText(text: String, targetHint: String? = null): Boolean {
        val rootNode = rootInActiveWindow ?: return false
        val editText = findEditText(rootNode, targetHint)
        
        if (editText != null) {
            editText.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                val arguments = android.os.Bundle()
                arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                editText.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
            }
            
            editText.recycle()
            return true
        }
        
        return false
    }
    
    fun scrollDown(): Boolean {
        val rootNode = rootInActiveWindow ?: return false
        return rootNode.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
    }
    
    fun scrollUp(): Boolean {
        val rootNode = rootInActiveWindow ?: return false
        return rootNode.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
    }
    
    fun getCurrentApp(): String? {
        val event = rootInActiveWindow
        return event?.packageName?.toString()
    }
    
    fun readScreen(): String {
        val rootNode = rootInActiveWindow ?: return "Cannot read screen"
        val texts = mutableListOf<String>()
        extractTexts(rootNode, texts)
        rootNode.recycle()
        return texts.joinToString("\n")
    }
    
    private fun findNodeByText(node: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        if (node.text?.toString()?.contains(text, ignoreCase = true) == true) {
            return node
        }
        
        if (node.contentDescription?.toString()?.contains(text, ignoreCase = true) == true) {
            return node
        }
        
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findNodeByText(child, text)
            if (result != null) {
                return result
            }
            child.recycle()
        }
        
        return null
    }
    
    private fun findEditText(node: AccessibilityNodeInfo, hint: String?): AccessibilityNodeInfo? {
        if (node.className?.toString()?.contains("EditText") == true) {
            if (hint == null || node.hintText?.toString()?.contains(hint, ignoreCase = true) == true) {
                return node
            }
        }
        
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findEditText(child, hint)
            if (result != null) {
                return result
            }
            child.recycle()
        }
        
        return null
    }
    
    private fun extractTexts(node: AccessibilityNodeInfo, texts: MutableList<String>) {
        node.text?.toString()?.let { if (it.isNotBlank()) texts.add(it) }
        node.contentDescription?.toString()?.let { if (it.isNotBlank()) texts.add(it) }
        
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            extractTexts(child, texts)
            child.recycle()
        }
    }
}
