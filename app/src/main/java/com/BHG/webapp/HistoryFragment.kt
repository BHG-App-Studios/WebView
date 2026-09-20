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
import com.BHG.webapp.databinding.FragmentHistoryBinding
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query

/**
 * Live list of the signed-in user's builds, newest first, read from
 * users/{uid}/builds and kept current with a snapshot listener — so a build
 * started on Home appears here and flips to Ready without a manual refresh.
 */
class HistoryFragment : Fragment() {

    private var _binding: FragmentHistoryBinding? = null
    private val binding get() = _binding!!

    private val auth: FirebaseAuth? by lazy { runCatching { FirebaseAuth.getInstance() }.getOrNull() }
    private val firestore: FirebaseFirestore? by lazy { runCatching { FirebaseFirestore.getInstance() }.getOrNull() }

    private lateinit var adapter: BuildAdapter
    private var listener: ListenerRegistration? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHistoryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        adapter = BuildAdapter(onDownload = ::download)
        binding.historyList.layoutManager = LinearLayoutManager(requireContext())
        binding.historyList.adapter = adapter
        loadBuilds()
    }

    private fun loadBuilds() {
        val user = auth?.currentUser
        val db = firestore
        if (user == null || db == null) {
            showEmpty(true)
            return
        }

        binding.historyProgress.visibility = View.VISIBLE
        listener = db.collection("users").document(user.uid)
            .collection("builds")
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .addSnapshotListener { snapshot, error ->
                if (_binding == null) return@addSnapshotListener
                binding.historyProgress.visibility = View.GONE
                if (error != null) {
                    Log.w(TAG, "History listener error: ${error.message}")
                    return@addSnapshotListener
                }
                val items = snapshot?.documents?.map { doc ->
                    BuildItem(
                        buildId = doc.getString("buildId") ?: doc.id,
                        appName = doc.getString("appName") ?: "",
                        url = doc.getString("url") ?: "",
                        status = doc.getString("status") ?: "BUILDING",
                        downloadUrl = doc.getString("downloadUrl") ?: "",
                        createdAtMs = doc.getTimestamp("createdAt")?.toDate()?.time ?: 0L
                    )
                }.orEmpty()

                adapter.submitList(items)
                showEmpty(items.isEmpty())
            }
    }

    private fun showEmpty(empty: Boolean) {
        if (_binding == null) return
        binding.emptyState.visibility = if (empty) View.VISIBLE else View.GONE
        binding.historyList.visibility = if (empty) View.GONE else View.VISIBLE
    }

    private fun download(item: BuildItem) {
        if (item.downloadUrl.isEmpty()) return
        val uri = Uri.parse(item.downloadUrl)
        val manager = requireContext().getSystemService(DownloadManager::class.java)
        if (manager == null) {
            openInBrowser(uri)
            return
        }
        val fileName = uri.lastPathSegment ?: "app.apk"
        val request = DownloadManager.Request(uri)
            .setTitle(fileName)
            .setMimeType("application/vnd.android.package-archive")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
        try {
            manager.enqueue(request)
            if (isAdded) Toast.makeText(requireContext(), R.string.download_started, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.w(TAG, "Download failed: ${e.message}")
            openInBrowser(uri)
        }
    }

    private fun openInBrowser(uri: Uri) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (e: Exception) {
            if (isAdded) Toast.makeText(requireContext(), R.string.error_generic, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroyView() {
        listener?.remove()
        listener = null
        _binding = null
        super.onDestroyView()
    }

    private companion object {
        private const val TAG = "HistoryFragment"
    }
}
