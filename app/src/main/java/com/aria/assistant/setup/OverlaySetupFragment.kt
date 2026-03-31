package com.aria.assistant.setup

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.aria.assistant.R
import com.aria.assistant.SetupHost
import com.google.android.material.button.MaterialButton

class OverlaySetupFragment : Fragment(R.layout.fragment_setup_overlay) {

    private lateinit var overlayStatusText: TextView

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        overlayStatusText = view.findViewById(R.id.overlayStatusText)

        view.findViewById<MaterialButton>(R.id.openOverlaySettingsButton).setOnClickListener {
            (requireActivity() as? SetupHost)?.openOverlaySettings()
        }

        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun refreshStatus() {
        val granted = SetupChecks.overlayGranted(requireContext())
        overlayStatusText.text = if (granted) {
            "✅ Overlay granted"
        } else {
            "❌ Overlay not granted"
        }
    }
}
