package com.BHG.webapp

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.BHG.webapp.databinding.FragmentBuildBinding
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import java.text.SimpleDateFormat
import java.util.*

/**
 * Build tab — shows the most recent BUILDING job for the signed-in user.
 *
 * Opens a real-time Firestore listener ordered by createdAt desc so it
 * automatically picks up a build started from the wizard (HomeFragment)
 * and tracks it all the way to READY or FAILED.
 */
class BuildFragment : Fragment() {

    private var _binding: FragmentBuildBinding? = null
    private val binding get() = _binding!!

    private val auth: FirebaseAuth? by lazy { runCatching { FirebaseAuth.getInstance() }.getOrNull() }
    private val firestore: FirebaseFirestore? by lazy { runCatching { FirebaseFirestore.getInstance() }.getOrNull() }

    private var buildListener: ListenerRegistration? = null
    private var pulseAnimSet: AnimatorSet? = null

    private var currentDownloadUrl: String? = null
    private var currentBuildStatus: String = ""

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentBuildBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.topBarMenu.setOnClickListener { (activity as? MainActivity)?.openDrawer() }
        binding.emptyStartBtn.setOnClickListener { (activity as? MainActivity)?.goToTab(R.id.nav_landing) }
        binding.downloadButton.setOnClickListener { startDownload() }
        binding.newBuildButton.setOnClickListener {
            (activity as? MainActivity)?.goToTab(R.id.nav_landing)
        }
        observeLatestBuild()
    }

    // =========================================================================
    //  Firestore listener — most recent build
    // =========================================================================

    private fun observeLatestBuild() {
        val user = auth?.currentUser
        val db   = firestore
        if (user == null || db == null) {
            showEmpty()
            return
        }

        buildListener = db.collection("users").document(user.uid)
            .collection("builds")
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .limit(1)
            .addSnapshotListener { snapshot, error ->
                if (_binding == null) return@addSnapshotListener
                if (error != null) {
                    Log.w(TAG, "Build listener error: ${error.message}")
                    showEmpty()
                    return@addSnapshotListener
                }

                val doc = snapshot?.documents?.firstOrNull()
                if (doc == null) {
                    showEmpty()
                    return@addSnapshotListener
                }

                val status      = doc.getString("status") ?: "BUILDING"
                val appName     = doc.getString("appName") ?: "My App"
                val url         = doc.getString("url") ?: ""
                val downloadUrl = doc.getString("downloadUrl") ?: ""
                val createdAt   = doc.getTimestamp("createdAt")?.toDate()

                currentDownloadUrl = downloadUrl.ifEmpty { null }

                // Only show card for active builds (BUILDING / READY / FAILED)
                // Skip very old READY/FAILED ones so empty state shows when idle
                val ageMs = if (createdAt != null) System.currentTimeMillis() - createdAt.time else 0L
                val isRecent = ageMs < SHOW_BUILD_MS

                if (status == "BUILDING" || (isRecent && status in listOf("READY","FAILED","REJECTED"))) {
                    showBuildCard(status, appName, url, createdAt)
                } else {
                    showEmpty()
                }
            }
    }

    // =========================================================================
    //  UI state helpers
    // =========================================================================

    private fun showEmpty() {
        if (_binding == null) return
        stopPulseAnimation()
        binding.emptyState.visibility  = View.VISIBLE
        binding.buildContent.visibility = View.GONE
        binding.statusBadge.visibility  = View.GONE
    }

    private fun showBuildCard(status: String, appName: String, url: String, createdAt: Date?) {
        if (_binding == null) return
        binding.emptyState.visibility   = View.GONE
        binding.buildContent.visibility = View.VISIBLE

        // Populate info row
        binding.buildAppName.text = appName.ifEmpty { "My App" }
        binding.buildUrl.text     = url
        binding.buildTime.text    = createdAt?.let { friendlyTime(it) } ?: "Just now"

        when (status) {
            "BUILDING" -> applyBuildingState()
            "READY"    -> applyReadyState()
            else       -> applyFailedState()
        }

        if (status != currentBuildStatus) {
            currentBuildStatus = status
            // Update badge
            binding.statusBadge.visibility = View.VISIBLE
            binding.statusBadge.text = when (status) {
                "READY"  -> "✓ Ready"
                "FAILED", "REJECTED" -> "✗ Failed"
                else     -> "Building"
            }
        }
    }

    private fun applyBuildingState() {
        binding.buildProgressRing.visibility = View.VISIBLE
        binding.buildCenterIcon.visibility   = View.VISIBLE
        binding.buildResultIcon.visibility   = View.GONE
        binding.downloadButton.visibility    = View.GONE
        binding.newBuildButton.visibility    = View.GONE
        binding.stepProgress2.visibility     = View.VISIBLE
        binding.stepIcon3.alpha              = 0.3f
        binding.buildStatusTitle.text        = "Building your app…"
        binding.buildStatusText.text         = "This usually takes a few minutes. Stay on this screen."
        startPulseAnimation()
    }

    private fun applyReadyState() {
        stopPulseAnimation()
        binding.buildProgressRing.visibility = View.GONE
        binding.buildCenterIcon.visibility   = View.GONE
        binding.buildResultIcon.visibility   = View.VISIBLE
        binding.buildResultIcon.setImageResource(R.drawable.ic_download)
        binding.downloadButton.visibility    = View.VISIBLE
        binding.newBuildButton.visibility    = View.VISIBLE
        binding.stepProgress2.visibility     = View.GONE
        binding.stepIcon3.alpha              = 1f
        binding.buildStatusTitle.text        = "🎉 Your app is ready!"
        binding.buildStatusText.text         = "Download the APK and install it on your device."
    }

    private fun applyFailedState() {
        stopPulseAnimation()
        binding.buildProgressRing.visibility = View.GONE
        binding.buildCenterIcon.visibility   = View.VISIBLE
        binding.buildResultIcon.visibility   = View.GONE
        binding.downloadButton.visibility    = View.GONE
        binding.newBuildButton.visibility    = View.VISIBLE
        binding.stepProgress2.visibility     = View.GONE
        binding.buildStatusTitle.text        = "Build failed"
        binding.buildStatusText.text         = "Something went wrong. Tap below to try again."
    }

    // =========================================================================
    //  Pulse animation
    // =========================================================================

    private fun startPulseAnimation() {
        if (pulseAnimSet?.isRunning == true) return
        val b = _binding ?: return

        val outerAlpha = ObjectAnimator.ofFloat(b.buildPulseOuter, "alpha", 0f, 0.4f, 0f).apply {
            duration = 1600; repeatCount = ValueAnimator.INFINITE; repeatMode = ValueAnimator.RESTART
            interpolator = AccelerateDecelerateInterpolator()
        }
        val outerScaleX = ObjectAnimator.ofFloat(b.buildPulseOuter, "scaleX", 0.85f, 1.1f).apply {
            duration = 1600; repeatCount = ValueAnimator.INFINITE; repeatMode = ValueAnimator.RESTART
            interpolator = AccelerateDecelerateInterpolator()
        }
        val outerScaleY = ObjectAnimator.ofFloat(b.buildPulseOuter, "scaleY", 0.85f, 1.1f).apply {
            duration = 1600; repeatCount = ValueAnimator.INFINITE; repeatMode = ValueAnimator.RESTART
            interpolator = AccelerateDecelerateInterpolator()
        }
        val iconRotate = ObjectAnimator.ofFloat(b.buildCenterIcon, "rotation", 0f, 360f).apply {
            duration = 3000; repeatCount = ValueAnimator.INFINITE; repeatMode = ValueAnimator.RESTART
            interpolator = AccelerateDecelerateInterpolator()
        }
        pulseAnimSet = AnimatorSet().also { it.playTogether(outerAlpha, outerScaleX, outerScaleY, iconRotate); it.start() }
    }

    private fun stopPulseAnimation() {
        pulseAnimSet?.cancel(); pulseAnimSet = null
        _binding?.buildCenterIcon?.rotation = 0f
    }

    // =========================================================================
    //  Download
    // =========================================================================

    private fun startDownload() {
        val url = currentDownloadUrl ?: return
        val uri = Uri.parse(url)
        val mgr = requireContext().getSystemService(DownloadManager::class.java)
        if (mgr == null) { openInBrowser(uri); return }
        val fileName = uri.lastPathSegment ?: "app.apk"
        val req = DownloadManager.Request(uri)
            .setTitle(fileName)
            .setMimeType("application/vnd.android.package-archive")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
        try {
            mgr.enqueue(req)
            if (isAdded) Toast.makeText(requireContext(), R.string.download_started, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.w(TAG, "Download failed: ${e.message}"); openInBrowser(uri)
        }
    }

    private fun openInBrowser(uri: Uri) {
        try { startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, uri)) }
        catch (e: Exception) { if (isAdded) Toast.makeText(requireContext(), R.string.error_generic, Toast.LENGTH_SHORT).show() }
    }

    // =========================================================================
    //  Helpers
    // =========================================================================

    private fun friendlyTime(date: Date): String {
        val diffMs  = System.currentTimeMillis() - date.time
        val diffMin = diffMs / 60_000
        return when {
            diffMin < 1  -> "Just now"
            diffMin < 60 -> "${diffMin}m ago"
            diffMin < 1440 -> "${diffMin / 60}h ago"
            else -> SimpleDateFormat("MMM d", Locale.getDefault()).format(date)
        }
    }

    override fun onDestroyView() {
        stopPulseAnimation()
        buildListener?.remove(); buildListener = null
        _binding = null
        super.onDestroyView()
    }

    private companion object {
        private const val TAG          = "BuildFragment"
        // Show the build card for up to 24 hours after creation
        private const val SHOW_BUILD_MS = 24 * 60 * 60 * 1000L
    }
}
