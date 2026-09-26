package com.BHG.webapp

import android.app.DownloadManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.BHG.webapp.databinding.FragmentBuildBinding
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query

/**
 * Active Builds tab — a LIVE view of builds that are currently BUILDING, not a
 * history list.
 *
 * On open it attaches a Firestore snapshot listener to users/{uid}/builds. A
 * build is shown here only while it is actively BUILDING, plus the brief moment
 * it flips to READY/FAILED *while the user is watching this session* — so the
 * download button appears right after a build completes. Once finished, it lives
 * in the My Apps history ([HistoryFragment]); it does NOT reappear here on a
 * later visit.
 *
 * The "session set" is the mechanism: only builds seen as BUILDING during this
 * fragment's lifetime are eligible to render. A build that was already finished
 * before this screen opened is never added, so it stays out of the active list.
 */
class BuildFragment : Fragment() {

    private var _binding: FragmentBuildBinding? = null
    private val binding get() = _binding!!

    private val auth: FirebaseAuth? by lazy { runCatching { FirebaseAuth.getInstance() }.getOrNull() }
    private val firestore: FirebaseFirestore? by lazy { runCatching { FirebaseFirestore.getInstance() }.getOrNull() }

    private var buildListener: ListenerRegistration? = null
    private lateinit var adapter: ActiveBuildAdapter

    /** Build IDs seen as BUILDING this session — the only ones allowed to render. */
    private val sessionActiveIds = HashSet<String>()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentBuildBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        adapter = ActiveBuildAdapter(
            onDownload = ::startDownload,
            onRetry = { (activity as? MainActivity)?.goToTab(R.id.nav_landing) }
        )
        binding.topBarMenu.setOnClickListener { (activity as? MainActivity)?.openDrawer() }
        binding.emptyRecentBtn.setOnClickListener { (activity as? MainActivity)?.goToTab(R.id.nav_history) }
        binding.activeList.layoutManager = LinearLayoutManager(requireContext())
        binding.activeList.adapter = adapter
        observeActiveBuilds()
    }

    // =========================================================================
    //  Firestore listener — active builds only
    // =========================================================================

    private fun observeActiveBuilds() {
        val user = auth?.currentUser
        val db = firestore
        if (user == null || db == null) {
            showEmpty()
            return
        }

        binding.activeProgress.visibility = View.VISIBLE
        // Watch recent builds so we catch the BUILDING -> READY/FAILED transition
        // live. We keep the limit small; the active set is what actually filters.
        buildListener = db.collection("users").document(user.uid)
            .collection("builds")
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .limit(20)
            .addSnapshotListener { snapshot, error ->
                if (_binding == null) return@addSnapshotListener
                binding.activeProgress.visibility = View.GONE
                if (error != null) {
                    Log.w(TAG, "Active build listener error: ${error.message}")
                    render()
                    return@addSnapshotListener
                }

                val docs = snapshot?.documents.orEmpty()
                val items = docs.map { doc ->
                    BuildItem(
                        buildId = doc.getString("buildId") ?: doc.id,
                        appName = doc.getString("appName") ?: "",
                        url = doc.getString("url") ?: "",
                        status = doc.getString("status") ?: "BUILDING",
                        downloadUrl = doc.getString("downloadUrl") ?: "",
                        createdAtMs = doc.getTimestamp("createdAt")?.toDate()?.time ?: 0L,
                        previewImage = doc.getString("previewImage") ?: ""
                    )
                }

                // Any build currently BUILDING becomes part of this session's
                // active set. On the very first snapshot this admits a build that
                // was already in progress when the screen opened (the intended
                // "connect to a live build on open" behaviour); on later snapshots
                // it admits newly started builds.
                for (item in items) {
                    if (item.status == "BUILDING") sessionActiveIds.add(item.buildId)
                }

                lastItems = items
                render()
            }
    }

    private var lastItems: List<BuildItem> = emptyList()

    private fun render() {
        if (_binding == null) return
        // Only builds admitted to the session set render, so finished builds from
        // previous visits never resurface here — they belong to My Apps.
        val active = lastItems
            .filter { it.buildId in sessionActiveIds }
            .sortedByDescending { it.createdAtMs }

        if (active.isEmpty()) {
            showEmpty()
        } else {
            showList(active)
        }
    }

    // =========================================================================
    //  UI state helpers
    // =========================================================================

    private fun showEmpty() {
        if (_binding == null) return
        adapter.submitList(emptyList())
        binding.emptyState.visibility = View.VISIBLE
        binding.activeList.visibility = View.GONE
        binding.statusBadge.visibility = View.GONE
    }

    private fun showList(items: List<BuildItem>) {
        if (_binding == null) return
        binding.emptyState.visibility = View.GONE
        binding.activeList.visibility = View.VISIBLE
        adapter.submitList(items)

        val building = items.count { it.status == "BUILDING" }
        binding.statusBadge.visibility = View.VISIBLE
        binding.statusBadge.text = if (building > 0) {
            if (building == 1) "1 building" else "$building building"
        } else {
            "✓ Ready"
        }
    }

    // =========================================================================
    //  Download
    // =========================================================================

    private fun startDownload(item: BuildItem) {
        if (item.downloadUrl.isEmpty()) return
        val uri = Uri.parse(item.downloadUrl)
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
        try { startActivity(Intent(Intent.ACTION_VIEW, uri)) }
        catch (e: Exception) { if (isAdded) Toast.makeText(requireContext(), R.string.error_generic, Toast.LENGTH_SHORT).show() }
    }

    override fun onDestroyView() {
        buildListener?.remove(); buildListener = null
        _binding = null
        super.onDestroyView()
    }

    private companion object {
        private const val TAG = "BuildFragment"
    }
}
