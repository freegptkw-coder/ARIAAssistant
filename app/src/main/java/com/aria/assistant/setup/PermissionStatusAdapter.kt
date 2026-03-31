package com.aria.assistant.setup

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.aria.assistant.R

class PermissionStatusAdapter(
    private val onItemClick: (PermissionUiItem) -> Unit
) : RecyclerView.Adapter<PermissionStatusAdapter.PermissionViewHolder>() {

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
        holder.bind(items[position], onItemClick)
    }

    override fun getItemCount(): Int = items.size

    class PermissionViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val icon: TextView = itemView.findViewById(R.id.permissionStatusIcon)
        private val title: TextView = itemView.findViewById(R.id.permissionTitle)
        private val subtitle: TextView = itemView.findViewById(R.id.permissionSubtitle)
        private val chip: TextView = itemView.findViewById(R.id.permissionChip)

        fun bind(item: PermissionUiItem, onItemClick: (PermissionUiItem) -> Unit) {
            icon.text = if (item.granted) "✅" else "❌"
            title.text = item.title
            subtitle.text = item.description

            val blockerMissing = item.blocker && !item.granted
            chip.text = if (item.blocker) "BLOCKER" else "OPTIONAL"
            chip.setBackgroundColor(if (item.blocker) Color.parseColor("#5D1F2A") else Color.parseColor("#1F3A5D"))
            chip.setTextColor(Color.WHITE)

            title.setTextColor(
                if (blockerMissing) Color.parseColor("#FF6B6B") else Color.WHITE
            )

            itemView.setOnClickListener { onItemClick(item) }
        }
    }
}
