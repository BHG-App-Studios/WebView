package com.BHG.webapp

import android.content.Context
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.BHG.webapp.databinding.ItemBuildBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
 * actions (Info · Keystore · Update · Download); Info expands an in-card panel
 * with the full build details. Uses ListAdapter/DiffUtil so live Firestore
 * updates animate in place.
 */
class BuildAdapter(
    private val onDownload: (BuildItem) -> Unit,
    private val onDownloadAab: (BuildItem) -> Unit = {},
    private val onDownloadKeystore: (BuildItem) -> Unit = {},
    private val onUpdate: (BuildItem) -> Unit = {}
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
        b.itemDate.text = if (item.createdAtMs > 0) DATE_FMT.format(Date(item.createdAtMs)) else ""

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
        if (showSize) b.itemSize.text = formatSize(item.sizeBytes)

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

        // Info toggle + panel.
        val isOpen = expanded.contains(item.buildId)
        b.itemInfoPanel.visibility = if (isOpen) View.VISIBLE else View.GONE
        if (isOpen) populateInfo(b, item)
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

    private fun populateInfo(b: ItemBuildBinding, item: BuildItem) {
        val ctx = b.root.context
        val rows = b.itemInfoRows
        rows.removeAllViews()

        val labelColor = MaterialColors.getColor(b.root, com.google.android.material.R.attr.colorOnSurfaceVariant)
        val valueColor = MaterialColors.getColor(b.root, com.google.android.material.R.attr.colorOnSurface)
        val headerColor = MaterialColors.getColor(b.root, androidx.appcompat.R.attr.colorPrimary)
        val onColor = ContextCompat.getColor(ctx, R.color.success)
        val offColor = ContextCompat.getColor(ctx, R.color.error)

        addRow(rows, ctx.getString(R.string.info_url), item.url, labelColor, valueColor)
        if (item.packageName.isNotEmpty() && item.packageName != "auto")
            addRow(rows, ctx.getString(R.string.info_package), item.packageName, labelColor, valueColor)
        val version = buildString {
            append(item.versionName.ifEmpty { "1.0" })
            if (item.versionCode > 0) append(" (").append(item.versionCode).append(")")
        }
        addRow(rows, ctx.getString(R.string.info_version), version, labelColor, valueColor)
        if (item.buildType.isNotEmpty())
            addRow(rows, ctx.getString(R.string.info_build_type), item.buildType.replaceFirstChar { it.uppercase() }, labelColor, valueColor)
        if (item.signingMode.isNotEmpty())
            addRow(rows, ctx.getString(R.string.info_signing), item.signingMode.replaceFirstChar { it.uppercase() }, labelColor, valueColor)
        if (item.outputs.isNotEmpty())
            addRow(rows, ctx.getString(R.string.info_output), item.outputs.joinToString(", ") { it.uppercase() }, labelColor, valueColor)
        if (item.sizeBytes > 0)
            addRow(rows, ctx.getString(R.string.info_size), formatSize(item.sizeBytes), labelColor, valueColor)
        if (item.createdAtMs > 0)
            addRow(rows, ctx.getString(R.string.info_created), DATE_FMT.format(Date(item.createdAtMs)), labelColor, valueColor)

        // Features — true/false per option the build was created with.
        addHeader(rows, ctx.getString(R.string.info_features), headerColor)
        for ((key, res) in FEATURE_LABELS)
            addBoolRow(rows, ctx.getString(res), item.options[key] ?: false, labelColor, onColor, offColor)

        // Permissions — included unless the build stripped that permission.
        addHeader(rows, ctx.getString(R.string.info_permissions), headerColor)
        for (perm in PERMISSIONS) {
            val included = perm.keywords.none { it in item.removePermissions }
            addBoolRow(rows, ctx.getString(perm.labelRes), included, labelColor, onColor, offColor)
        }
    }

    private fun addRow(parent: LinearLayout, label: String, value: String, labelColor: Int, valueColor: Int) {
        val ctx = parent.context
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(ctx, 3), 0, dp(ctx, 3))
        }
        row.addView(TextView(ctx).apply {
            text = label; setTextColor(labelColor); textSize = 13f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        row.addView(TextView(ctx).apply {
            text = value; setTextColor(valueColor); textSize = 13f; gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.5f)
        })
        parent.addView(row)
    }

    private fun addHeader(parent: LinearLayout, text: String, color: Int) {
        val ctx = parent.context
        parent.addView(TextView(ctx).apply {
            this.text = text.uppercase(); setTextColor(color); textSize = 11f
            letterSpacing = 0.06f
            setPadding(0, dp(ctx, 12), 0, dp(ctx, 4))
        })
    }

    private fun addBoolRow(parent: LinearLayout, label: String, on: Boolean, labelColor: Int, onColor: Int, offColor: Int) {
        val ctx = parent.context
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(ctx, 2), 0, dp(ctx, 2))
        }
        row.addView(TextView(ctx).apply {
            text = label; setTextColor(labelColor); textSize = 13f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        row.addView(TextView(ctx).apply {
            text = if (on) "✓" else "✗"
            setTextColor(if (on) onColor else offColor)
            textSize = 15f; gravity = Gravity.END
        })
        parent.addView(row)
    }

    private fun dp(ctx: Context, v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    private fun formatSize(bytes: Long): String {
        if (bytes <= 0) return ""
        val mb = bytes / (1024.0 * 1024.0)
        return if (mb >= 1.0) String.format(Locale.getDefault(), "%.1f MB", mb)
        else String.format(Locale.getDefault(), "%.0f KB", bytes / 1024.0)
    }

    private data class Perm(val key: String, val labelRes: Int, val keywords: List<String>)

    private companion object {
        private val DATE_FMT = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault())

        // Kept in sync with HomeFragment.featureOptions / permissionOptions so the
        // Info panel labels match what the builder offered.
        private val FEATURE_LABELS = listOf(
            "progress_bar" to R.string.opt_progress_bar,
            "circular_progress" to R.string.opt_circular_progress,
            "swipe_refresh" to R.string.opt_swipe_refresh,
            "file_upload" to R.string.opt_file_upload,
            "multiple_files" to R.string.opt_multiple_files,
            "downloads" to R.string.opt_downloads,
            "external_links" to R.string.opt_external_links,
            "offline_page" to R.string.opt_offline_page,
            "fullscreen_video" to R.string.opt_fullscreen_video
        )

        private val PERMISSIONS = listOf(
            Perm("camera", R.string.perm_camera, listOf("CAMERA")),
            Perm("microphone", R.string.perm_microphone, listOf("MICROPHONE", "MODIFY_AUDIO_SETTINGS")),
            Perm("location", R.string.perm_location, listOf("LOCATION")),
            Perm("vibrate", R.string.perm_vibrate, listOf("VIBRATE")),
            Perm("notifications", R.string.perm_notifications, listOf("POST_NOTIFICATIONS"))
        )

        private val DIFF = object : DiffUtil.ItemCallback<BuildItem>() {
            override fun areItemsTheSame(a: BuildItem, b: BuildItem) = a.buildId == b.buildId
            override fun areContentsTheSame(a: BuildItem, b: BuildItem) = a == b
        }
    }
}
