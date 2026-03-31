package com.aria.assistant.setup

import android.Manifest
import android.app.role.RoleManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat

data class SetupState(
    val grantedPermissions: Int,
    val totalPermissions: Int,
    val permissionsDone: Boolean,
    val overlayDone: Boolean,
    val assistantDone: Boolean
) {
    val allDone: Boolean
        get() = permissionsDone && overlayDone && assistantDone
}

data class PermissionEntry(
    val title: String,
    val permission: String,
    val description: String,
    val isSpecial: Boolean = false,
    val isBlocker: Boolean = false
)

object SetupChecks {

    private const val OVERLAY_PERMISSION_KEY = "android.permission.SYSTEM_ALERT_WINDOW"

    private fun runtimeCorePermissions(): List<String> = permissionEntriesForUi()
        .filter { !it.isSpecial }
        .map { it.permission }

    fun runtimeRequestablePermissions(): Array<String> {
        val list = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.SEND_SMS,
            Manifest.permission.READ_SMS,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.CAMERA
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            list += Manifest.permission.POST_NOTIFICATIONS
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            list += Manifest.permission.BLUETOOTH_CONNECT
            list += Manifest.permission.BLUETOOTH_SCAN
        }

        return list.toTypedArray()
    }

    fun missingRuntimeRequestablePermissions(context: Context): Array<String> {
        return runtimeRequestablePermissions().filterNot {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }.toTypedArray()
    }

    fun permissionEntriesForUi(): List<PermissionEntry> {
        val entries = mutableListOf(
            PermissionEntry(
                title = "Microphone",
                permission = Manifest.permission.RECORD_AUDIO,
                description = "Required for voice wake word and speech commands.",
                isBlocker = true
            ),
            PermissionEntry(
                title = "Contacts",
                permission = Manifest.permission.READ_CONTACTS,
                description = "Allows ARIA to find and call saved contacts.",
                isBlocker = false
            ),
            PermissionEntry(
                title = "Phone Call",
                permission = Manifest.permission.CALL_PHONE,
                description = "Required to place direct calls from voice commands.",
                isBlocker = true
            ),
            PermissionEntry(
                title = "Send SMS",
                permission = Manifest.permission.SEND_SMS,
                description = "Allows sending text messages via ARIA.",
                isBlocker = true
            ),
            PermissionEntry(
                title = "Read SMS",
                permission = Manifest.permission.READ_SMS,
                description = "Needed to read incoming messages on request.",
                isBlocker = false
            ),
            PermissionEntry(
                title = "Precise Location",
                permission = Manifest.permission.ACCESS_FINE_LOCATION,
                description = "Used for accurate location-based automations.",
                isBlocker = false
            ),
            PermissionEntry(
                title = "Approximate Location",
                permission = Manifest.permission.ACCESS_COARSE_LOCATION,
                description = "Used when fine GPS is unavailable.",
                isBlocker = false
            ),
            PermissionEntry(
                title = "Camera",
                permission = Manifest.permission.CAMERA,
                description = "Required for camera-trigger commands.",
                isBlocker = false
            ),
            PermissionEntry(
                title = "Vibrate",
                permission = Manifest.permission.VIBRATE,
                description = "Allows haptic feedback for assistant events.",
                isBlocker = false
            )
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            entries += PermissionEntry(
                title = "Notifications",
                permission = Manifest.permission.POST_NOTIFICATIONS,
                description = "Allows proactive alerts and reminders.",
                isBlocker = false
            )
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            entries += PermissionEntry(
                title = "Bluetooth Connect",
                permission = Manifest.permission.BLUETOOTH_CONNECT,
                description = "Required to control paired bluetooth devices.",
                isBlocker = false
            )
            entries += PermissionEntry(
                title = "Bluetooth Scan",
                permission = Manifest.permission.BLUETOOTH_SCAN,
                description = "Required to discover nearby bluetooth devices.",
                isBlocker = false
            )
        }

        entries += PermissionEntry(
            title = "Display Over Apps",
            permission = OVERLAY_PERMISSION_KEY,
            description = "Special permission for floating ARIA controls.",
            isSpecial = true,
            isBlocker = true
        )

        return entries
    }

    fun isPermissionGranted(context: Context, permission: String): Boolean {
        if (permission == OVERLAY_PERMISSION_KEY) return Settings.canDrawOverlays(context)
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

    fun overlayGranted(context: Context): Boolean = Settings.canDrawOverlays(context)

    fun assistantGranted(context: Context): Boolean {
        val secureAssistant = Settings.Secure.getString(context.contentResolver, "assistant")
        val secureMatch = secureAssistant?.contains(context.packageName) == true

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = context.getSystemService(RoleManager::class.java)
            val roleHeld = roleManager?.isRoleHeld(RoleManager.ROLE_ASSISTANT) == true
            roleHeld || secureMatch
        } else {
            secureMatch
        }
    }

    fun evaluate(context: Context): SetupState {
        val corePermissions = runtimeCorePermissions()
        val grantedCore = corePermissions.count { isPermissionGranted(context, it) }

        return SetupState(
            grantedPermissions = grantedCore,
            totalPermissions = corePermissions.size,
            permissionsDone = grantedCore == corePermissions.size,
            overlayDone = overlayGranted(context),
            assistantDone = assistantGranted(context)
        )
    }
}
