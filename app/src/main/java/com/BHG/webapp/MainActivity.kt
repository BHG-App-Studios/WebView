package com.BHG.webapp

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
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

    private val mainHandler = Handler(Looper.getMainLooper())

    private var buildInProgress = false
    private var currentBuildId: String? = null
    private var currentDownloadUrl: String? = null

    // Poll state
    private var pollAttempts = 0
    private var pollRunnable: Runnable? = null

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

        val request = buildRequestJson(url, appName, packageName)

        setBuilding(true)
        showStatus(getString(R.string.building), showSpinner = true, showDownload = false)

        BuildApi.build(
            request = request,
            onSuccess = { result ->
                if (isFinishing || isDestroyed) return@build
                currentBuildId = result.buildId
                currentDownloadUrl = result.downloadUrl
                saveBuildRecord(result, request)
                showStatus(getString(R.string.build_queued), showSpinner = true, showDownload = false)
                startPolling(result.buildId)
            },
            onError = { message ->
                if (isFinishing || isDestroyed) return@build
                Log.w(TAG, "Build dispatch failed: $message")
                setBuilding(false)
                showStatus(getString(R.string.build_failed), showSpinner = false, showDownload = false)
                toast(R.string.build_failed)
            }
        )
    }

    /** Assembles the exact JSON contract the Worker expects. */
    private fun buildRequestJson(url: String, appName: String, packageName: String): JSONObject {
        val json = JSONObject()
        json.put("url", url)
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

    // ---- Status polling ------------------------------------------------------

    private fun startPolling(buildId: String) {
        pollAttempts = 0
        scheduleNextPoll(buildId)
    }

    private fun scheduleNextPoll(buildId: String) {
        cancelPolling()
        val runnable = Runnable { poll(buildId) }
        pollRunnable = runnable
        mainHandler.postDelayed(runnable, POLL_INTERVAL_MS)
    }

    private fun poll(buildId: String) {
        if (isFinishing || isDestroyed) return
        if (buildId != currentBuildId) return

        pollAttempts++
        BuildApi.status(
            buildId = buildId,
            onResult = { status ->
                if (isFinishing || isDestroyed || buildId != currentBuildId) return@status
                if (status.ready) {
                    onBuildReady(status.sizeBytes)
                } else if (pollAttempts >= MAX_POLL_ATTEMPTS) {
                    onBuildTimeout()
                } else {
                    scheduleNextPoll(buildId)
                }
            },
            onError = {
                if (isFinishing || isDestroyed || buildId != currentBuildId) return@status
                // Transient errors shouldn't abort a build that may still finish.
                if (pollAttempts >= MAX_POLL_ATTEMPTS) {
                    onBuildTimeout()
                } else {
                    scheduleNextPoll(buildId)
                }
            }
        )
    }

    private fun cancelPolling() {
        pollRunnable?.let { mainHandler.removeCallbacks(it) }
        pollRunnable = null
    }

    private fun onBuildReady(sizeBytes: Long) {
        setBuilding(false)
        showStatus(getString(R.string.build_ready), showSpinner = false, showDownload = true)
        updateBuildStatus("READY", sizeBytes)
    }

    private fun onBuildTimeout() {
        setBuilding(false)
        // The build may still complete server-side; the download button lets them retry.
        showStatus(getString(R.string.build_timeout), showSpinner = false, showDownload = true)
        updateBuildStatus("TIMEOUT", 0L)
    }

    // ---- Firestore -----------------------------------------------------------

    /**
     * Writes the user profile (merge) and the build record.
     *
     * Layout: users/{uid} holds the profile; users/{uid}/builds/{buildId} holds
     * one document per build with its full configuration.
     */
    private fun saveBuildRecord(result: BuildApi.BuildResult, request: JSONObject) {
        val user = auth?.currentUser ?: return
        val db = firestore ?: return
        val userDoc = db.collection("users").document(user.uid)

        val profile = mapOf(
            "uid" to user.uid,
            "email" to (user.email ?: ""),
            "displayName" to (user.displayName ?: ""),
            "photoUrl" to (user.photoUrl?.toString() ?: ""),
            "lastActiveAt" to FieldValue.serverTimestamp()
        )
        userDoc.set(profile, SetOptions.merge())
            .addOnFailureListener { e -> Log.w(TAG, "Profile save failed: ${e.message}") }

        val record = hashMapOf(
            "buildId" to result.buildId,
            "url" to request.optString("url"),
            "appName" to result.appName,
            "packageName" to result.packageName,
            "options" to featureOptions.associate { opt ->
                opt.key to (featureSwitches[opt.key]?.isChecked ?: opt.default)
            },
            "removePermissions" to permissionOptions
                .filter { permissionSwitches[it.key]?.isChecked == false }
                .flatMap { it.removeKeywords }
                .distinct(),
            "downloadUrl" to result.downloadUrl,
            "status" to "BUILDING",
            "sizeBytes" to 0L,
            "createdAt" to FieldValue.serverTimestamp()
        )

        userDoc.collection("builds").document(result.buildId)
            .set(record)
            .addOnFailureListener { e -> Log.w(TAG, "Build record save failed: ${e.message}") }
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

    private fun isOffline(): Boolean {
        val manager = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val network = manager.activeNetwork ?: return true
        val capabilities = manager.getNetworkCapabilities(network) ?: return true
        return !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    override fun onDestroy() {
        cancelPolling()
        super.onDestroy()
    }

    private companion object {
        private const val TAG = "MainActivity"
        private const val POLL_INTERVAL_MS = 8_000L
        private const val MAX_POLL_ATTEMPTS = 45 // ~6 minutes at 8s intervals
    }
}
