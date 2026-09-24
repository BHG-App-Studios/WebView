package com.BHG.webapp

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.BHG.webapp.databinding.ItemBuildBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One build row in the History list. */
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
    val keystoreDownloadUrl: String = ""
)

/**
 * Renders build history. Status maps to a colored pill; the download button is
 * only active for READY builds. Uses ListAdapter/DiffUtil so live Firestore
 * updates animate in place.
 */
class BuildAdapter(
    private val onDownload: (BuildItem) -> Unit,
    private val onDownloadAab: (BuildItem) -> Unit = {},
    private val onDownloadKeystore: (BuildItem) -> Unit = {}
) : ListAdapter<BuildItem, BuildAdapter.VH>(DIFF) {

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
        b.itemStatus.setTextColor(androidx.core.content.ContextCompat.getColor(ctx, colorAttr))

        val ready = item.status == "READY"
        // Primary APK button is active only when an APK exists. AAB-only release
        // builds have no APK, so dim it when there's no APK download URL.
        val hasApk = item.downloadUrl.isNotEmpty()
        b.itemDownload.isEnabled = ready && hasApk
        b.itemDownload.alpha = if (ready && hasApk) 1f else 0.35f
        b.itemDownload.setOnClickListener { if (ready && hasApk) onDownload(item) }

        // AAB download — shown only when the build produced one.
        val showAab = ready && item.aabDownloadUrl.isNotEmpty()
        b.itemDownloadAab.visibility = if (showAab) android.view.View.VISIBLE else android.view.View.GONE
        b.itemDownloadAab.setOnClickListener { if (showAab) onDownloadAab(item) }

        // Keystore download — shown for any build whose signing key was stored
        // (debug or release, auto-generated or user-uploaded).
        val showKeystore = ready && item.keystoreAvailable && item.keystoreDownloadUrl.isNotEmpty()
        b.itemDownloadKeystore.visibility = if (showKeystore) android.view.View.VISIBLE else android.view.View.GONE
        b.itemDownloadKeystore.setOnClickListener { if (showKeystore) onDownloadKeystore(item) }
    }

    private companion object {
        private val DATE_FMT = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault())

        private val DIFF = object : DiffUtil.ItemCallback<BuildItem>() {
            override fun areItemsTheSame(a: BuildItem, b: BuildItem) = a.buildId == b.buildId
            override fun areContentsTheSame(a: BuildItem, b: BuildItem) = a == b
        }
    }
}
