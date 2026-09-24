package com.BHG.webapp

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.BHG.webapp.databinding.FragmentTrashBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query

/**
 * Live list of the signed-in user's trashed apps (users/{uid}/trashApps),
 * newest-trashed first. From here the user can Restore an app (move it back to
 * builds) or Delete it forever — which wipes its Firestore doc AND all of its
 * Cloudflare files (APK, AAB, keystore). There is no auto-purge; both actions
 * are manual.
 */
class TrashFragment : Fragment() {

    private var _binding: FragmentTrashBinding? = null
    private val binding get() = _binding!!

    private val auth: FirebaseAuth? by lazy { runCatching { FirebaseAuth.getInstance() }.getOrNull() }
    private val firestore: FirebaseFirestore? by lazy { runCatching { FirebaseFirestore.getInstance() }.getOrNull() }

    private lateinit var adapter: TrashAdapter
    private var listener: ListenerRegistration? = null

    /** buildIds with a permanent-delete in flight, so we don't fire it twice. */
    private val deleting = mutableSetOf<String>()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentTrashBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        adapter = TrashAdapter(
            onRestore = ::confirmRestore,
            onDeleteForever = ::confirmDeleteForever
        )
        binding.topBarBack.setOnClickListener { (activity as? MainActivity)?.goBackFromOverlay() }
        binding.trashList.layoutManager = LinearLayoutManager(requireContext())
        binding.trashList.adapter = adapter
        loadTrash()
    }

    private fun loadTrash() {
        val user = auth?.currentUser
        val db = firestore
        if (user == null || db == null) {
            showEmpty(true)
            return
        }

        binding.trashProgress.visibility = View.VISIBLE
        listener = db.collection("users").document(user.uid)
            .collection("trashApps")
            .orderBy("trashedAt", Query.Direction.DESCENDING)
            .addSnapshotListener { snapshot, error ->
                if (_binding == null) return@addSnapshotListener
                binding.trashProgress.visibility = View.GONE
                if (error != null) {
                    Log.w(TAG, "Trash listener error: ${error.message}")
                    return@addSnapshotListener
                }
                val items = snapshot?.documents?.map { doc ->
                    BuildItem(
                        buildId = doc.getString("buildId") ?: doc.id,
                        appName = doc.getString("appName") ?: "",
                        url = doc.getString("url") ?: "",
                        status = doc.getString("status") ?: "READY",
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
        binding.trashList.visibility = if (empty) View.GONE else View.VISIBLE
    }

    // ---- Restore -------------------------------------------------------------

    private fun confirmRestore(item: BuildItem) {
        val name = item.appName.ifEmpty { getString(R.string.app_display_name) }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.restore_title)
            .setMessage(getString(R.string.restore_message, name))
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_restore) { _, _ -> restore(item) }
            .show()
    }

    private fun restore(item: BuildItem) {
        val user = auth?.currentUser ?: return
        val db = firestore ?: return
        val builds = db.collection("users").document(user.uid).collection("builds")
        val trash = db.collection("users").document(user.uid).collection("trashApps")

        trash.document(item.buildId).get()
            .addOnSuccessListener { snap ->
                if (!isAdded) return@addOnSuccessListener
                val data = HashMap(snap.data ?: emptyMap())
                data.remove("trashedAt")
                builds.document(item.buildId).set(data)
                    .addOnSuccessListener {
                        trash.document(item.buildId).delete()
                            .addOnSuccessListener {
                                if (isAdded) Toast.makeText(requireContext(), R.string.restored_from_trash, Toast.LENGTH_SHORT).show()
                            }
                            .addOnFailureListener { fail(it) }
                    }
                    .addOnFailureListener { fail(it) }
            }
            .addOnFailureListener { fail(it) }
    }

    // ---- Permanent delete ----------------------------------------------------

    private fun confirmDeleteForever(item: BuildItem) {
        val name = item.appName.ifEmpty { getString(R.string.app_display_name) }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.delete_forever_title)
            .setMessage(getString(R.string.delete_forever_message, name))
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_delete_forever) { _, _ -> deleteForever(item) }
            .show()
    }

    /**
     * Full wipe: first ask the Worker to delete the app's Cloudflare files
     * (APK/AAB/keystore) using the owner's ID token, then remove the Firestore
     * doc from trashApps. Only when both are gone is the app truly deleted.
     */
    private fun deleteForever(item: BuildItem) {
        if (!deleting.add(item.buildId)) return
        val user = auth?.currentUser
        if (user == null) {
            deleting.remove(item.buildId)
            if (isAdded) Toast.makeText(requireContext(), R.string.delete_failed, Toast.LENGTH_SHORT).show()
            return
        }
        if (isAdded) Toast.makeText(requireContext(), R.string.deleting, Toast.LENGTH_SHORT).show()

        user.getIdToken(false)
            .addOnSuccessListener { result ->
                val token = result.token
                if (token.isNullOrEmpty()) {
                    deleting.remove(item.buildId)
                    if (isAdded) Toast.makeText(requireContext(), R.string.delete_failed, Toast.LENGTH_SHORT).show()
                    return@addOnSuccessListener
                }
                BuildApi.deleteApp(
                    buildId = item.buildId,
                    idToken = token,
                    onSuccess = { deleteTrashDoc(item) },
                    onError = { msg ->
                        deleting.remove(item.buildId)
                        Log.w(TAG, "Server delete failed: $msg")
                        if (isAdded) Toast.makeText(requireContext(), R.string.delete_failed, Toast.LENGTH_SHORT).show()
                    }
                )
            }
            .addOnFailureListener {
                deleting.remove(item.buildId)
                fail(it)
            }
    }

    private fun deleteTrashDoc(item: BuildItem) {
        val user = auth?.currentUser
        val db = firestore
        if (user == null || db == null) {
            deleting.remove(item.buildId)
            return
        }
        db.collection("users").document(user.uid)
            .collection("trashApps").document(item.buildId).delete()
            .addOnSuccessListener {
                deleting.remove(item.buildId)
                if (isAdded) Toast.makeText(requireContext(), R.string.deleted_forever, Toast.LENGTH_SHORT).show()
            }
            .addOnFailureListener {
                deleting.remove(item.buildId)
                fail(it)
            }
    }

    private fun fail(e: Exception) {
        Log.w(TAG, "Trash op failed: ${e.message}")
        if (isAdded) Toast.makeText(requireContext(), R.string.delete_failed, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroyView() {
        listener?.remove()
        listener = null
        _binding = null
        super.onDestroyView()
    }

    private companion object {
        private const val TAG = "TrashFragment"
    }
}
