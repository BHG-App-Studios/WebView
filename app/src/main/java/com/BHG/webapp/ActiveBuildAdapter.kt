package com.BHG.webapp

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.BHG.webapp.databinding.ItemActiveBuildBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Renders the live "Active Builds" list. Each row reflects the current status of
 * a build that is BUILDING (or that finished while the user was watching this
 * session): a spinner while building, a download button when READY, a retry
 * prompt when FAILED. Reuses [BuildItem] from [BuildAdapter].
 */
class ActiveBuildAdapter(
    private val onDownload: (BuildItem) -> Unit,
    private val onRetry: () -> Unit
) : ListAdapter<BuildItem, ActiveBuildAdapter.VH>(DIFF) {

    inner class VH(val binding: ItemActiveBuildBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemActiveBuildBinding.inflate(
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
        b.itemTime.text = if (item.createdAtMs > 0) friendlyTime(Date(item.createdAtMs)) else "Just now"

        when (item.status) {
            "READY" -> {
                b.itemSpinner.visibility = View.GONE
                b.itemStatusPill.visibility = View.VISIBLE
                b.itemStatusPill.text = ctx.getString(R.string.status_ready)
                b.itemStatusPill.setTextColor(ContextCompat.getColor(ctx, R.color.success))
                b.itemStatusHint.text = ctx.getString(R.string.active_ready_hint)
                b.itemDownload.visibility = View.VISIBLE
                b.itemDownload.setOnClickListener { onDownload(item) }
                b.itemRetry.visibility = View.GONE
            }
            "FAILED", "REJECTED" -> {
                b.itemSpinner.visibility = View.GONE
                b.itemStatusPill.visibility = View.VISIBLE
                b.itemStatusPill.text = ctx.getString(R.string.status_failed)
                b.itemStatusPill.setTextColor(ContextCompat.getColor(ctx, R.color.error))
                b.itemStatusHint.text = ctx.getString(R.string.active_failed_hint)
                b.itemDownload.visibility = View.GONE
                b.itemRetry.visibility = View.VISIBLE
                b.itemRetry.setOnClickListener { onRetry() }
            }
            else -> { // BUILDING (or unknown -> treat as building)
                b.itemSpinner.visibility = View.VISIBLE
                b.itemStatusPill.visibility = View.GONE
                b.itemStatusHint.text = ctx.getString(R.string.active_building_hint)
                b.itemDownload.visibility = View.GONE
                b.itemRetry.visibility = View.GONE
            }
        }
    }

    private fun friendlyTime(date: Date): String {
        val diffMin = (System.currentTimeMillis() - date.time) / 60_000
        return when {
            diffMin < 1 -> "Just now"
            diffMin < 60 -> "${diffMin}m ago"
            diffMin < 1440 -> "${diffMin / 60}h ago"
            else -> DATE_FMT.format(date)
        }
    }

    private companion object {
        private val DATE_FMT = SimpleDateFormat("MMM d", Locale.getDefault())

        private val DIFF = object : DiffUtil.ItemCallback<BuildItem>() {
            override fun areItemsTheSame(a: BuildItem, b: BuildItem) = a.buildId == b.buildId
            override fun areContentsTheSame(a: BuildItem, b: BuildItem) = a == b
        }
    }
}
