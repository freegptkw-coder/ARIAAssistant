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

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        summaryText = view.findViewById(R.id.permissionsSummaryText)

        val recycler: RecyclerView = view.findViewById(R.id.permissionsRecycler)
        recycler.layoutManager = LinearLayoutManager(requireContext())
        adapter = PermissionStatusAdapter()
        recycler.adapter = adapter

        view.findViewById<MaterialButton>(R.id.grantAllPermissionsButton).setOnClickListener {
            (requireActivity() as? SetupHost)?.requestAllRuntimePermissions()
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
            PermissionUiItem(it.title, SetupChecks.isPermissionGranted(requireContext(), it.permission))
        }

        val granted = uiItems.count { it.granted }
        summaryText.text = "$granted/${uiItems.size} permissions granted"

        adapter.submit(uiItems)
    }
}
