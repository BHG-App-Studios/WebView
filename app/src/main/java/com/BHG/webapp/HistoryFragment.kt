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
        adapter = BuildAdapter(
            onDownload = ::download,
            onDownloadAab = ::downloadAab,
            onDownloadKeystore = ::downloadKeystore
        )
        binding.topBarMenu.setOnClickListener { (activity as? MainActivity)?.openDrawer() }
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
                        createdAtMs = doc.getTimestamp("createdAt")?.toDate()?.time ?: 0L,
                        aabDownloadUrl = doc.getString("aabDownloadUrl") ?: "",
                        keystoreAvailable = doc.getBoolean("keystoreAvailable") ?: false,
                        keystoreDownloadUrl = doc.getString("keystoreDownloadUrl") ?: ""
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

    private fun download(item: BuildItem) = enqueuePublic(
        item.downloadUrl, "application/vnd.android.package-archive", null
    )

    /** AAB is a public download like the APK. */
    private fun downloadAab(item: BuildItem) = enqueuePublic(
        item.aabDownloadUrl, "application/octet-stream", null
    )

    /**
     * Public download via the system DownloadManager. [authHeader], when given,
     * is attached as an Authorization header (used for the owner-only keystore).
     */
    private fun enqueuePublic(urlStr: String, mime: String, authHeader: String?) {
        if (urlStr.isEmpty()) return
        val uri = Uri.parse(urlStr)
        val manager = requireContext().getSystemService(DownloadManager::class.java)
        if (manager == null) { openInBrowser(uri); return }
        val fileName = uri.lastPathSegment ?: "download"
        val request = DownloadManager.Request(uri)
            .setTitle(fileName)
            .setMimeType(mime)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
        if (authHeader != null) request.addRequestHeader("Authorization", authHeader)
        try {
            manager.enqueue(request)
            if (isAdded) Toast.makeText(requireContext(), R.string.download_started, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.w(TAG, "Download failed: ${e.message}")
            if (authHeader == null) openInBrowser(uri)
            else if (isAdded) Toast.makeText(requireContext(), R.string.error_generic, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Keystore download is owner-only: the Worker requires the caller's Firebase
     * ID token. We fetch a fresh token, then hand the request (with the Bearer
     * header) to the DownloadManager.
     */
    private fun downloadKeystore(item: BuildItem) {
        if (item.keystoreDownloadUrl.isEmpty()) return
        val user = auth?.currentUser
        if (user == null) {
            if (isAdded) Toast.makeText(requireContext(), R.string.error_generic, Toast.LENGTH_SHORT).show()
            return
        }
        user.getIdToken(false)
            .addOnSuccessListener { result ->
                if (!isAdded) return@addOnSuccessListener
                val token = result.token
                if (token.isNullOrEmpty()) {
                    Toast.makeText(requireContext(), R.string.error_generic, Toast.LENGTH_SHORT).show()
                    return@addOnSuccessListener
                }
                enqueuePublic(item.keystoreDownloadUrl, "application/zip", "Bearer $token")
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "Keystore token fetch failed: ${e.message}")
                if (isAdded) Toast.makeText(requireContext(), R.string.error_generic, Toast.LENGTH_SHORT).show()
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
