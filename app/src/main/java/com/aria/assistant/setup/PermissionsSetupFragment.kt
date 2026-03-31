package com.aria.assistant.setup

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.aria.assistant.R
import com.aria.assistant.SetupHost
import com.google.android.material.button.MaterialButton

class PermissionsSetupFragment : Fragment(R.layout.fragment_setup_permissions) {

    private lateinit var adapter: PermissionStatusAdapter
    private lateinit var summaryText: TextView
    private lateinit var explainTitle: TextView
    private lateinit var explainBody: TextView

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        summaryText = view.findViewById(R.id.permissionsSummaryText)
        explainTitle = view.findViewById(R.id.permissionExplainTitle)
        explainBody = view.findViewById(R.id.permissionExplainBody)

        val recycler: RecyclerView = view.findViewById(R.id.permissionsRecycler)
        recycler.layoutManager = LinearLayoutManager(requireContext())
        adapter = PermissionStatusAdapter { selected ->
            explainTitle.text = selected.title
            explainBody.text = selected.description + if (selected.blocker) "\n\nThis is a blocker permission for full ARIA flow." else ""
        }
        recycler.adapter = adapter

        view.findViewById<MaterialButton>(R.id.grantAllPermissionsButton).setOnClickListener {
            (requireActivity() as? SetupHost)?.requestAllRuntimePermissions()
        }

        view.findViewById<MaterialButton>(R.id.grantMissingPermissionsButton).setOnClickListener {
            (requireActivity() as? SetupHost)?.requestMissingRuntimePermissions()
        }

        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun refreshStatus() {
        val entries = SetupChecks.permissionEntriesForUi()
        val uiItems = entries.map {
            PermissionUiItem(
                title = it.title,
                description = it.description,
                granted = SetupChecks.isPermissionGranted(requireContext(), it.permission),
                blocker = it.isBlocker
            )
        }

        val granted = uiItems.count { it.granted }
        summaryText.text = "$granted/${uiItems.size} permissions granted"

        val firstMissing = uiItems.firstOrNull { !it.granted } ?: uiItems.firstOrNull()
        if (firstMissing != null) {
            explainTitle.text = firstMissing.title
            explainBody.text = firstMissing.description
        }

        adapter.submit(uiItems)
    }
}
