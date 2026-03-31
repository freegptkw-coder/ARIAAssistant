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
    val isSpecial: Boolean = false
)

object SetupChecks {

    private const val OVERLAY_PERMISSION_KEY = "android.permission.SYSTEM_ALERT_WINDOW"

    private fun runtimeCorePermissions(): List<String> {
        val base = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.SEND_SMS,
            Manifest.permission.READ_SMS,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.CAMERA,
            Manifest.permission.VIBRATE
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            base += Manifest.permission.POST_NOTIFICATIONS
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            base += Manifest.permission.BLUETOOTH_CONNECT
            base += Manifest.permission.BLUETOOTH_SCAN
        }

        return base
    }

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

    fun permissionEntriesForUi(): List<PermissionEntry> {
        val entries = mutableListOf(
            PermissionEntry("Microphone", Manifest.permission.RECORD_AUDIO),
            PermissionEntry("Contacts", Manifest.permission.READ_CONTACTS),
            PermissionEntry("Phone Call", Manifest.permission.CALL_PHONE),
            PermissionEntry("Send SMS", Manifest.permission.SEND_SMS),
            PermissionEntry("Read SMS", Manifest.permission.READ_SMS),
            PermissionEntry("Precise Location", Manifest.permission.ACCESS_FINE_LOCATION),
            PermissionEntry("Approximate Location", Manifest.permission.ACCESS_COARSE_LOCATION),
            PermissionEntry("Camera", Manifest.permission.CAMERA),
            PermissionEntry("Vibrate", Manifest.permission.VIBRATE)
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            entries += PermissionEntry("Notifications", Manifest.permission.POST_NOTIFICATIONS)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            entries += PermissionEntry("Bluetooth Connect", Manifest.permission.BLUETOOTH_CONNECT)
            entries += PermissionEntry("Bluetooth Scan", Manifest.permission.BLUETOOTH_SCAN)
        }

        entries += PermissionEntry("Display Over Apps", OVERLAY_PERMISSION_KEY, isSpecial = true)

        return entries
    }

    fun isPermissionGranted(context: Context, permission: String): Boolean {
        if (permission == OVERLAY_PERMISSION_KEY) return Settings.canDrawOverlays(context)

        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

    fun overlayGranted(context: Context): Boolean = Settings.canDrawOverlays(context)

    fun assistantGranted(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = context.getSystemService(RoleManager::class.java)
            roleManager?.isRoleHeld(RoleManager.ROLE_ASSISTANT) == true
        } else {
            val assistant = Settings.Secure.getString(context.contentResolver, "assistant")
            assistant?.contains(context.packageName) == true
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
