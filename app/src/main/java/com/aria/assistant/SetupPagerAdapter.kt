package com.aria.assistant

import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.aria.assistant.setup.AssistantSetupFragment
import com.aria.assistant.setup.OverlaySetupFragment
import com.aria.assistant.setup.PermissionsSetupFragment

class SetupPagerAdapter(activity: AppCompatActivity) : FragmentStateAdapter(activity) {
    override fun getItemCount(): Int = 3

    override fun createFragment(position: Int): Fragment = when (position) {
        0 -> PermissionsSetupFragment()
        1 -> OverlaySetupFragment()
        else -> AssistantSetupFragment()
    }
}
