package com.aria.assistant.setup

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.aria.assistant.R

class PermissionStatusAdapter : RecyclerView.Adapter<PermissionStatusAdapter.PermissionViewHolder>() {

    private val items = mutableListOf<PermissionUiItem>()

    fun submit(itemsList: List<PermissionUiItem>) {
        items.clear()
        items.addAll(itemsList)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PermissionViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_permission_status, parent, false)
        return PermissionViewHolder(view)
    }

    override fun onBindViewHolder(holder: PermissionViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    class PermissionViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val icon: TextView = itemView.findViewById(R.id.permissionStatusIcon)
        private val title: TextView = itemView.findViewById(R.id.permissionTitle)

        fun bind(item: PermissionUiItem) {
            icon.text = if (item.granted) "✅" else "❌"
            title.text = item.title
        }
    }
}
