package com.BHG.webapp

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.BHG.webapp.databinding.ItemBuildBinding
import com.google.android.material.button.MaterialButton
import java.util.Date

/** One build row in the History ("My Apps") list. */
data class BuildItem(
    val buildId: String,
    val appName: String,
    val url: String,
    val status: String,
    val downloadUrl: String,
    val createdAtMs: Long,
    // Release/signing extras. Empty/false when not applicable.
    val aabDownloadUrl: String = "",
    val keystoreAvailable: Boolean = false,
    val keystoreDownloadUrl: String = "",
    // Details shown in the expandable Info panel.
    val packageName: String = "",
    val versionName: String = "",
    val versionCode: Long = 0L,
    val buildType: String = "",
    val signingMode: String = "",
    val sizeBytes: Long = 0L,
    val outputs: List<String> = emptyList(),
    val options: Map<String, Boolean> = emptyMap(),
    val removePermissions: List<String> = emptyList()
)

/**
 * Renders the "My Apps" list. Each card shows a status pill + size and a row of
 * actions (Info · Keystore · Update · Download · Delete); Info expands an
 * in-card panel with the full build details. Uses ListAdapter/DiffUtil so live
 * Firestore updates animate in place. Delete moves the app to Trash.
 */
class BuildAdapter(
    private val onDownload: (BuildItem) -> Unit,
    private val onDownloadAab: (BuildItem) -> Unit = {},
    private val onDownloadKeystore: (BuildItem) -> Unit = {},
    private val onUpdate: (BuildItem) -> Unit = {},
    private val onDelete: (BuildItem) -> Unit = {}
) : ListAdapter<BuildItem, BuildAdapter.VH>(DIFF) {

    /** buildIds whose Info panel is currently expanded. */
    private val expanded = mutableSetOf<String>()

    inner class VH(val binding: ItemBuildBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemBuildBinding.inflate(
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

        val ready = item.status == "READY"
        val failed = item.status == "FAILED" || item.status == "REJECTED"

        // App size in the header, when known.
        val showSize = ready && item.sizeBytes > 0
        b.itemSize.visibility = if (showSize) View.VISIBLE else View.GONE
        if (showSize) b.itemSize.text = BuildInfoPanel.formatSize(item.sizeBytes)

        // Download — APK when present; AAB-only release falls back to the AAB.
        val hasApk = item.downloadUrl.isNotEmpty()
        val hasAab = item.aabDownloadUrl.isNotEmpty()
        val canDownload = ready && (hasApk || hasAab)
        setAction(b.itemDownload, canDownload) { if (hasApk) onDownload(item) else onDownloadAab(item) }

        // Keystore — any ready build whose signing key was stored.
        val canKeystore = ready && item.keystoreAvailable && item.keystoreDownloadUrl.isNotEmpty()
        setAction(b.itemDownloadKeystore, canKeystore) { onDownloadKeystore(item) }

        // Update — finished builds only (publish an update, or retry a failure).
        setAction(b.itemUpdate, ready || failed) { onUpdate(item) }

        // Delete — always available; moves the app to Trash.
        setAction(b.itemDelete, true) { onDelete(item) }

        // Info toggle + panel.
        val isOpen = expanded.contains(item.buildId)
        b.itemInfoPanel.visibility = if (isOpen) View.VISIBLE else View.GONE
        if (isOpen) BuildInfoPanel.populate(b.itemInfoRows, item)
        b.itemInfo.setOnClickListener {
            if (!expanded.remove(item.buildId)) expanded.add(item.buildId)
            notifyItemChanged(holder.bindingAdapterPosition)
        }

        // AAB button lives in the info panel; shown only when both APK and AAB
        // exist (AAB-only builds already download via the main Download button).
        val showAabExtra = isOpen && ready && hasApk && hasAab
        b.itemDownloadAab.visibility = if (showAabExtra) View.VISIBLE else View.GONE
        b.itemDownloadAab.setOnClickListener { onDownloadAab(item) }
    }

    private fun setAction(btn: MaterialButton, enabled: Boolean, onClick: () -> Unit) {
        btn.isEnabled = enabled
        btn.alpha = if (enabled) 1f else 0.35f
        btn.setOnClickListener { if (enabled) onClick() }
    }

    private companion object {
        private val DIFF = object : DiffUtil.ItemCallback<BuildItem>() {
            override fun areItemsTheSame(a: BuildItem, b: BuildItem) = a.buildId == b.buildId
            override fun areContentsTheSame(a: BuildItem, b: BuildItem) = a == b
        }
    }
}
