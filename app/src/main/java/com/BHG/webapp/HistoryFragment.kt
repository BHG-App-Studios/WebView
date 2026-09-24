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
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
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
            onDownloadKeystore = ::downloadKeystore,
            onUpdate = ::update,
            onDelete = ::confirmMoveToTrash
        )
        binding.topBarMenu.setOnClickListener { (activity as? MainActivity)?.openDrawer() }
        binding.topBarTrash.setOnClickListener { (activity as? MainActivity)?.showTrash() }
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
                val items = snapshot?.documents?.mapNotNull { doc ->
                    val status = doc.getString("status") ?: "BUILDING"
                    // While building, the app doesn't belong in "My Apps" yet.
                    if (status == "BUILDING") return@mapNotNull null
                    BuildItem(
                        buildId = doc.getString("buildId") ?: doc.id,
                        appName = doc.getString("appName") ?: "",
                        url = doc.getString("url") ?: "",
                        status = status,
                        downloadUrl = doc.getString("downloadUrl") ?: "",
                        createdAtMs = doc.getTimestamp("createdAt")?.toDate()?.time ?: 0L,
                        aabDownloadUrl = doc.getString("aabDownloadUrl") ?: "",
                        keystoreAvailable = doc.getBoolean("keystoreAvailable") ?: false,
                        keystoreDownloadUrl = doc.getString("keystoreDownloadUrl") ?: "",
                        packageName = doc.getString("packageName") ?: "",
                        versionName = doc.getString("versionName") ?: "",
                        versionCode = doc.getLong("versionCode") ?: 0L,
                        buildType = doc.getString("buildType") ?: "",
                        signingMode = doc.getString("signingMode") ?: "",
                        sizeBytes = doc.getLong("sizeBytes") ?: 0L,
                        outputs = (doc.get("outputs") as? List<*>)?.mapNotNull { it as? String } ?: emptyList(),
                        options = (doc.get("options") as? Map<*, *>)?.entries
                            ?.mapNotNull { (k, v) -> (k as? String)?.let { it to (v as? Boolean ?: false) } }
                            ?.toMap() ?: emptyMap(),
                        removePermissions = (doc.get("removePermissions") as? List<*>)?.mapNotNull { it as? String } ?: emptyList()
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

    /**
     * Open the build wizard pre-filled for an update of this app: same URL and
     * package, version code bumped by one so the next Play upload is accepted.
     */
    private fun update(item: BuildItem) {
        (activity as? MainActivity)?.startAppUpdate(
            BuildPrefill(
                url = item.url,
                packageName = item.packageName,
                versionName = item.versionName.ifEmpty { "1.0" },
                versionCode = (if (item.versionCode > 0) item.versionCode else 1L) + 1
            )
        )
    }

    /**
     * Confirm, then move the app to Trash. This is an app-side move only: the
     * Firestore doc is copied from users/{uid}/builds to users/{uid}/trashApps
     * and removed from builds. No files on the server are touched — the app can
     * still be restored, and only a permanent delete from Trash wipes its data.
     */
    private fun confirmMoveToTrash(item: BuildItem) {
        val name = item.appName.ifEmpty { getString(R.string.app_display_name) }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.delete_to_trash_title)
            .setMessage(getString(R.string.delete_to_trash_message, name))
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_delete) { _, _ -> moveToTrash(item) }
            .show()
    }

    private fun moveToTrash(item: BuildItem) {
        val user = auth?.currentUser ?: return
        val db = firestore ?: return
        val builds = db.collection("users").document(user.uid).collection("builds")
        val trash = db.collection("users").document(user.uid).collection("trashApps")

        builds.document(item.buildId).get()
            .addOnSuccessListener { snap ->
                if (!isAdded) return@addOnSuccessListener
                val data = HashMap(snap.data ?: emptyMap())
                data["trashedAt"] = FieldValue.serverTimestamp()
                trash.document(item.buildId).set(data)
                    .addOnSuccessListener {
                        builds.document(item.buildId).delete()
                            .addOnSuccessListener {
                                if (isAdded) Toast.makeText(requireContext(), R.string.moved_to_trash, Toast.LENGTH_SHORT).show()
                            }
                            .addOnFailureListener { failTrashOp(it) }
                    }
                    .addOnFailureListener { failTrashOp(it) }
            }
            .addOnFailureListener { failTrashOp(it) }
    }

    private fun failTrashOp(e: Exception) {
        Log.w(TAG, "Move to trash failed: ${e.message}", e)
        if (isAdded) Toast.makeText(requireContext(), "Trash failed: ${e.message}", Toast.LENGTH_LONG).show()
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
