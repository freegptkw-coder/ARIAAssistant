package com.aria.assistant

import android.app.role.RoleManager
import android.content.ComponentName
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
import java.util.Locale

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
        viewPager.setPageTransformer { page, position ->
            page.alpha = 0.7f + (1f - kotlin.math.abs(position)) * 0.3f
            page.translationX = -position * 30f
        }

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

    override fun requestMissingRuntimePermissions() {
        val missing = SetupChecks.missingRuntimeRequestablePermissions(this)
        if (missing.isEmpty()) {
            Toast.makeText(this, "All runtime permissions already granted", Toast.LENGTH_SHORT).show()
            return
        }

        ActivityCompat.requestPermissions(this, missing, REQUEST_RUNTIME_PERMISSIONS)
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
                if (canHandle(roleIntent)) {
                    @Suppress("DEPRECATION")
                    startActivityForResult(roleIntent, REQUEST_ASSISTANT_ROLE)
                    return
                }
            }
        }

        openDefaultAppsSettings()
    }

    override fun openDefaultAppsSettings() {
        val intents = mutableListOf<Intent>()

        intents += brandSpecificAssistantIntents()
        intents += Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
        intents += Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)
        intents += Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))

        val opened = intents.firstOrNull { canHandle(it) }?.let {
            startActivity(it)
            true
        } ?: false

        if (opened) {
            Toast.makeText(this, "Set ARIA as Assist app from opened settings", Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(this, "No assistant settings page found, trying root fallback", Toast.LENGTH_LONG).show()
            forceSetAssistantWithRoot()
        }
    }

    override fun getAssistantFallbackHint(): String {
        return when (Build.MANUFACTURER.lowercase(Locale.getDefault())) {
            "xiaomi", "redmi", "poco" -> "MIUI: Settings > Apps > Manage apps > Default apps > Assist & voice input"
            "samsung" -> "Samsung: Settings > Apps > Choose default apps > Digital assistant app"
            "oppo", "realme", "oneplus" -> "ColorOS/RealmeUI: Settings > Apps > Default apps > Assist app"
            "vivo" -> "Funtouch: Settings > Apps > Default apps > Assist app"
            else -> "Android: Settings > Apps > Default apps > Digital assistant app"
        }
    }

    override fun forceSetAssistantWithRoot() {
        Thread {
            val ok = tryRootAssistantFallback()
            runOnUiThread {
                if (ok) {
                    Toast.makeText(this, "Root fallback applied. Re-open Assistant tab to verify.", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this, "Root fallback failed. Set manually from system settings.", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun tryRootAssistantFallback(): Boolean {
        return try {
            if (!rootExecutor.checkRootAccess()) return false
            val component = "$packageName/.AssistantActivity"
            val result = rootExecutor.execute("settings put secure assistant '$component'")
            !result.lowercase(Locale.getDefault()).contains("error")
        } catch (_: Exception) {
            false
        }
    }

    private fun brandSpecificAssistantIntents(): List<Intent> {
        val manufacturer = Build.MANUFACTURER.lowercase(Locale.getDefault())
        val list = mutableListOf<Intent>()

        when (manufacturer) {
            "xiaomi", "redmi", "poco" -> {
                list += Intent().apply {
                    component = ComponentName("com.android.settings", "com.android.settings.SubSettings")
                    putExtra(":settings:show_fragment", "com.android.settings.applications.defaultapps.DefaultAppsSettings")
                }
            }

            "samsung" -> {
                list += Intent().apply {
                    component = ComponentName("com.android.settings", "com.android.settings.Settings\$ManageApplicationsActivity")
                }
            }

            "oppo", "realme", "oneplus", "vivo" -> {
                list += Intent(Settings.ACTION_APPLICATION_SETTINGS)
            }
        }

        return list
    }

    private fun canHandle(intent: Intent): Boolean {
        return intent.resolveActivity(packageManager) != null
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
