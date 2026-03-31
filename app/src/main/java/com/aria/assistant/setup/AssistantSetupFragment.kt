package com.aria.assistant.setup

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.aria.assistant.R
import com.aria.assistant.SetupHost
import com.google.android.material.button.MaterialButton

class AssistantSetupFragment : Fragment(R.layout.fragment_setup_assistant) {

    private lateinit var assistantStatusText: TextView

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        assistantStatusText = view.findViewById(R.id.assistantStatusText)

        view.findViewById<MaterialButton>(R.id.setAssistantButton).setOnClickListener {
            (requireActivity() as? SetupHost)?.requestDefaultAssistant()
        }

        view.findViewById<MaterialButton>(R.id.openDefaultAppsButton).setOnClickListener {
            (requireActivity() as? SetupHost)?.openDefaultAppsSettings()
        }

        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun refreshStatus() {
        val granted = SetupChecks.assistantGranted(requireContext())
        assistantStatusText.text = if (granted) {
            "✅ ARIA set as default assistant"
        } else {
            "❌ ARIA not default assistant (open fallback settings if missing)"
        }
    }
}
