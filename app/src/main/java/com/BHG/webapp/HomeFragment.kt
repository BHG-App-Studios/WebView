package com.BHG.webapp

import android.animation.ValueAnimator
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.BHG.webapp.databinding.FragmentHomeBinding
import com.BHG.webapp.databinding.StepFeaturesBinding
import com.BHG.webapp.databinding.StepPermissionsBinding
import com.BHG.webapp.databinding.StepWebsiteBinding
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import org.json.JSONObject

/**
 * The builder wizard — 3 steps on the Home tab.
 *
 * Step 0: Website URL, App name, Package name
 * Step 1: Feature toggles
 * Step 2: Permission toggles + Build button
 *
 * On Build: registers in Firestore, dispatches to the Worker, then navigates
 * to the Build tab (nav_home) where [BuildFragment] shows live progress.
 */
class HomeFragment : Fragment() {

    // ── Root binding ──────────────────────────────────────────────────────────
    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    // ── Step bindings ─────────────────────────────────────────────────────────
    private var stepWebsiteBinding: StepWebsiteBinding? = null
    private var stepFeaturesBinding: StepFeaturesBinding? = null
    private var stepPermissionsBinding: StepPermissionsBinding? = null

    // ── Firebase ──────────────────────────────────────────────────────────────
    private val auth: FirebaseAuth? by lazy { runCatching { FirebaseAuth.getInstance() }.getOrNull() }
    private val firestore: FirebaseFirestore? by lazy { runCatching { FirebaseFirestore.getInstance() }.getOrNull() }

    // ── Build state ───────────────────────────────────────────────────────────
    private var buildDispatching = false

    // ── Toggle data ───────────────────────────────────────────────────────────
    private val featureSwitches    = LinkedHashMap<String, MaterialSwitch>()
    private val permissionSwitches = LinkedHashMap<String, MaterialSwitch>()

    private data class FeatureOption(val key: String, val labelRes: Int, val default: Boolean)
    private data class PermissionOption(
        val key: String, val labelRes: Int,
        val removeKeywords: List<String>, val defaultOn: Boolean
    )

    private val featureOptions = listOf(
        FeatureOption("progress_bar",      R.string.opt_progress_bar,      true),
        FeatureOption("circular_progress", R.string.opt_circular_progress, false),
        FeatureOption("swipe_refresh",     R.string.opt_swipe_refresh,     true),
        FeatureOption("file_upload",       R.string.opt_file_upload,       true),
        FeatureOption("multiple_files",    R.string.opt_multiple_files,    true),
        FeatureOption("downloads",         R.string.opt_downloads,         true),
        FeatureOption("external_links",    R.string.opt_external_links,    true),
        FeatureOption("offline_page",      R.string.opt_offline_page,      true),
        FeatureOption("fullscreen_video",  R.string.opt_fullscreen_video,  true)
    )

    private val permissionOptions = listOf(
        PermissionOption("camera",        R.string.perm_camera,        listOf("CAMERA"),                            true),
        PermissionOption("microphone",    R.string.perm_microphone,    listOf("MICROPHONE","MODIFY_AUDIO_SETTINGS"), true),
        PermissionOption("location",      R.string.perm_location,      listOf("LOCATION"),                          true),
        PermissionOption("vibrate",       R.string.perm_vibrate,       listOf("VIBRATE"),                           true),
        PermissionOption("notifications", R.string.perm_notifications, listOf("POST_NOTIFICATIONS"),                true)
    )

    // ── Step constants ────────────────────────────────────────────────────────
    private val STEP_WEBSITE     = 0
    private val STEP_FEATURES    = 1
    private val STEP_PERMISSIONS = 2
    private val TOTAL_STEPS      = 3

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
        setupBackPressHandler()
    }

    private fun setupBackPressHandler() {
        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    val current = binding.wizardPager.currentItem
                    if (current > STEP_WEBSITE) {
                        // Navigate to previous step
                        goBack()
                    } else {
                        // On step 1: let MainActivity handle (double-back-to-exit)
                        isEnabled = false
                        requireActivity().onBackPressedDispatcher.onBackPressed()
                        isEnabled = true
                    }
                }
            }
        )
    }

    // =========================================================================
    //  Wizard setup
    // =========================================================================

    private fun setupWizard() {
        val adapter = WizardPagerAdapter()
        binding.wizardPager.adapter = adapter
        binding.wizardPager.isUserInputEnabled = false
        binding.wizardPager.offscreenPageLimit  = 2

        // Depth zoom page transformer for a premium slide+scale effect
        binding.wizardPager.setPageTransformer { page, position ->
            val abs = Math.abs(position)
            page.alpha       = 1f - abs * 0.4f
            page.translationX = -position * page.width * 0.1f
            val scale = 1f - abs * 0.08f
            page.scaleX = scale; page.scaleY = scale
        }

        binding.wizardPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                updateTopBarForStep(position)
                updateNextButtonForStep(position)
            }
        })
    }

    private fun setupTopBar() {
        binding.topBarMenu.setOnClickListener { (activity as? MainActivity)?.openDrawer() }
        binding.topBarBack.setOnClickListener { goBack() }
        // onPageSelected doesn't fire for position 0, so set the initial bar state
        // explicitly — otherwise the back button keeps its XML visibility and the
        // title starts indented on first render.
        updateTopBarForStep(binding.wizardPager.currentItem)
    }

    private fun setupNextButton() {
        binding.btnNext.setOnClickListener {
            when (binding.wizardPager.currentItem) {
                STEP_WEBSITE  -> onNextFromWebsite()
                STEP_FEATURES -> goToStep(STEP_PERMISSIONS)
            }
        }
    }

    // =========================================================================
    //  Inner adapter — 3 steps only
    // =========================================================================

    inner class WizardPagerAdapter : RecyclerView.Adapter<WizardPagerAdapter.StepVH>() {
        inner class StepVH(root: View) : RecyclerView.ViewHolder(root)

        override fun getItemCount()              = TOTAL_STEPS
        override fun getItemViewType(position: Int) = position

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): StepVH {
            val inf = LayoutInflater.from(parent.context)
            return when (viewType) {
                STEP_WEBSITE -> {
                    val b = StepWebsiteBinding.inflate(inf, parent, false)
                    stepWebsiteBinding = b
                    StepVH(b.root)
                }
                STEP_FEATURES -> {
                    val b = StepFeaturesBinding.inflate(inf, parent, false)
                    stepFeaturesBinding = b
                    buildFeatureToggles(b)
                    StepVH(b.root)
                }
                STEP_PERMISSIONS -> {
                    val b = StepPermissionsBinding.inflate(inf, parent, false)
                    stepPermissionsBinding = b
                    buildPermissionToggles(b)
                    b.buildButton.setOnClickListener { onBuildClicked() }
                    StepVH(b.root)
                }
                else -> StepVH(View(parent.context))
            }
        }

        override fun onBindViewHolder(holder: StepVH, position: Int) { /* static */ }
    }

    // =========================================================================
    //  Toggles
    // =========================================================================

    private fun buildFeatureToggles(b: StepFeaturesBinding) {
        for (opt in featureOptions) {
            val sw = makeSwitch(getString(opt.labelRes), opt.default)
            featureSwitches[opt.key] = sw
            b.featureContainer.addView(sw)
        }
    }

    private fun buildPermissionToggles(b: StepPermissionsBinding) {
        for (opt in permissionOptions) {
            val sw = makeSwitch(getString(opt.labelRes), opt.defaultOn)
            permissionSwitches[opt.key] = sw
            b.permissionContainer.addView(sw)
        }
    }

    private fun makeSwitch(label: String, checked: Boolean) = MaterialSwitch(requireContext()).apply {
        text = label; isChecked = checked; textSize = 15f
        val padV = (14 * resources.displayMetrics.density).toInt()
        setPadding(0, padV, 0, padV)
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    // =========================================================================
    //  Step navigation
    // =========================================================================

    private fun goToStep(step: Int) = binding.wizardPager.setCurrentItem(step, true)

    private fun goBack() {
        val cur = binding.wizardPager.currentItem
        if (cur > STEP_WEBSITE) binding.wizardPager.setCurrentItem(cur - 1, true)
    }

    private fun onNextFromWebsite() {
        val url = stepWebsiteBinding?.urlInput?.text?.toString()?.trim().orEmpty()
        stepWebsiteBinding?.urlLayout?.error = null
        if (url.isEmpty()) { stepWebsiteBinding?.urlLayout?.error = getString(R.string.error_url_required); return }
        if (!isValidHttpUrl(url)) { stepWebsiteBinding?.urlLayout?.error = getString(R.string.error_url_invalid); return }
        goToStep(STEP_FEATURES)
    }

    // =========================================================================
    //  Step updates
    // =========================================================================


    private fun updateTopBarForStep(step: Int) {
        // GONE (not INVISIBLE) on step 1 so the hidden back button reserves no space —
        // the hamburger then sits at the same start position as the other fragments'
        // top bars, and the title lines up identically.
        binding.topBarBack.visibility = if (step > STEP_WEBSITE) View.VISIBLE else View.GONE
        binding.topBarMenu.visibility = if (step == STEP_WEBSITE) View.VISIBLE else View.GONE
        binding.topBarTitle.text = listOf("Build App", "App Features", "Permissions").getOrNull(step) ?: "Build App"
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
    //  Build flow — register & dispatch, then hand off to Build tab
    // =========================================================================

    private fun onBuildClicked() {
        if (buildDispatching) return
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
        buildDispatching = true
        stepPermissionsBinding?.buildButton?.isEnabled = false

        val buildId     = newBuildId()
        val downloadUrl = "${BuildApi.BASE_URL}/download/$buildId.apk"
        val request     = buildRequestJson(url, appName, packageName, buildId)

        registerAndDispatch(user, buildId, url, appName, packageName, downloadUrl, request)
    }

    private fun registerAndDispatch(
        user: FirebaseUser, buildId: String, url: String,
        appName: String, packageName: String, downloadUrl: String, request: JSONObject
    ) {
        val db = firestore ?: run { resetBuildButton(); toast(R.string.error_generic); return }

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
                if (!isAdded) return@addOnSuccessListener
                // Navigate to Build tab — BuildFragment will pick up the live build
                (activity as? MainActivity)?.goToTab(R.id.nav_home)
                dispatchBuild(user, buildId, request)
                resetWizard()
            }
            .addOnFailureListener { e ->
                if (!isAdded) return@addOnFailureListener
                Log.w(TAG, "Build register failed: ${e.message}")
                resetBuildButton()
                toast(R.string.error_generic)
            }
    }

    private fun dispatchBuild(user: FirebaseUser, buildId: String, request: JSONObject) {
        user.getIdToken(false)
            .addOnSuccessListener { result ->
                val token = result.token ?: return@addOnSuccessListener
                BuildApi.build(
                    request  = request,
                    idToken  = token,
                    onSuccess = { Log.d(TAG, "Build dispatched: $buildId") },
                    onError   = { msg -> Log.w(TAG, "Dispatch error: $msg") }
                )
            }
            .addOnFailureListener { e -> Log.w(TAG, "Token fetch failed: ${e.message}") }
    }

    private fun buildRequestJson(url: String, appName: String, packageName: String, buildId: String): JSONObject {
        val json = JSONObject().apply {
            put("url", url); put("build_id", buildId)
            if (appName.isNotEmpty())     put("app_name",     appName)
            if (packageName.isNotEmpty()) put("package_name", packageName)
        }
        for (opt in featureOptions) json.put(opt.key, featureSwitches[opt.key]?.isChecked ?: opt.default)
        val toRemove = LinkedHashSet<String>()
        for (opt in permissionOptions) if (permissionSwitches[opt.key]?.isChecked == false) toRemove.addAll(opt.removeKeywords)
        if (toRemove.isNotEmpty()) json.put("remove_permissions", BuildApi.jsonArrayOf(toRemove))
        return json
    }

    // =========================================================================
    //  Helpers
    // =========================================================================

    /** Reset wizard to step 1 so user can start a new build. */
    private fun resetWizard() {
        buildDispatching = false
        stepPermissionsBinding?.buildButton?.isEnabled = true
        binding.wizardPager.setCurrentItem(STEP_WEBSITE, false)
        stepWebsiteBinding?.urlInput?.text?.clear()
        stepWebsiteBinding?.appNameInput?.text?.clear()
        stepWebsiteBinding?.packageInput?.text?.clear()
    }

    private fun resetBuildButton() {
        buildDispatching = false
        stepPermissionsBinding?.buildButton?.isEnabled = true
    }

    private fun toast(resId: Int) { if (isAdded) Toast.makeText(requireContext(), resId, Toast.LENGTH_SHORT).show() }

    private fun isValidHttpUrl(raw: String) = try {
        val uri = Uri.parse(raw); val s = uri.scheme?.lowercase()
        (s == "http" || s == "https") && !uri.host.isNullOrBlank()
    } catch (e: Exception) { false }

    private fun isOffline(): Boolean {
        val mgr  = requireContext().getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val net  = mgr.activeNetwork ?: return true
        val caps = mgr.getNetworkCapabilities(net) ?: return true
        return !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun newBuildId(): String {
        val chars = "abcdefghijklmnopqrstuvwxyz0123456789"
        return "app_${System.currentTimeMillis()}_${buildString { repeat(5) { append(chars.random()) } }}"
    }

    private fun dpToPx(dp: Int) = (dp * resources.displayMetrics.density).toInt()

    override fun onDestroyView() {
        stepWebsiteBinding     = null
        stepFeaturesBinding    = null
        stepPermissionsBinding = null
        _binding = null
        super.onDestroyView()
    }

    private companion object { private const val TAG = "HomeFragment" }
}
