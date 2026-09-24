package com.BHG.webapp

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.BHG.webapp.databinding.ItemTrashBinding
import java.util.Date

/**
 * Renders the "Trash" list. Same card look as My Apps but with a reduced action
 * row — Info · Restore · Delete forever. Restore moves the app back to My Apps;
 * Delete forever wipes its Firestore doc and all Cloudflare files.
 */
class TrashAdapter(
    private val onRestore: (BuildItem) -> Unit,
    private val onDeleteForever: (BuildItem) -> Unit
) : ListAdapter<BuildItem, TrashAdapter.VH>(DIFF) {

    /** buildIds whose Info panel is currently expanded. */
    private val expanded = mutableSetOf<String>()

    inner class VH(val binding: ItemTrashBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemTrashBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = getItem(position)
        val b = holder.binding
        val ctx = b.root.context

        b.itemAppName.text = item.appName.ifEmpty { ctx.getString(R.string.app_display_name) }
        b.itemUrl.text = item.url
        b.itemDate.text = if (item.createdAtMs > 0) BuildInfoPanel.DATE_FMT.format(Date(item.createdAtMs)) else ""

        val (labelRes, colorAttr) = when (item.status) {
            "READY" -> R.string.status_ready to R.color.success
            "FAILED", "REJECTED" -> R.string.status_failed to R.color.error
            else -> R.string.status_building to R.color.text_secondary
        }
        b.itemStatus.text = ctx.getString(labelRes)
        b.itemStatus.setTextColor(ContextCompat.getColor(ctx, colorAttr))

        val showSize = item.status == "READY" && item.sizeBytes > 0
        b.itemSize.visibility = if (showSize) View.VISIBLE else View.GONE
        if (showSize) b.itemSize.text = BuildInfoPanel.formatSize(item.sizeBytes)

        b.itemRestore.setOnClickListener { onRestore(item) }
        b.itemDeleteForever.setOnClickListener { onDeleteForever(item) }

        // Info toggle + panel.
        val isOpen = expanded.contains(item.buildId)
        b.itemInfoPanel.visibility = if (isOpen) View.VISIBLE else View.GONE
        if (isOpen) BuildInfoPanel.populate(b.itemInfoRows, item)
        b.itemInfo.setOnClickListener {
            if (!expanded.remove(item.buildId)) expanded.add(item.buildId)
            notifyItemChanged(holder.bindingAdapterPosition)
        }
    }

    private companion object {
        private val DIFF = object : DiffUtil.ItemCallback<BuildItem>() {
            override fun areItemsTheSame(a: BuildItem, b: BuildItem) = a.buildId == b.buildId
            override fun areContentsTheSame(a: BuildItem, b: BuildItem) = a == b
        }
    }
}
