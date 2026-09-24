package com.BHG.webapp

import android.content.Context
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.color.MaterialColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Builds the expandable "Info" panel shown inside a build card — the full list
 * of build details plus true/false rows for every feature and permission.
 *
 * Shared by [BuildAdapter] (My Apps) and [TrashAdapter] (Trash) so both render
 * identical detail panels. Colours are read from the active theme so the panel
 * looks right in both light and dark mode.
 */
object BuildInfoPanel {

    val DATE_FMT = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault())

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

    private data class Perm(val key: String, val labelRes: Int, val keywords: List<String>)

    private val PERMISSIONS = listOf(
        Perm("camera", R.string.perm_camera, listOf("CAMERA")),
        Perm("microphone", R.string.perm_microphone, listOf("MICROPHONE", "MODIFY_AUDIO_SETTINGS")),
        Perm("location", R.string.perm_location, listOf("LOCATION")),
        Perm("vibrate", R.string.perm_vibrate, listOf("VIBRATE")),
        Perm("notifications", R.string.perm_notifications, listOf("POST_NOTIFICATIONS"))
    )

    /** Fills [rows] (an empty vertical LinearLayout) with [item]'s details. */
    fun populate(rows: LinearLayout, item: BuildItem) {
        val ctx = rows.context
        rows.removeAllViews()

        val labelColor = MaterialColors.getColor(rows, com.google.android.material.R.attr.colorOnSurfaceVariant)
        val valueColor = MaterialColors.getColor(rows, com.google.android.material.R.attr.colorOnSurface)
        val headerColor = MaterialColors.getColor(rows, androidx.appcompat.R.attr.colorPrimary)
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

    fun formatSize(bytes: Long): String {
        if (bytes <= 0) return ""
        val mb = bytes / (1024.0 * 1024.0)
        return if (mb >= 1.0) String.format(Locale.getDefault(), "%.1f MB", mb)
        else String.format(Locale.getDefault(), "%.0f KB", bytes / 1024.0)
    }
}
