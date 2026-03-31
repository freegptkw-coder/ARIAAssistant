package com.aria.assistant

import android.app.role.RoleManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
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
                @Suppress("DEPRECATION")
                startActivityForResult(
                    roleManager.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT),
                    REQUEST_ASSISTANT_ROLE
                )
                return
            }
        }

        startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS))
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
