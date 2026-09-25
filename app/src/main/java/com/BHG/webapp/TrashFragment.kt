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

    // Newest list the server sent, plus the raw doc data behind it, so a restore
    // can write the app back to builds without another read.
    private var latestItems: List<BuildItem> = emptyList()
    private var latestData: Map<String, Map<String, Any?>> = emptyMap()
    // BuildIds whose row is hidden right now — a restore or permanent delete in
    // flight. Every callback below lands on the main thread (Firestore and
    // BuildApi both post back there), so these plain sets need no locking.
    private val pendingRemovals = mutableSetOf<String>()
    // BuildIds with an op already running, so a double tap can't fire it twice.
    private val inFlight = mutableSetOf<String>()

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

                latestItems = items
                latestData = snapshot?.documents?.associate { doc ->
                    (doc.getString("buildId") ?: doc.id) to (doc.data ?: emptyMap<String, Any?>())
                }.orEmpty()
                // Anything the server no longer sends back is gone for good, so
                // stop hiding it — the op has landed.
                pendingRemovals.retainAll(items.map { it.buildId }.toSet())
                render()
            }
    }

    /**
     * Draws [latestItems] minus the rows with an op in flight, so Restore and
     * Delete forever both take effect instantly and the listener catches up
     * afterwards.
     */
    private fun render() {
        if (_binding == null) return
        val visible = latestItems.filter { it.buildId !in pendingRemovals }
        adapter.submitList(visible)
        showEmpty(visible.isEmpty())
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

    /**
     * Fire-and-forget restore: the row disappears at once and the write back to
     * builds runs in the background — the listener drops it from trash when it
     * lands. If it fails the row comes back and we say so.
     */
    private fun restore(item: BuildItem) {
        val user = auth?.currentUser ?: return
        val db = firestore ?: return
        if (!inFlight.add(item.buildId)) return

        val raw = latestData[item.buildId]
        if (raw == null) {
            // Nothing cached to write back — the doc is already gone; let the
            // listener settle the list on its own.
            inFlight.remove(item.buildId)
            render()
            return
        }

        // Optimistic: drop the row now, don't wait for Firestore.
        pendingRemovals.add(item.buildId)
        render()
        if (isAdded) Toast.makeText(requireContext(), R.string.restored_from_trash, Toast.LENGTH_SHORT).show()

        val data = HashMap<String, Any?>(raw)
        data.remove("trashedAt")
        val builds = db.collection("users").document(user.uid)
            .collection("builds").document(item.buildId)
        val trash = db.collection("users").document(user.uid)
            .collection("trashApps").document(item.buildId)

        // No success handling needed: the listener reports the result for us.
        builds.set(data)
            .addOnSuccessListener {
                inFlight.remove(item.buildId)
                trash.delete().addOnFailureListener { failRestore(item, it) }
            }
            .addOnFailureListener { failRestore(item, it) }
    }

    private fun failRestore(item: BuildItem, e: Exception) {
        Log.w(TAG, "Restore failed: ${e.message}", e)
        inFlight.remove(item.buildId)
        pendingRemovals.remove(item.buildId)
        render()
        if (isAdded) Toast.makeText(requireContext(), R.string.delete_failed, Toast.LENGTH_SHORT).show()
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
     *
     * The row is hidden straight away so the tap feels instant, but the wipe is
     * irreversible — so any failure below puts the row back and reports it.
     */
    private fun deleteForever(item: BuildItem) {
        if (!inFlight.add(item.buildId)) return
        val user = auth?.currentUser
        if (user == null) {
            inFlight.remove(item.buildId)
            if (isAdded) Toast.makeText(requireContext(), R.string.delete_failed, Toast.LENGTH_SHORT).show()
            return
        }

        pendingRemovals.add(item.buildId)
        render()
        if (isAdded) Toast.makeText(requireContext(), R.string.deleting, Toast.LENGTH_SHORT).show()

        user.getIdToken(false)
            .addOnSuccessListener { result ->
                val token = result.token
                if (token.isNullOrEmpty()) {
                    failDelete(item, null)
                    return@addOnSuccessListener
                }
                BuildApi.deleteApp(
                    buildId = item.buildId,
                    idToken = token,
                    onSuccess = { deleteTrashDoc(item) },
                    onError = { msg ->
                        Log.w(TAG, "Server delete failed: $msg")
                        failDelete(item, null)
                    }
                )
            }
            .addOnFailureListener { failDelete(item, it) }
    }

    private fun deleteTrashDoc(item: BuildItem) {
        val user = auth?.currentUser
        val db = firestore
        if (user == null || db == null) {
            failDelete(item, null)
            return
        }
        db.collection("users").document(user.uid)
            .collection("trashApps").document(item.buildId).delete()
            .addOnSuccessListener {
                inFlight.remove(item.buildId)
                if (isAdded) Toast.makeText(requireContext(), R.string.deleted_forever, Toast.LENGTH_SHORT).show()
            }
            .addOnFailureListener { failDelete(item, it) }
    }

    /**
     * Puts a hidden row back and reports the failure. [e] is null when the
     * failure came from BuildApi, which only hands back a message.
     */
    private fun failDelete(item: BuildItem, e: Exception?) {
        if (e != null) Log.w(TAG, "Trash op failed: ${e.message}", e)
        inFlight.remove(item.buildId)
        pendingRemovals.remove(item.buildId)
        render()
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
