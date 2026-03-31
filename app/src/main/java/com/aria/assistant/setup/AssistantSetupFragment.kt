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
    private lateinit var fallbackHintText: TextView

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        assistantStatusText = view.findViewById(R.id.assistantStatusText)
        fallbackHintText = view.findViewById(R.id.assistantFallbackHint)

        val host = requireActivity() as? SetupHost
        fallbackHintText.text = host?.getAssistantFallbackHint() ?: "Open default apps settings and choose ARIA."

        view.findViewById<MaterialButton>(R.id.setAssistantButton).setOnClickListener {
            host?.requestDefaultAssistant()
        }

        view.findViewById<MaterialButton>(R.id.openDefaultAppsButton).setOnClickListener {
            host?.openDefaultAppsSettings()
        }

        view.findViewById<MaterialButton>(R.id.forceRootAssistantButton).setOnClickListener {
            host?.forceSetAssistantWithRoot()
        }

        view.findViewById<MaterialButton>(R.id.verifyAssistantButton).setOnClickListener {
            refreshStatus()
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
            "❌ ARIA not default assistant"
        }
    }
}
