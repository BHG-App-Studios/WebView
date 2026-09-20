package com.BHG.webapp

import android.app.DownloadManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.BHG.webapp.databinding.FragmentHomeBinding
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import org.json.JSONObject

/**
 * The builder screen. Collects a URL, feature toggles, and permission toggles;
 * registers the build in Firestore; dispatches it to the Worker with a fresh
 * Firebase ID token; then listens in real time for the terminal status.
 *
 * All async callbacks guard on isAdded/view availability, so nothing touches a
 * detached fragment. Network runs off the main thread inside [BuildApi].
 */
class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    private val auth: FirebaseAuth? by lazy { runCatching { FirebaseAuth.getInstance() }.getOrNull() }
    private val firestore: FirebaseFirestore? by lazy { runCatching { FirebaseFirestore.getInstance() }.getOrNull() }

    private var buildInProgress = false
    private var currentBuildId: String? = null
    private var currentDownloadUrl: String? = null
    private var buildListener: ListenerRegistration? = null

    private val featureSwitches = LinkedHashMap<String, MaterialSwitch>()
    private val permissionSwitches = LinkedHashMap<String, MaterialSwitch>()

    private data class FeatureOption(val key: String, val labelRes: Int, val default: Boolean)
    private data class PermissionOption(
        val key: String,
        val labelRes: Int,
        val removeKeywords: List<String>,
        val defaultOn: Boolean
    )

    private val featureOptions = listOf(
        FeatureOption("progress_bar", R.string.opt_progress_bar, true),
        FeatureOption("circular_progress", R.string.opt_circular_progress, false),
        FeatureOption("swipe_refresh", R.string.opt_swipe_refresh, true),
        FeatureOption("file_upload", R.string.opt_file_upload, true),
        FeatureOption("multiple_files", R.string.opt_multiple_files, true),
        FeatureOption("downloads", R.string.opt_downloads, true),
        FeatureOption("external_links", R.string.opt_external_links, true),
        FeatureOption("offline_page", R.string.opt_offline_page, true),
        FeatureOption("fullscreen_video", R.string.opt_fullscreen_video, true)
    )

    private val permissionOptions = listOf(
        PermissionOption("camera", R.string.perm_camera, listOf("CAMERA"), true),
        PermissionOption("microphone", R.string.perm_microphone, listOf("MICROPHONE", "MODIFY_AUDIO_SETTINGS"), true),
        PermissionOption("location", R.string.perm_location, listOf("LOCATION"), true),
        PermissionOption("vibrate", R.string.perm_vibrate, listOf("VIBRATE"), true),
        PermissionOption("notifications", R.string.perm_notifications, listOf("POST_NOTIFICATIONS"), true)
    )

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        buildFeatureToggles()
        buildPermissionToggles()
        binding.topBarMenu.setOnClickListener { (activity as? MainActivity)?.openDrawer() }
        binding.buildButton.setOnClickListener { onBuildClicked() }
        binding.downloadButton.setOnClickListener { startDownload() }
    }

    // ---- Toggles -------------------------------------------------------------

    private fun buildFeatureToggles() {
        for (option in featureOptions) {
            val sw = makeSwitch(getString(option.labelRes), option.default)
            featureSwitches[option.key] = sw
            binding.featureContainer.addView(sw)
        }
    }

    private fun buildPermissionToggles() {
        for (option in permissionOptions) {
            val sw = makeSwitch(getString(option.labelRes), option.defaultOn)
            permissionSwitches[option.key] = sw
            binding.permissionContainer.addView(sw)
        }
    }

    private fun makeSwitch(label: String, checked: Boolean): MaterialSwitch {
        return MaterialSwitch(requireContext()).apply {
            text = label
            isChecked = checked
            textSize = 15f
            val padV = (12 * resources.displayMetrics.density).toInt()
            setPadding(0, padV, 0, padV)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
    }

    // ---- Build flow ----------------------------------------------------------

    private fun onBuildClicked() {
        if (buildInProgress) return

        val url = binding.urlInput.text?.toString()?.trim().orEmpty()
        val appName = binding.appNameInput.text?.toString()?.trim().orEmpty()
        val packageName = binding.packageInput.text?.toString()?.trim().orEmpty()

        binding.urlLayout.error = null

        if (url.isEmpty()) {
            binding.urlLayout.error = getString(R.string.error_url_required)
            return
        }
        if (!isValidHttpUrl(url)) {
            binding.urlLayout.error = getString(R.string.error_url_invalid)
            return
        }
        if (isOffline()) {
            toast(R.string.error_no_network)
            return
        }

        val user = auth?.currentUser ?: return
        val buildId = newBuildId()
        val downloadUrl = "${BuildApi.BASE_URL}/download/$buildId.apk"
        currentBuildId = buildId
        currentDownloadUrl = downloadUrl

        val request = buildRequestJson(url, appName, packageName, buildId)

        setBuilding(true)
        showStatus(getString(R.string.building), showSpinner = true, showDownload = false)

        registerAndDispatch(user, buildId, url, appName, packageName, downloadUrl, request)
    }

    private fun registerAndDispatch(
        user: FirebaseUser,
        buildId: String,
        url: String,
        appName: String,
        packageName: String,
        downloadUrl: String,
        request: JSONObject
    ) {
        val db = firestore
        if (db == null) {
            failBuild(getString(R.string.build_failed))
            return
        }

        val record = hashMapOf(
            "buildId" to buildId,
            "url" to url,
            "appName" to appName.ifEmpty { Uri.parse(url).host ?: "" },
            "packageName" to packageName.ifEmpty { "auto" },
            "options" to featureOptions.associate { opt ->
                opt.key to (featureSwitches[opt.key]?.isChecked ?: opt.default)
            },
            "removePermissions" to permissionOptions
                .filter { permissionSwitches[it.key]?.isChecked == false }
                .flatMap { it.removeKeywords }
                .distinct(),
            "downloadUrl" to downloadUrl,
            "status" to "BUILDING",
            "sizeBytes" to 0L,
            "createdAt" to FieldValue.serverTimestamp()
        )

        db.collection("users").document(user.uid)
            .collection("builds").document(buildId)
            .set(record)
            .addOnSuccessListener {
                if (!isSafe(buildId)) return@addOnSuccessListener
                dispatchBuild(user, buildId, request)
            }
            .addOnFailureListener { e ->
                if (!isAdded) return@addOnFailureListener
                Log.w(TAG, "Build register failed: ${e.message}")
                failBuild(getString(R.string.build_failed))
            }
    }

    private fun dispatchBuild(user: FirebaseUser, buildId: String, request: JSONObject) {
        user.getIdToken(false)
            .addOnSuccessListener { result ->
                if (!isSafe(buildId)) return@addOnSuccessListener
                val token = result.token
                if (token.isNullOrEmpty()) {
                    failBuild(getString(R.string.build_failed))
                    return@addOnSuccessListener
                }
                callWorker(buildId, request, token)
            }
            .addOnFailureListener { e ->
                if (!isAdded) return@addOnFailureListener
                Log.w(TAG, "ID token fetch failed: ${e.message}")
                failBuild(getString(R.string.build_failed))
            }
    }

    private fun callWorker(buildId: String, request: JSONObject, idToken: String) {
        BuildApi.build(
            request = request,
            idToken = idToken,
            onSuccess = { result ->
                if (!isSafe(buildId)) return@build
                currentDownloadUrl = result.downloadUrl.ifEmpty { currentDownloadUrl }
                showStatus(getString(R.string.build_queued), showSpinner = true, showDownload = false)
                observeBuild(buildId)
            },
            onError = { message ->
                if (!isSafe(buildId)) return@build
                Log.w(TAG, "Build dispatch failed: $message")
                updateBuildStatus("REJECTED", 0L)
                failBuild(getString(R.string.build_failed))
            }
        )
    }

    private fun buildRequestJson(
        url: String,
        appName: String,
        packageName: String,
        buildId: String
    ): JSONObject {
        val json = JSONObject()
        json.put("url", url)
        json.put("build_id", buildId)
        if (appName.isNotEmpty()) json.put("app_name", appName)
        if (packageName.isNotEmpty()) json.put("package_name", packageName)

        for (option in featureOptions) {
            json.put(option.key, featureSwitches[option.key]?.isChecked ?: option.default)
        }

        val toRemove = LinkedHashSet<String>()
        for (option in permissionOptions) {
            val on = permissionSwitches[option.key]?.isChecked ?: option.defaultOn
            if (!on) toRemove.addAll(option.removeKeywords)
        }
        if (toRemove.isNotEmpty()) {
            json.put("remove_permissions", BuildApi.jsonArrayOf(toRemove))
        }
        return json
    }

    // ---- Live status ---------------------------------------------------------

    /**
     * Watches users/{uid}/builds/{buildId}. The Worker flips it to READY (on APK
     * upload) or FAILED (from GitHub Actions on error), so the UI reacts the
     * instant the build resolves — no polling, no timeout.
     */
    private fun observeBuild(buildId: String) {
        val user = auth?.currentUser ?: return
        val db = firestore ?: return
        detachListener()

        buildListener = db.collection("users").document(user.uid)
            .collection("builds").document(buildId)
            .addSnapshotListener { snapshot, error ->
                if (!isSafe(buildId)) return@addSnapshotListener
                if (error != null) {
                    Log.w(TAG, "Build listener error: ${error.message}")
                    return@addSnapshotListener
                }
                when (snapshot?.getString("status")) {
                    "READY" -> {
                        setBuilding(false)
                        showStatus(getString(R.string.build_ready), showSpinner = false, showDownload = true)
                    }
                    "FAILED", "REJECTED" -> {
                        setBuilding(false)
                        showStatus(getString(R.string.build_failed), showSpinner = false, showDownload = false)
                    }
                    // else: keep waiting
                }
            }
    }

    private fun detachListener() {
        buildListener?.remove()
        buildListener = null
    }

    private fun updateBuildStatus(status: String, sizeBytes: Long) {
        val user = auth?.currentUser ?: return
        val db = firestore ?: return
        val buildId = currentBuildId ?: return
        val update = mutableMapOf<String, Any>(
            "status" to status,
            "updatedAt" to FieldValue.serverTimestamp()
        )
        if (sizeBytes > 0) update["sizeBytes"] = sizeBytes
        db.collection("users").document(user.uid)
            .collection("builds").document(buildId)
            .update(update)
            .addOnFailureListener { e -> Log.w(TAG, "Status update failed: ${e.message}") }
    }

    // ---- Download ------------------------------------------------------------

    private fun startDownload() {
        val downloadUrl = currentDownloadUrl ?: return
        val uri = Uri.parse(downloadUrl)
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
            toast(R.string.download_started)
        } catch (e: Exception) {
            Log.w(TAG, "Download enqueue failed: ${e.message}")
            openInBrowser(uri)
        }
    }

    private fun openInBrowser(uri: Uri) {
        try {
            startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, uri))
        } catch (e: Exception) {
            toast(R.string.error_generic)
        }
    }

    // ---- UI helpers ----------------------------------------------------------

    private fun setBuilding(building: Boolean) {
        buildInProgress = building
        if (_binding == null) return
        binding.buildButton.isEnabled = !building
        binding.urlInput.isEnabled = !building
        binding.appNameInput.isEnabled = !building
        binding.packageInput.isEnabled = !building
        featureSwitches.values.forEach { it.isEnabled = !building }
        permissionSwitches.values.forEach { it.isEnabled = !building }
    }

    private fun showStatus(message: String, showSpinner: Boolean, showDownload: Boolean) {
        if (_binding == null) return
        binding.statusCard.visibility = View.VISIBLE
        binding.statusText.text = message
        binding.statusProgress.visibility = if (showSpinner) View.VISIBLE else View.GONE
        binding.statusIcon.visibility = if (!showSpinner && showDownload) View.VISIBLE else View.GONE
        binding.downloadButton.visibility = if (showDownload) View.VISIBLE else View.GONE
    }

    private fun failBuild(message: String) {
        setBuilding(false)
        showStatus(message, showSpinner = false, showDownload = false)
        if (isAdded) Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
    }

    private fun toast(resId: Int) {
        if (isAdded) Toast.makeText(requireContext(), resId, Toast.LENGTH_SHORT).show()
    }

    /** True only while this fragment is attached, its view alive, and the id current. */
    private fun isSafe(buildId: String): Boolean =
        isAdded && _binding != null && buildId == currentBuildId

    private fun isValidHttpUrl(raw: String): Boolean {
        return try {
            val uri = Uri.parse(raw)
            val scheme = uri.scheme?.lowercase()
            (scheme == "http" || scheme == "https") && !uri.host.isNullOrBlank()
        } catch (e: Exception) {
            false
        }
    }

    private fun isOffline(): Boolean {
        val manager = requireContext().getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val network = manager.activeNetwork ?: return true
        val capabilities = manager.getNetworkCapabilities(network) ?: return true
        return !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun newBuildId(): String {
        val chars = "abcdefghijklmnopqrstuvwxyz0123456789"
        val suffix = buildString { repeat(5) { append(chars.random()) } }
        return "app_${System.currentTimeMillis()}_$suffix"
    }

    override fun onDestroyView() {
        detachListener()
        _binding = null
        super.onDestroyView()
    }

    private companion object {
        private const val TAG = "HomeFragment"
    }
}
