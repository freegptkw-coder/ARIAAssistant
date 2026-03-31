package com.aria.assistant

import android.app.role.RoleManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.viewpager2.widget.ViewPager2
import com.aria.assistant.setup.SetupChecks
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator

class SetupActivity : AppCompatActivity(), SetupHost {

    companion object {
        private const val REQUEST_RUNTIME_PERMISSIONS = 701
        private const val REQUEST_ASSISTANT_ROLE = 702
    }

    private lateinit var tabLayout: TabLayout
    private lateinit var viewPager: ViewPager2
    private val rootExecutor = RootCommandExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)

        supportActionBar?.title = "ARIA Setup"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        tabLayout = findViewById(R.id.setupTabLayout)
        viewPager = findViewById(R.id.setupViewPager)
        viewPager.adapter = SetupPagerAdapter(this)

        val tabTitles = listOf("Permissions", "Overlay", "Assistant")
        TabLayoutMediator(tabLayout, viewPager) { tab, position ->
            tab.text = tabTitles[position]
        }.attach()
    }

    override fun requestAllRuntimePermissions() {
        ActivityCompat.requestPermissions(
            this,
            SetupChecks.runtimeRequestablePermissions(),
            REQUEST_RUNTIME_PERMISSIONS
        )
    }

    override fun openOverlaySettings() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        startActivity(intent)
    }

    override fun requestDefaultAssistant() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = getSystemService(RoleManager::class.java)
            if (roleManager != null
                && roleManager.isRoleAvailable(RoleManager.ROLE_ASSISTANT)
                && !roleManager.isRoleHeld(RoleManager.ROLE_ASSISTANT)
            ) {
                val roleIntent = roleManager.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT)
                if (!canHandle(roleIntent)) {
                    Toast.makeText(this, "Assistant role screen unavailable, opening fallback", Toast.LENGTH_SHORT).show()
                    openDefaultAppsSettings()
                    return
                }
                @Suppress("DEPRECATION")
                startActivityForResult(
                    roleIntent,
                    REQUEST_ASSISTANT_ROLE
                )
                return
            }
        }

        // OEM fallback chain (some devices hide Digital Assistant menu)
        val fallbackIntents = listOf(
            Intent(Settings.ACTION_VOICE_INPUT_SETTINGS),
            Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
        )

        val opened = fallbackIntents.firstOrNull { canHandle(it) }?.let {
            startActivity(it)
            true
        } ?: false

        if (opened) {
            Toast.makeText(
                this,
                "Set ARIA as Assist app from opened settings screen",
                Toast.LENGTH_LONG
            ).show()
        } else {
            Toast.makeText(this, "No compatible settings screen found. Trying root fallback...", Toast.LENGTH_LONG).show()
            tryRootAssistantFallbackAsync()
        }
    }

    override fun openDefaultAppsSettings() {
        val fallbackIntents = listOf(
            Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS),
            Intent(Settings.ACTION_VOICE_INPUT_SETTINGS),
            Intent(Settings.ACTION_APPLICATION_SETTINGS),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
        )

        val opened = fallbackIntents.firstOrNull { canHandle(it) }?.let {
            startActivity(it)
            true
        } ?: false

        if (!opened) {
            Toast.makeText(this, "Default apps settings unavailable. Trying root fallback...", Toast.LENGTH_LONG).show()
            tryRootAssistantFallbackAsync()
        }
    }

    private fun canHandle(intent: Intent): Boolean {
        return intent.resolveActivity(packageManager) != null
    }

    private fun tryRootAssistantFallbackAsync() {
        Thread {
            val ok = tryRootAssistantFallback()
            runOnUiThread {
                if (ok) {
                    Toast.makeText(
                        this,
                        "Root fallback applied. Re-open this screen to verify.",
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    Toast.makeText(
                        this,
                        "Root fallback failed. Please set manually in system settings.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }.start()
    }

    private fun tryRootAssistantFallback(): Boolean {
        return try {
            if (!rootExecutor.checkRootAccess()) return false

            val component = "$packageName/$packageName.AssistantActivity"
            val result = rootExecutor.execute("settings put secure assistant $component")
            !result.lowercase().contains("error")
        } catch (_: Exception) {
            false
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
