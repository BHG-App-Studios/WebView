package com.BHG.webapp

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
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
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.BHG.webapp.databinding.FragmentHomeBinding
import com.BHG.webapp.databinding.StepBuildingBinding
import com.BHG.webapp.databinding.StepFeaturesBinding
import com.BHG.webapp.databinding.StepPermissionsBinding
import com.BHG.webapp.databinding.StepWebsiteBinding
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import org.json.JSONObject

/**
 * The builder screen — now a 4-step wizard driven by a ViewPager2.
 *
 * Step 0: Website URL, App name, Package name
 * Step 1: Feature toggles
 * Step 2: Permission toggles + Build button
 * Step 3: Building / Done screen (animated ring + live Firestore listener)
 */
class HomeFragment : Fragment() {

    // ── Root binding ──────────────────────────────────────────────────────────
    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    // ── Step bindings (lazily cached from the adapter) ────────────────────────
    private var stepWebsiteBinding: StepWebsiteBinding? = null
    private var stepFeaturesBinding: StepFeaturesBinding? = null
    private var stepPermissionsBinding: StepPermissionsBinding? = null
    private var stepBuildingBinding: StepBuildingBinding? = null

    // ── Firebase ──────────────────────────────────────────────────────────────
    private val auth: FirebaseAuth? by lazy { runCatching { FirebaseAuth.getInstance() }.getOrNull() }
    private val firestore: FirebaseFirestore? by lazy { runCatching { FirebaseFirestore.getInstance() }.getOrNull() }

    // ── Build state ───────────────────────────────────────────────────────────
    private var buildInProgress = false
    private var currentBuildId: String? = null
    private var currentDownloadUrl: String? = null
    private var buildListener: ListenerRegistration? = null

    // ── Pulse animation ───────────────────────────────────────────────────────
    private var pulseAnimSet: AnimatorSet? = null

    // ── Toggle data ───────────────────────────────────────────────────────────
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
        FeatureOption("progress_bar",       R.string.opt_progress_bar,       true),
        FeatureOption("circular_progress",  R.string.opt_circular_progress,  false),
        FeatureOption("swipe_refresh",      R.string.opt_swipe_refresh,      true),
        FeatureOption("file_upload",        R.string.opt_file_upload,        true),
        FeatureOption("multiple_files",     R.string.opt_multiple_files,     true),
        FeatureOption("downloads",          R.string.opt_downloads,          true),
        FeatureOption("external_links",     R.string.opt_external_links,     true),
        FeatureOption("offline_page",       R.string.opt_offline_page,       true),
        FeatureOption("fullscreen_video",   R.string.opt_fullscreen_video,   true)
    )

    private val permissionOptions = listOf(
        PermissionOption("camera",        R.string.perm_camera,        listOf("CAMERA"),                        true),
        PermissionOption("microphone",    R.string.perm_microphone,    listOf("MICROPHONE","MODIFY_AUDIO_SETTINGS"), true),
        PermissionOption("location",      R.string.perm_location,      listOf("LOCATION"),                      true),
        PermissionOption("vibrate",       R.string.perm_vibrate,       listOf("VIBRATE"),                       true),
        PermissionOption("notifications", R.string.perm_notifications, listOf("POST_NOTIFICATIONS"),            true)
    )

    // ── Steps ─────────────────────────────────────────────────────────────────
    private val STEP_WEBSITE     = 0
    private val STEP_FEATURES    = 1
    private val STEP_PERMISSIONS = 2
    private val STEP_BUILDING    = 3
    private val TOTAL_STEPS      = 4

    // =========================================================================
    //  Lifecycle
    // =========================================================================

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupWizard()
        setupTopBar()
        setupNextButton()
    }

    // =========================================================================
    //  Wizard setup
    // =========================================================================

    private fun setupWizard() {
        val adapter = WizardPagerAdapter()
        binding.wizardPager.adapter = adapter
        binding.wizardPager.isUserInputEnabled = false   // swipe disabled; buttons only
        binding.wizardPager.offscreenPageLimit = 3        // keep all pages alive for binding refs

        // Custom depth page transformer for a premium slide+scale effect
        binding.wizardPager.setPageTransformer { page, position ->
            val absPos = Math.abs(position)
            page.alpha = 1f - absPos * 0.4f
            page.translationX = -position * page.width * 0.1f
            val scale = 1f - absPos * 0.08f
            page.scaleX = scale
            page.scaleY = scale
        }

        binding.wizardPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                updateStepIndicator(position)
                updateTopBarForStep(position)
                updateNextButtonForStep(position)
                // Start build ring animation when reaching building step
                if (position == STEP_BUILDING && buildInProgress) {
                    startPulseAnimation()
                }
            }
        })
    }

    private fun setupTopBar() {
        binding.topBarMenu.setOnClickListener { (activity as? MainActivity)?.openDrawer() }
        binding.topBarBack.setOnClickListener { goBack() }
    }

    private fun setupNextButton() {
        binding.btnNext.setOnClickListener {
            val current = binding.wizardPager.currentItem
            when (current) {
                STEP_WEBSITE     -> onNextFromWebsite()
                STEP_FEATURES    -> goToStep(STEP_PERMISSIONS)
                STEP_PERMISSIONS -> { /* Next is hidden; Build button handles this */ }
                else             -> { /* No next on building step */ }
            }
        }
    }

    // =========================================================================
    //  Inner adapter
    // =========================================================================

    inner class WizardPagerAdapter : RecyclerView.Adapter<WizardPagerAdapter.StepVH>() {

        inner class StepVH(val root: View) : RecyclerView.ViewHolder(root)

        override fun getItemCount() = TOTAL_STEPS
        override fun getItemViewType(position: Int) = position

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): StepVH {
            val inflater = LayoutInflater.from(parent.context)
            return when (viewType) {
                STEP_WEBSITE -> {
                    val b = StepWebsiteBinding.inflate(inflater, parent, false)
                    stepWebsiteBinding = b
                    StepVH(b.root)
                }
                STEP_FEATURES -> {
                    val b = StepFeaturesBinding.inflate(inflater, parent, false)
                    stepFeaturesBinding = b
                    buildFeatureToggles(b)
                    StepVH(b.root)
                }
                STEP_PERMISSIONS -> {
                    val b = StepPermissionsBinding.inflate(inflater, parent, false)
                    stepPermissionsBinding = b
                    buildPermissionToggles(b)
                    b.buildButton.setOnClickListener { onBuildClicked() }
                    StepVH(b.root)
                }
                STEP_BUILDING -> {
                    val b = StepBuildingBinding.inflate(inflater, parent, false)
                    stepBuildingBinding = b
                    b.downloadButton.setOnClickListener { startDownload() }
                    b.newBuildButton.setOnClickListener { resetWizard() }
                    StepVH(b.root)
                }
                else -> StepVH(View(parent.context))
            }
        }

        override fun onBindViewHolder(holder: StepVH, position: Int) { /* static content */ }
    }

    // =========================================================================
    //  Toggles
    // =========================================================================

    private fun buildFeatureToggles(b: StepFeaturesBinding) {
        for (option in featureOptions) {
            val sw = makeSwitch(getString(option.labelRes), option.default)
            featureSwitches[option.key] = sw
            b.featureContainer.addView(sw)
        }
    }

    private fun buildPermissionToggles(b: StepPermissionsBinding) {
        for (option in permissionOptions) {
            val sw = makeSwitch(getString(option.labelRes), option.defaultOn)
            permissionSwitches[option.key] = sw
            b.permissionContainer.addView(sw)
        }
    }

    private fun makeSwitch(label: String, checked: Boolean): MaterialSwitch {
        return MaterialSwitch(requireContext()).apply {
            text = label
            isChecked = checked
            textSize = 15f
            val padV = (14 * resources.displayMetrics.density).toInt()
            setPadding(0, padV, 0, padV)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
    }

    // =========================================================================
    //  Step navigation helpers
    // =========================================================================

    private fun goToStep(step: Int) {
        binding.wizardPager.setCurrentItem(step, true)
    }

    private fun goBack() {
        val current = binding.wizardPager.currentItem
        if (current > STEP_WEBSITE) {
            binding.wizardPager.setCurrentItem(current - 1, true)
        }
    }

    private fun onNextFromWebsite() {
        val url = stepWebsiteBinding?.urlInput?.text?.toString()?.trim().orEmpty()
        stepWebsiteBinding?.urlLayout?.error = null
        if (url.isEmpty()) {
            stepWebsiteBinding?.urlLayout?.error = getString(R.string.error_url_required)
            return
        }
        if (!isValidHttpUrl(url)) {
            stepWebsiteBinding?.urlLayout?.error = getString(R.string.error_url_invalid)
            return
        }
        goToStep(STEP_FEATURES)
    }

    // =========================================================================
    //  Step indicator dots
    // =========================================================================

    private fun updateStepIndicator(step: Int) {
        val dots = listOf(binding.dot1, binding.dot2, binding.dot3, binding.dot4)
        dots.forEachIndexed { index, dot ->
            val isActive = index == step
            animateDot(dot, isActive)
        }
    }

    private fun animateDot(dot: View, active: Boolean) {
        val targetWidth = if (active) dpToPx(22) else dpToPx(8)
        val targetBg = if (active) R.drawable.bg_step_dot_active else R.drawable.bg_step_dot_inactive
        dot.setBackgroundResource(targetBg)
        val animator = ValueAnimator.ofInt(dot.width, targetWidth).apply {
            duration = 250
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { anim ->
                val lp = dot.layoutParams
                lp.width = anim.animatedValue as Int
                dot.layoutParams = lp
            }
        }
        animator.start()
    }

    private fun updateTopBarForStep(step: Int) {
        val showBack = step > STEP_WEBSITE && step < STEP_BUILDING
        val lockBuilding = step == STEP_BUILDING
        binding.topBarBack.visibility  = if (showBack) View.VISIBLE else View.INVISIBLE
        binding.topBarMenu.visibility  = if (step == STEP_WEBSITE) View.VISIBLE else View.GONE
        val titles = listOf("Build App", "App Features", "Permissions", "Building…")
        binding.topBarTitle.text = titles.getOrNull(step) ?: "Build App"
        // Hide step indicator on building screen
        binding.stepIndicator.visibility = if (lockBuilding) View.GONE else View.VISIBLE
    }

    private fun updateNextButtonForStep(step: Int) {
        when (step) {
            STEP_WEBSITE, STEP_FEATURES -> {
                binding.btnNext.visibility = View.VISIBLE
                binding.btnNext.text = "Next"
                binding.btnNext.setIconResource(R.drawable.ic_arrow_forward)
            }
            else -> binding.btnNext.visibility = View.GONE
        }
    }

    // =========================================================================
    //  Build flow
    // =========================================================================

    private fun onBuildClicked() {
        if (buildInProgress) return
        if (isOffline()) { toast(R.string.error_no_network); return }

        val url         = stepWebsiteBinding?.urlInput?.text?.toString()?.trim().orEmpty()
        val appName     = stepWebsiteBinding?.appNameInput?.text?.toString()?.trim().orEmpty()
        val packageName = stepWebsiteBinding?.packageInput?.text?.toString()?.trim().orEmpty()

        if (url.isEmpty() || !isValidHttpUrl(url)) {
            goToStep(STEP_WEBSITE)
            stepWebsiteBinding?.urlLayout?.error = getString(R.string.error_url_invalid)
            return
        }

        val user = auth?.currentUser ?: return
        val buildId     = newBuildId()
        val downloadUrl = "${BuildApi.BASE_URL}/download/$buildId.apk"
        currentBuildId  = buildId
        currentDownloadUrl = downloadUrl

        val request = buildRequestJson(url, appName, packageName, buildId)

        // Advance to building screen
        goToStep(STEP_BUILDING)
        setBuilding(true)
        startPulseAnimation()
        updateBuildScreen(title = "Building your app…",
            detail = "This usually takes a few minutes.",
            showRing = true, showDownload = false, showNewBuild = false)

        registerAndDispatch(user, buildId, url, appName, packageName, downloadUrl, request)
    }

    private fun registerAndDispatch(
        user: FirebaseUser, buildId: String, url: String, appName: String,
        packageName: String, downloadUrl: String, request: JSONObject
    ) {
        val db = firestore ?: run { failBuild(getString(R.string.build_failed)); return }

        val record = hashMapOf(
            "buildId"           to buildId,
            "url"               to url,
            "appName"           to appName.ifEmpty { Uri.parse(url).host ?: "" },
            "packageName"       to packageName.ifEmpty { "auto" },
            "options"           to featureOptions.associate { it.key to (featureSwitches[it.key]?.isChecked ?: it.default) },
            "removePermissions" to permissionOptions
                .filter { permissionSwitches[it.key]?.isChecked == false }
                .flatMap { it.removeKeywords }.distinct(),
            "downloadUrl"       to downloadUrl,
            "status"            to "BUILDING",
            "sizeBytes"         to 0L,
            "createdAt"         to FieldValue.serverTimestamp()
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
                if (token.isNullOrEmpty()) { failBuild(getString(R.string.build_failed)); return@addOnSuccessListener }
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
            request  = request,
            idToken  = idToken,
            onSuccess = { result ->
                if (!isSafe(buildId)) return@build
                currentDownloadUrl = result.downloadUrl.ifEmpty { currentDownloadUrl }
                updateBuildScreen(
                    title = "Build queued…",
                    detail = "We've received your request. Sit tight!",
                    showRing = true, showDownload = false, showNewBuild = false
                )
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

    private fun buildRequestJson(url: String, appName: String, packageName: String, buildId: String): JSONObject {
        val json = JSONObject().apply {
            put("url", url)
            put("build_id", buildId)
            if (appName.isNotEmpty())     put("app_name",     appName)
            if (packageName.isNotEmpty()) put("package_name", packageName)
        }
        for (option in featureOptions) {
            json.put(option.key, featureSwitches[option.key]?.isChecked ?: option.default)
        }
        val toRemove = LinkedHashSet<String>()
        for (option in permissionOptions) {
            if (permissionSwitches[option.key]?.isChecked == false) toRemove.addAll(option.removeKeywords)
        }
        if (toRemove.isNotEmpty()) json.put("remove_permissions", BuildApi.jsonArrayOf(toRemove))
        return json
    }

    // =========================================================================
    //  Live status
    // =========================================================================

    private fun observeBuild(buildId: String) {
        val user = auth?.currentUser ?: return
        val db   = firestore ?: return
        detachListener()
        buildListener = db.collection("users").document(user.uid)
            .collection("builds").document(buildId)
            .addSnapshotListener { snapshot, error ->
                if (!isSafe(buildId)) return@addSnapshotListener
                if (error != null) { Log.w(TAG, "Build listener error: ${error.message}"); return@addSnapshotListener }
                when (snapshot?.getString("status")) {
                    "READY" -> {
                        setBuilding(false)
                        stopPulseAnimation()
                        updateBuildScreen(
                            title = "🎉 Your app is ready!",
                            detail = "Download the APK and install it on your device.",
                            showRing = false, showDownload = true, showNewBuild = true
                        )
                    }
                    "FAILED", "REJECTED" -> {
                        setBuilding(false)
                        stopPulseAnimation()
                        updateBuildScreen(
                            title = "Build failed",
                            detail = "Something went wrong. Tap below to try again.",
                            showRing = false, showDownload = false, showNewBuild = true
                        )
                    }
                }
            }
    }

    private fun detachListener() { buildListener?.remove(); buildListener = null }

    private fun updateBuildStatus(status: String, sizeBytes: Long) {
        val user    = auth?.currentUser ?: return
        val db      = firestore ?: return
        val buildId = currentBuildId ?: return
        val update  = mutableMapOf<String, Any>("status" to status, "updatedAt" to FieldValue.serverTimestamp())
        if (sizeBytes > 0) update["sizeBytes"] = sizeBytes
        db.collection("users").document(user.uid)
            .collection("builds").document(buildId)
            .update(update)
            .addOnFailureListener { e -> Log.w(TAG, "Status update failed: ${e.message}") }
    }

    // =========================================================================
    //  Building screen helpers
    // =========================================================================

    private fun updateBuildScreen(
        title: String, detail: String,
        showRing: Boolean, showDownload: Boolean, showNewBuild: Boolean
    ) {
        val b = stepBuildingBinding ?: return
        b.buildStatusTitle.text  = title
        b.buildStatusText.text   = detail
        b.buildProgressRing.visibility = if (showRing) View.VISIBLE else View.GONE
        b.buildCenterIcon.visibility   = if (showRing) View.VISIBLE else View.GONE
        b.buildResultIcon.visibility   = if (!showRing && showDownload) View.VISIBLE else View.GONE
        b.downloadButton.visibility    = if (showDownload) View.VISIBLE else View.GONE
        b.newBuildButton.visibility    = if (showNewBuild) View.VISIBLE else View.GONE

        // Pulse animation for step 3 icon
        if (!showRing && showDownload) {
            b.stepIcon3.alpha = 1f
            b.stepIcon3.animate().scaleX(1.2f).scaleY(1.2f).setDuration(300)
                .withEndAction { b.stepIcon3.animate().scaleX(1f).scaleY(1f).setDuration(200).start() }.start()
        }
    }

    private fun startPulseAnimation() {
        val b = stepBuildingBinding ?: return
        pulseAnimSet?.cancel()

        // Pulse the outer ring: fade in and scale up repeatedly
        val outerAlpha = ObjectAnimator.ofFloat(b.buildPulseOuter, "alpha", 0f, 0.4f, 0f).apply {
            duration     = 1600
            repeatCount  = ValueAnimator.INFINITE
            repeatMode   = ValueAnimator.RESTART
            interpolator = AccelerateDecelerateInterpolator()
        }
        val outerScaleX = ObjectAnimator.ofFloat(b.buildPulseOuter, "scaleX", 0.85f, 1.1f).apply {
            duration    = 1600
            repeatCount = ValueAnimator.INFINITE
            repeatMode  = ValueAnimator.RESTART
            interpolator = AccelerateDecelerateInterpolator()
        }
        val outerScaleY = ObjectAnimator.ofFloat(b.buildPulseOuter, "scaleY", 0.85f, 1.1f).apply {
            duration    = 1600
            repeatCount = ValueAnimator.INFINITE
            repeatMode  = ValueAnimator.RESTART
            interpolator = AccelerateDecelerateInterpolator()
        }
        // Gentle rotation on the center icon
        val iconRotate = ObjectAnimator.ofFloat(b.buildCenterIcon, "rotation", 0f, 360f).apply {
            duration    = 3000
            repeatCount = ValueAnimator.INFINITE
            repeatMode  = ValueAnimator.RESTART
            interpolator = AccelerateDecelerateInterpolator()
        }
        pulseAnimSet = AnimatorSet().also {
            it.playTogether(outerAlpha, outerScaleX, outerScaleY, iconRotate)
            it.start()
        }
    }

    private fun stopPulseAnimation() {
        pulseAnimSet?.cancel()
        pulseAnimSet = null
        stepBuildingBinding?.buildCenterIcon?.rotation = 0f
    }

    // =========================================================================
    //  Download
    // =========================================================================

    private fun startDownload() {
        val downloadUrl = currentDownloadUrl ?: return
        val uri     = Uri.parse(downloadUrl)
        val manager = requireContext().getSystemService(DownloadManager::class.java)
        if (manager == null) { openInBrowser(uri); return }
        val fileName = uri.lastPathSegment ?: "app.apk"
        val request  = DownloadManager.Request(uri)
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
        try { startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, uri)) }
        catch (e: Exception) { toast(R.string.error_generic) }
    }

    // =========================================================================
    //  Reset
    // =========================================================================

    private fun resetWizard() {
        detachListener()
        buildInProgress    = false
        currentBuildId     = null
        currentDownloadUrl = null
        stopPulseAnimation()
        // Return to step 1 with animation
        binding.wizardPager.setCurrentItem(STEP_WEBSITE, true)
    }

    // =========================================================================
    //  UI helpers
    // =========================================================================

    private fun setBuilding(building: Boolean) {
        buildInProgress = building
        if (_binding == null) return
        stepPermissionsBinding?.buildButton?.isEnabled = !building
        featureSwitches.values.forEach   { it.isEnabled = !building }
        permissionSwitches.values.forEach { it.isEnabled = !building }
        stepWebsiteBinding?.urlInput?.isEnabled     = !building
        stepWebsiteBinding?.appNameInput?.isEnabled = !building
        stepWebsiteBinding?.packageInput?.isEnabled = !building
    }

    private fun failBuild(message: String) {
        setBuilding(false)
        stopPulseAnimation()
        updateBuildScreen(
            title = "Build failed",
            detail = message,
            showRing = false, showDownload = false, showNewBuild = true
        )
        if (isAdded) Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
    }

    private fun toast(resId: Int) {
        if (isAdded) Toast.makeText(requireContext(), resId, Toast.LENGTH_SHORT).show()
    }

    private fun isSafe(buildId: String) = isAdded && _binding != null && buildId == currentBuildId

    private fun isValidHttpUrl(raw: String): Boolean {
        return try {
            val uri    = Uri.parse(raw)
            val scheme = uri.scheme?.lowercase()
            (scheme == "http" || scheme == "https") && !uri.host.isNullOrBlank()
        } catch (e: Exception) { false }
    }

    private fun isOffline(): Boolean {
        val manager = requireContext().getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val network = manager.activeNetwork ?: return true
        val caps    = manager.getNetworkCapabilities(network) ?: return true
        return !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun newBuildId(): String {
        val chars = "abcdefghijklmnopqrstuvwxyz0123456789"
        val suffix = buildString { repeat(5) { append(chars.random()) } }
        return "app_${System.currentTimeMillis()}_$suffix"
    }

    private fun dpToPx(dp: Int) = (dp * resources.displayMetrics.density).toInt()

    override fun onDestroyView() {
        stopPulseAnimation()
        detachListener()
        stepWebsiteBinding    = null
        stepFeaturesBinding   = null
        stepPermissionsBinding = null
        stepBuildingBinding   = null
        _binding = null
        super.onDestroyView()
    }

    private companion object {
        private const val TAG = "HomeFragment"
    }
}
