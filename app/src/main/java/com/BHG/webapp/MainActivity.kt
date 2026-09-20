package com.BHG.webapp

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.BHG.webapp.databinding.ActivityMainBinding
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import org.json.JSONObject

/**
 * Website-to-App builder screen.
 *
 * The user enters a URL and toggles features/permissions; on Build the request
 * is dispatched to the Cloudflare Worker ([BuildApi]), the full configuration
 * and result are written to Firestore under users/{uid}/builds/{buildId}, and
 * the build is polled until the APK is ready to download.
 *
 * Design notes:
 *  - Crash-proof: an auth guard sends signed-out users back to [AuthActivity];
 *    every async callback bails if the activity is finishing/destroyed; polling
 *    is stopped in onDestroy.
 *  - Thread-safe: [BuildApi] runs network work off the main thread and delivers
 *    callbacks back on it, so all UI/Firestore calls here stay on the main thread.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private var auth: FirebaseAuth? = null
    private var firestore: FirebaseFirestore? = null

    private var buildInProgress = false
    private var currentBuildId: String? = null
    private var currentDownloadUrl: String? = null

    // Live status
    private var buildListener: ListenerRegistration? = null

    // Generated toggle views, keyed by their server option/permission key.
    private val featureSwitches = LinkedHashMap<String, MaterialSwitch>()
    private val permissionSwitches = LinkedHashMap<String, MaterialSwitch>()

    /** A boolean AppConfig option the server understands, with its default. */
    private data class FeatureOption(val key: String, val labelRes: Int, val default: Boolean)

    /**
     * A permission the template manifest declares and the server can strip.
     * [removeKeywords] are the names sent in remove_permissions when the switch
     * is OFF (matching tools/set-manifest-permissions.js vocabulary).
     */
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
        // Mic capture in a WebView needs both the runtime permission and the audio-settings one.
        PermissionOption("microphone", R.string.perm_microphone, listOf("MICROPHONE", "MODIFY_AUDIO_SETTINGS"), true),
        PermissionOption("location", R.string.perm_location, listOf("LOCATION"), true),
        PermissionOption("vibrate", R.string.perm_vibrate, listOf("VIBRATE"), true),
        PermissionOption("notifications", R.string.perm_notifications, listOf("POST_NOTIFICATIONS"), true)
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // Guard: never show the app while signed out (killed session, post sign-out, etc.).
        auth = try {
            FirebaseAuth.getInstance()
        } catch (e: Exception) {
            null
        }
        if (auth?.currentUser == null) {
            startActivity(Intent(this, AuthActivity::class.java))
            finish()
            return
        }

        firestore = try {
            FirebaseFirestore.getInstance()
        } catch (e: Exception) {
            Log.w(TAG, "Firestore init failed: ${e.message}")
            null
        }

        // Refresh lastActiveAt on every launch (user is guaranteed signed in here).
        auth?.currentUser?.let { saveProfile(it) }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            v.updatePadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        buildFeatureToggles()
        buildPermissionToggles()

        binding.toolbar.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.action_sign_out) {
                signOut()
                true
            } else {
                false
            }
        }

        binding.buildButton.setOnClickListener { onBuildClicked() }
        binding.downloadButton.setOnClickListener { startDownload() }
    }

    // ---- Toggle construction -------------------------------------------------

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
        return MaterialSwitch(this).apply {
            text = label
            isChecked = checked
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

        val user = auth?.currentUser
        if (user == null) {
            startActivity(Intent(this, AuthActivity::class.java))
            finish()
            return
        }

        // The build id is generated here, registered in Firestore, then handed
        // to the Worker. The Worker re-reads this exact doc with a service
        // account and rejects the build unless it exists under this uid and is
        // less than 5 minutes old — so a caller cannot fabricate a request.
        val buildId = newBuildId()
        val downloadUrl = "${BuildApi.BASE_URL}/download/$buildId.apk"
        currentBuildId = buildId
        currentDownloadUrl = downloadUrl

        val request = buildRequestJson(url, appName, packageName, buildId)

        setBuilding(true)
        showStatus(getString(R.string.building), showSpinner = true, showDownload = false)

        registerAndDispatch(user, buildId, url, appName, packageName, downloadUrl, request)
    }

    /**
     * Registers the build doc first, then dispatches to the Worker only after
     * the write is acknowledged by the server — so the doc (with its server
     * timestamp) is guaranteed to be readable when the Worker checks it.
     */
    private fun registerAndDispatch(
        user: com.google.firebase.auth.FirebaseUser,
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

        saveProfile(user)

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
                if (isFinishing || isDestroyed || buildId != currentBuildId) return@addOnSuccessListener
                dispatchBuild(user, buildId, request)
            }
            .addOnFailureListener { e ->
                if (isFinishing || isDestroyed) return@addOnFailureListener
                Log.w(TAG, "Build register failed: ${e.message}")
                failBuild(getString(R.string.build_failed))
            }
    }

    /** Fetches a fresh ID token, then calls the Worker. */
    private fun dispatchBuild(
        user: com.google.firebase.auth.FirebaseUser,
        buildId: String,
        request: JSONObject
    ) {
        user.getIdToken(false)
            .addOnSuccessListener { result ->
                if (isFinishing || isDestroyed || buildId != currentBuildId) return@addOnSuccessListener
                val token = result.token
                if (token.isNullOrEmpty()) {
                    failBuild(getString(R.string.build_failed))
                    return@addOnSuccessListener
                }
                callWorker(buildId, request, token)
            }
            .addOnFailureListener { e ->
                if (isFinishing || isDestroyed) return@addOnFailureListener
                Log.w(TAG, "ID token fetch failed: ${e.message}")
                failBuild(getString(R.string.build_failed))
            }
    }

    private fun callWorker(buildId: String, request: JSONObject, idToken: String) {
        BuildApi.build(
            request = request,
            idToken = idToken,
            onSuccess = { result ->
                if (isFinishing || isDestroyed || buildId != currentBuildId) return@build
                currentDownloadUrl = result.downloadUrl.ifEmpty { currentDownloadUrl }
                showStatus(getString(R.string.build_queued), showSpinner = true, showDownload = false)
                observeBuild(buildId)
            },
            onError = { message ->
                if (isFinishing || isDestroyed || buildId != currentBuildId) return@build
                Log.w(TAG, "Build dispatch failed: $message")
                updateBuildStatus("REJECTED", 0L)
                failBuild(getString(R.string.build_failed))
            }
        )
    }

    private fun failBuild(message: String) {
        setBuilding(false)
        showStatus(message, showSpinner = false, showDownload = false)
        if (!isFinishing && !isDestroyed) {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
    }

    /** Assembles the exact JSON contract the Worker expects. */
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

        // Feature options -> top-level boolean keys.
        for (option in featureOptions) {
            val checked = featureSwitches[option.key]?.isChecked ?: option.default
            json.put(option.key, checked)
        }

        // Permissions: an OFF switch contributes its keyword(s) to remove_permissions.
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

    // ---- Live status via Firestore listener ---------------------------------

    /**
     * Watches users/{uid}/builds/{buildId} in real time. The Worker flips the
     * doc to READY (on APK upload) or FAILED (from GitHub Actions on error), so
     * the UI reacts the instant the build resolves — no polling, no timeout.
     * The listener simply waits until a terminal status arrives.
     */
    private fun observeBuild(buildId: String) {
        val user = auth?.currentUser ?: return
        val db = firestore ?: return

        detachListener()

        buildListener = db.collection("users").document(user.uid)
            .collection("builds").document(buildId)
            .addSnapshotListener { snapshot, error ->
                if (isFinishing || isDestroyed || buildId != currentBuildId) return@addSnapshotListener
                if (error != null) {
                    Log.w(TAG, "Build listener error: ${error.message}")
                    return@addSnapshotListener
                }
                val status = snapshot?.getString("status") ?: return@addSnapshotListener
                when (status) {
                    "READY" -> {
                        setBuilding(false)
                        showStatus(getString(R.string.build_ready), showSpinner = false, showDownload = true)
                    }
                    "FAILED", "REJECTED" -> {
                        setBuilding(false)
                        showStatus(getString(R.string.build_failed), showSpinner = false, showDownload = false)
                    }
                    // "BUILDING" and anything else: keep waiting.
                }
            }
    }

    private fun detachListener() {
        buildListener?.remove()
        buildListener = null
    }

    // ---- Firestore -----------------------------------------------------------

    /** Upserts the user profile under users/{uid} (merge, never clobbers builds). */
    private fun saveProfile(user: com.google.firebase.auth.FirebaseUser) {
        val db = firestore ?: return
        val profile = mapOf(
            "uid" to user.uid,
            "email" to (user.email ?: ""),
            "displayName" to (user.displayName ?: ""),
            "photoUrl" to (user.photoUrl?.toString() ?: ""),
            "lastActiveAt" to FieldValue.serverTimestamp()
        )
        db.collection("users").document(user.uid)
            .set(profile, SetOptions.merge())
            .addOnFailureListener { e -> Log.w(TAG, "Profile save failed: ${e.message}") }
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

        val manager = getSystemService(DownloadManager::class.java)
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
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (e: Exception) {
            toast(R.string.error_generic)
        }
    }

    // ---- Auth ----------------------------------------------------------------

    private fun signOut() {
        try {
            auth?.signOut()
        } catch (e: Exception) {
            Log.w(TAG, "Sign-out failed: ${e.message}")
        }
        startActivity(Intent(this, AuthActivity::class.java))
        finish()
    }

    // ---- UI helpers ----------------------------------------------------------

    private fun setBuilding(building: Boolean) {
        buildInProgress = building
        binding.buildButton.isEnabled = !building
        setFormEnabled(!building)
    }

    private fun setFormEnabled(enabled: Boolean) {
        binding.urlInput.isEnabled = enabled
        binding.appNameInput.isEnabled = enabled
        binding.packageInput.isEnabled = enabled
        featureSwitches.values.forEach { it.isEnabled = enabled }
        permissionSwitches.values.forEach { it.isEnabled = enabled }
    }

    private fun showStatus(message: String, showSpinner: Boolean, showDownload: Boolean) {
        binding.statusCard.visibility = View.VISIBLE
        binding.statusText.text = message
        binding.statusProgress.visibility = if (showSpinner) View.VISIBLE else View.GONE
        binding.downloadButton.visibility = if (showDownload) View.VISIBLE else View.GONE
    }

    private fun toast(resId: Int) {
        if (isFinishing || isDestroyed) return
        Toast.makeText(this, resId, Toast.LENGTH_SHORT).show()
    }

    private fun isValidHttpUrl(raw: String): Boolean {
        return try {
            val uri = Uri.parse(raw)
            val scheme = uri.scheme?.lowercase()
            (scheme == "http" || scheme == "https") && !uri.host.isNullOrBlank()
        } catch (e: Exception) {
            false
        }
    }

    /** Matches the Worker's expected id shape: app_<millis>_<shortrandom>. */
    private fun newBuildId(): String {
        val chars = "abcdefghijklmnopqrstuvwxyz0123456789"
        val suffix = buildString { repeat(5) { append(chars.random()) } }
        return "app_${System.currentTimeMillis()}_$suffix"
    }

    private fun isOffline(): Boolean {
        val manager = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val network = manager.activeNetwork ?: return true
        val capabilities = manager.getNetworkCapabilities(network) ?: return true
        return !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    override fun onDestroy() {
        detachListener()
        super.onDestroy()
    }

    private companion object {
        private const val TAG = "MainActivity"
    }
}
