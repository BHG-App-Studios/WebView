package com.BHG.webapp

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.BHG.webapp.databinding.FragmentHomeBinding
import com.BHG.webapp.databinding.StepEntryBinding
import com.BHG.webapp.databinding.StepFeaturesBinding
import com.BHG.webapp.databinding.StepPermissionsBinding
import com.BHG.webapp.databinding.StepSigningBinding
import com.BHG.webapp.databinding.StepWebsiteBinding
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * The builder wizard — 4 steps on the Home tab.
 *
 * Step 1: App name + package name
 * Step 2: Feature toggles
 * Step 3: Permission toggles
 * Step 4: Build type / output / signing + Build button
 *
 * On Build: registers in Firestore, dispatches to the Worker, then navigates
 * to the Build tab (nav_home) where [BuildFragment] shows live progress.
 */
class HomeFragment : Fragment() {

    // ── Root binding ──────────────────────────────────────────────────────────
    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    // ── Step bindings ─────────────────────────────────────────────────────────
    private var stepEntryBinding: StepEntryBinding? = null
    private var stepWebsiteBinding: StepWebsiteBinding? = null
    private var stepFeaturesBinding: StepFeaturesBinding? = null
    private var stepPermissionsBinding: StepPermissionsBinding? = null
    private var stepSigningBinding: StepSigningBinding? = null

    private var buttonAnimators: List<ValueAnimator>? = null

    /** URL confirmed reachable on the entry screen; reused by the build request. */
    private var confirmedUrl: String = ""
    private var pinging = false

    /** Pending "update this app" prefill (url/package/version), applied on Step 1. */
    private var updatePrefill: BuildPrefill? = null

    // ── Firebase ──────────────────────────────────────────────────────────────
    private val auth: FirebaseAuth? by lazy { runCatching { FirebaseAuth.getInstance() }.getOrNull() }
    private val firestore: FirebaseFirestore? by lazy { runCatching { FirebaseFirestore.getInstance() }.getOrNull() }

    // ── Build state ───────────────────────────────────────────────────────────
    private var buildDispatching = false

    // ── Signing state ─────────────────────────────────────────────────────────
    // buildType: "debug" | "release";  signingMode: "auto" | "custom"
    private var buildType = "debug"
    private var signingMode = "auto"
    // "apk" | "aab" | "both" — only meaningful for release builds.
    private var outputFormat = "apk"
    /** Base64 of the user-picked custom keystore, and its display name. */
    private var customKeystoreB64: String? = null
    private var customKeystoreName: String? = null
    // True only when the current keystore was generated on-device (KeystoreCreateSheet),
    // so the build can ask the Worker to persist it to {uid}/keystores/{package}.
    private var customKeystoreGenerated = false

    // Custom app logo built on-device by LogoCreateFragment and persisted by
    // LogoStore under the confirmed URL, so entering the same site again — even
    // after a restart — brings its logo back. The ZIP's bytes are base64'd into
    // the build request at dispatch time so the runner can drop them into the
    // project.
    private var logoZipPath: String? = null
    private var logoPreviewPath: String? = null

    /** The URL the app name + package fields were last auto-derived from. */
    private var namesDerivedForUrl: String? = null

    /** SAF picker for the custom keystore file. */
    private lateinit var keystorePicker: ActivityResultLauncher<Array<String>>

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

    // ── Page constants ──────────────────────────────────────────────────────
    // Page 0 is the uncounted entry screen (URL + Start Building). The three
    // numbered steps follow it.
    private val PAGE_ENTRY       = 0
    private val STEP_WEBSITE     = 1  // Step 1: app name + package
    private val STEP_FEATURES    = 2  // Step 2: features
    private val STEP_PERMISSIONS = 3  // Step 3: permissions
    private val STEP_SIGNING     = 4  // Step 4: build type / signing + build
    private val TOTAL_STEPS      = 5

    // =========================================================================
    //  Lifecycle
    // =========================================================================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Register the keystore file picker here (before STARTED, as required by
        // the Activity Result API). Accepts any file; we validate the bytes on read.
        keystorePicker = registerForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { uri -> if (uri != null) onKeystorePicked(uri) }

        // Receive keystores generated on-device by the create-keystore sheet.
        parentFragmentManager.setFragmentResultListener(
            KeystoreCreateSheet.RESULT_KEY, this
        ) { _, bundle -> onKeystoreGenerated(bundle) }

        // Receive the app logo built on-device by the logo creator.
        parentFragmentManager.setFragmentResultListener(
            LogoCreateFragment.RESULT_KEY, this
        ) { _, bundle -> onLogoResult(bundle) }
    }

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

    override fun onResume() {
        super.onResume()
        // A "My Apps → Update" tap hands us a prefill via MainActivity. Consume it
        // once: drop the app's URL into the entry field and start the wizard so
        // Step 1 can override the package/version to match the app being updated.
        val activity = activity as? MainActivity ?: return
        val prefill = activity.pendingUpdatePrefill ?: return
        activity.pendingUpdatePrefill = null
        updatePrefill = prefill
        binding.wizardPager.post {
            if (_binding == null) return@post
            binding.wizardPager.setCurrentItem(PAGE_ENTRY, false)
            stepEntryBinding?.entryUrlInput?.setText(prefill.url)
            onStartBuilding()
        }
    }

    private fun setupBackPressHandler() {
        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    val current = binding.wizardPager.currentItem
                    if (current > PAGE_ENTRY) {
                        // Step back toward the entry screen (nav bar reappears there).
                        goBack()
                    } else {
                        // On the entry screen: let MainActivity handle it.
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
        // Keep every page inflated so all step bindings are ready when Build fires.
        binding.wizardPager.offscreenPageLimit  = 4



        binding.wizardPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                updateTopBarForStep(position)
                updateNextButtonForStep(position)
                updateNavForStep(position)
            }
        })
    }

    private fun setupTopBar() {
        binding.topBarMenu.setOnClickListener { (activity as? MainActivity)?.openDrawer() }
        binding.topBarBack.setOnClickListener { goBack() }

        val currentMode = ThemePrefs.getMode(requireContext())
        updateThemeToggleIcon(currentMode)
        
        binding.topBarThemeToggle.setOnClickListener {
            val mode = ThemePrefs.getMode(requireContext())
            val newMode = if (mode == ThemePrefs.DARK) ThemePrefs.LIGHT else ThemePrefs.DARK
            ThemePrefs.setMode(requireContext(), newMode)
            updateThemeToggleIcon(newMode)
        }

        // onPageSelected doesn't fire for position 0, so set the initial bar state
        // explicitly — otherwise the back button keeps its XML visibility and the
        // title starts indented on first render.
        updateTopBarForStep(binding.wizardPager.currentItem)
    }

    private fun updateThemeToggleIcon(mode: String) {
        val isDark = mode == ThemePrefs.DARK || (mode == ThemePrefs.SYSTEM && 
            (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES)
        binding.topBarThemeToggle.setImageResource(if (isDark) R.drawable.ic_light_mode else R.drawable.ic_dark_mode)
    }

    private fun setupNextButton() {
        binding.btnNext.setOnClickListener {
            when (binding.wizardPager.currentItem) {
                STEP_WEBSITE     -> onNextFromWebsite()
                STEP_FEATURES    -> goToStep(STEP_PERMISSIONS)
                STEP_PERMISSIONS -> goToStep(STEP_SIGNING)
            }
        }
    }

    // =========================================================================
    //  Inner adapter — entry screen + 4 steps
    // =========================================================================

    inner class WizardPagerAdapter : RecyclerView.Adapter<WizardPagerAdapter.StepVH>() {
        inner class StepVH(root: View) : RecyclerView.ViewHolder(root)

        override fun getItemCount()              = TOTAL_STEPS
        override fun getItemViewType(position: Int) = position

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): StepVH {
            val inf = LayoutInflater.from(parent.context)
            return when (viewType) {
                PAGE_ENTRY -> {
                    val b = StepEntryBinding.inflate(inf, parent, false)
                    stepEntryBinding = b
                    b.startBuildingButton.setOnClickListener { onStartBuilding() }
                    b.entryUrlInput.setOnEditorActionListener { _, _, _ -> onStartBuilding(); true }
                    
                    b.pasteButton.setOnClickListener {
                        val clipboard = requireContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        if (clipboard.hasPrimaryClip()) {
                            val text = clipboard.primaryClip?.getItemAt(0)?.text
                            if (!text.isNullOrEmpty()) {
                                b.entryUrlInput.setText(text)
                                b.entryUrlInput.setSelection(text.length)
                            }
                        }
                    }

                    setupButtonAnimations()
                    StepVH(b.root)
                }
                STEP_WEBSITE -> {
                    val b = StepWebsiteBinding.inflate(inf, parent, false)
                    stepWebsiteBinding = b
                    // If the URL was already confirmed before this page existed,
                    // fill in the derived app name + package — and the logo saved
                    // for that site — now.
                    if (confirmedUrl.isNotEmpty()) {
                        prefillNamesFromUrl()
                        restoreLogoForUrl()
                    }
                    b.logoCreateButton.setOnClickListener { openLogoCreator() }
                    updateLogoCard(b)
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
                    StepVH(b.root)
                }
                STEP_SIGNING -> {
                    val b = StepSigningBinding.inflate(inf, parent, false)
                    stepSigningBinding = b
                    setupSigningControls(b)
                    b.buildButton.isEnabled = b.ownershipCheckbox.isChecked
                    b.ownershipCheckbox.setOnCheckedChangeListener { _, isChecked ->
                        b.buildButton.isEnabled = isChecked
                    }
                    b.ownershipLayout.setOnClickListener {
                        b.ownershipCheckbox.isChecked = !b.ownershipCheckbox.isChecked
                    }
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

    // =========================================================================
    //  Signing controls (build type / output / keystore)
    // =========================================================================

    private fun setupSigningControls(b: StepSigningBinding) {
        // Defaults: debug + auto + apk.
        b.buildTypeToggle.check(b.buildTypeDebug.id)
        b.signingToggle.check(b.signingAuto.id)
        b.outputToggle.check(b.outputApk.id)

        b.buildTypeToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            buildType = if (checkedId == b.buildTypeRelease.id) "release" else "debug"
            // Output format only applies to release builds.
            b.outputFormatSection.visibility =
                if (buildType == "release") View.VISIBLE else View.GONE
            b.buildTypeHelper.setText(
                if (buildType == "release") R.string.build_type_release_helper
                else R.string.build_type_debug_helper
            )
            applySigningLockForBuildType(b)
        }

        b.outputToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            outputFormat = when (checkedId) {
                b.outputAab.id  -> "aab"
                b.outputBoth.id -> "both"
                else            -> "apk"
            }
        }

        b.signingToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            signingMode = if (checkedId == b.signingCustom.id) "custom" else "auto"
            val custom = signingMode == "custom"
            b.customKeystoreSection.visibility = if (custom) View.VISIBLE else View.GONE
            b.signingHelper.setText(
                if (custom) R.string.signing_custom_helper else R.string.signing_auto_helper
            )
        }

        b.pickKeystoreButton.setOnClickListener {
            // Common keystore MIME types are unreliable; accept everything and
            // validate on read.
            keystorePicker.launch(arrayOf("*/*"))
        }

        b.createKeystoreButton.setOnClickListener {
            KeystoreCreateSheet().show(parentFragmentManager, KeystoreCreateSheet.TAG)
        }

        // Debug is the initial build type, so lock signing to auto up front.
        applySigningLockForBuildType(b)
    }

    /**
     * Debug builds are always auto-signed with a throwaway test key, so the
     * signing choice is locked to Auto-generate and the toggle is disabled.
     * Release builds unlock the toggle so the user can pick their own keystore.
     */
    private fun applySigningLockForBuildType(b: StepSigningBinding) {
        val debug = buildType == "debug"
        if (debug) {
            // Force Auto and clear any custom keystore the user may have picked
            // before switching back to Debug.
            if (signingMode != "auto") clearKeystoreSelection()
            signingMode = "auto"
            b.signingToggle.check(b.signingAuto.id)
            b.customKeystoreSection.visibility = View.GONE
            b.signingHelper.setText(R.string.signing_debug_locked_helper)
        } else {
            val custom = signingMode == "custom"
            b.customKeystoreSection.visibility = if (custom) View.VISIBLE else View.GONE
            b.signingHelper.setText(
                if (custom) R.string.signing_custom_helper else R.string.signing_auto_helper
            )
        }
        // A disabled toggle keeps Auto selected but stops the user from changing it;
        // dim the whole group so the locked state reads clearly on Debug.
        b.signingAuto.isEnabled = !debug
        b.signingCustom.isEnabled = !debug
        b.signingToggle.alpha = if (debug) 0.5f else 1f
    }

    /** Applies a keystore generated on-device by [KeystoreCreateSheet]. */
    private fun onKeystoreGenerated(bundle: Bundle) {
        val b = stepSigningBinding ?: return
        val b64 = bundle.getString(KeystoreCreateSheet.ARG_B64).orEmpty()
        if (b64.isEmpty()) return
        val alias = bundle.getString(KeystoreCreateSheet.ARG_ALIAS).orEmpty()
        val storePw = bundle.getString(KeystoreCreateSheet.ARG_STORE_PW).orEmpty()
        val keyPw = bundle.getString(KeystoreCreateSheet.ARG_KEY_PW).orEmpty()

        customKeystoreB64 = b64
        customKeystoreName = "$alias.p12"
        customKeystoreGenerated = true
        // Fill the credential fields so the existing validation + dispatch path
        // sends the passwords that match the generated keystore.
        b.storePwInput.setText(storePw)
        b.keyAliasInput.setText(alias)
        b.keyPwInput.setText(keyPw)
        b.keystoreFileName.visibility = View.VISIBLE
        b.keystoreFileName.text = getString(R.string.keystore_generated, customKeystoreName)
        toast(R.string.keystore_create_ok)
    }

    /** Reads the picked keystore into base64 and shows its name. */
    private fun onKeystorePicked(uri: Uri) {
        val b = stepSigningBinding ?: return
        try {
            val bytes = requireContext().contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: throw IllegalStateException("empty stream")
            if (bytes.isEmpty()) throw IllegalStateException("empty file")
            customKeystoreB64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
            customKeystoreName = queryDisplayName(uri) ?: "keystore"
            customKeystoreGenerated = false
            b.keystoreFileName.visibility = View.VISIBLE
            b.keystoreFileName.text = getString(R.string.keystore_selected, customKeystoreName)
        } catch (e: Exception) {
            Log.w(TAG, "keystore read failed: ${e.message}")
            customKeystoreB64 = null
            customKeystoreName = null
            customKeystoreGenerated = false
            toast(R.string.keystore_read_failed)
        }
    }

    /** Clears the selected custom keystore and its on-screen file name. */
    private fun clearKeystoreSelection() {
        customKeystoreB64 = null
        customKeystoreName = null
        customKeystoreGenerated = false
        stepSigningBinding?.keystoreFileName?.let {
            it.text = ""
            it.visibility = View.GONE
        }
    }

    // =========================================================================
    //  App logo (custom launcher icon)
    // =========================================================================

    private fun openLogoCreator() {
        LogoCreateFragment.newInstance(editing = logoZipPath != null, url = confirmedUrl)
            .show(parentFragmentManager, LogoCreateFragment.TAG)
    }

    private fun onLogoResult(bundle: Bundle) {
        if (bundle.getBoolean(LogoCreateFragment.ARG_REMOVED, false)) {
            clearLogoSelection()
        } else {
            // Paths into LogoStore, so the logo this build uses is the same one
            // that comes back for this URL on the next launch.
            logoZipPath = bundle.getString(LogoCreateFragment.ARG_ZIP_PATH)
            logoPreviewPath = bundle.getString(LogoCreateFragment.ARG_PREVIEW_PATH)
        }
        stepWebsiteBinding?.let { updateLogoCard(it) }
    }

    /**
     * Adopts the logo stored for [confirmedUrl]. A logo belongs to the site it
     * was designed for, so a different URL starts with none rather than
     * inheriting the previous one. Called when a URL is confirmed and when
     * Step 1 is (re)created, so the card always matches the confirmed site.
     */
    private fun restoreLogoForUrl() {
        val saved = LogoStore.committed(requireContext(), confirmedUrl)
        logoZipPath = saved?.zipPath
        logoPreviewPath = saved?.previewPath
    }

    /** Clears the in-memory logo selection; what LogoStore holds is left alone. */
    private fun clearLogoSelection() {
        logoZipPath = null
        logoPreviewPath = null
    }

    /**
     * Reflects whether a custom logo is set on the Step 1 card. The stored ZIP is
     * what a build actually consumes, so it drives the card's state; the preview
     * is decoration and may fail to decode without invalidating the logo.
     */
    private fun updateLogoCard(b: StepWebsiteBinding) {
        val hasLogo = logoZipPath?.let { File(it).isFile } == true
        val preview = if (hasLogo) {
            logoPreviewPath?.let { runCatching { BitmapFactory.decodeFile(it) }.getOrNull() }
        } else null

        if (preview != null) {
            b.logoPreview.setImageBitmap(preview)
            b.logoPreview.visibility = View.VISIBLE
            b.logoIcon.visibility = View.GONE
            b.logoIconBg.visibility = View.GONE
        } else {
            b.logoPreview.setImageDrawable(null)
            b.logoPreview.visibility = View.GONE
            b.logoIcon.visibility = View.VISIBLE
            b.logoIconBg.visibility = View.VISIBLE
        }

        if (hasLogo) {
            b.logoSubtitle.setText(R.string.logo_added)
            b.logoCreateButton.setText(R.string.logo_edit)
        } else {
            b.logoSubtitle.setText(R.string.logo_card_subtitle)
            b.logoCreateButton.setText(R.string.logo_create)
        }
    }

    // =========================================================================
    //  App name / package auto-derivation (client-side preview of what the
    //  server would generate, so the details page is never blank). The Worker
    //  re-derives from the same rules when a field is sent empty, so anything
    //  the user leaves blank is still handled server-side.
    // =========================================================================

    /**
     * Fills the app-name and package fields from [confirmedUrl]. A field is
     * (re)filled when it is empty, or when the confirmed URL changed since the
     * last derivation — so user edits survive navigating back and forth, but a
     * new URL produces fresh suggestions.
     */
    private fun prefillNamesFromUrl() {
        val b = stepWebsiteBinding ?: return
        if (confirmedUrl.isEmpty()) return
        val urlChanged = namesDerivedForUrl != confirmedUrl
        if (urlChanged || b.appNameInput.text.isNullOrBlank()) {
            b.appNameInput.setText(deriveAppName(confirmedUrl))
        }
        if (urlChanged || b.packageInput.text.isNullOrBlank()) {
            b.packageInput.setText(derivePackageName(confirmedUrl))
        }
        // Sensible version defaults for a first build; the user can change them.
        if (b.versionNameInput.text.isNullOrBlank()) b.versionNameInput.setText(DEFAULT_VERSION_NAME)
        if (b.versionCodeInput.text.isNullOrBlank()) b.versionCodeInput.setText(DEFAULT_VERSION_CODE.toString())
        // Updating an existing app: keep its package and carry the bumped version.
        updatePrefill?.let { p ->
            if (p.packageName.isNotEmpty()) b.packageInput.setText(p.packageName)
            b.versionNameInput.setText(p.versionName)
            b.versionCodeInput.setText(p.versionCode.toString())
            updatePrefill = null
            toast(R.string.update_prefilled)
        }
        namesDerivedForUrl = confirmedUrl
    }

    private fun hostOf(url: String): String? =
        runCatching { Uri.parse(url).host?.lowercase() }.getOrNull()?.takeIf { it.isNotEmpty() }

    /** First meaningful host label (skips "www"), e.g. "www.google.com" -> "google". */
    private fun siteLabel(host: String): String? =
        host.split(".").firstOrNull { it.isNotEmpty() && it != "www" }

    /**
     * A friendly app name from the URL: the site label, split on non-alphanumeric
     * separators and title-cased ("my-cool-site.com" -> "My Cool Site"). Falls
     * back to the bare host when nothing usable is found.
     */
    private fun deriveAppName(url: String): String {
        val host = hostOf(url) ?: return ""
        val label = siteLabel(host) ?: return host.take(50)
        val words = label.split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return host.take(50)
        return words.joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }.take(50)
    }

    /**
     * Mirrors the Worker's packageNameFromHost so the app previews the exact
     * package the server would generate: com.{site}.webview.
     */
    private fun derivePackageName(url: String): String {
        val host = hostOf(url) ?: return DEFAULT_PACKAGE
        val labels = host.split(".").filter { it.isNotEmpty() && it != "www" }
        var name = (labels.firstOrNull() ?: "").replace(Regex("[^a-z0-9]"), "")
        if (name.length < 2) return DEFAULT_PACKAGE
        if (!name.first().isLetter()) name = "x$name"
        if (name in RESERVED_PACKAGE_WORDS) name = name + "app"
        if (name.length > 50) name = name.substring(0, 50)
        return "com.$name.webview"
    }

    private fun queryDisplayName(uri: Uri): String? = runCatching {
        requireContext().contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
        }
    }.getOrNull()

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
        if (cur > PAGE_ENTRY) binding.wizardPager.setCurrentItem(cur - 1, true)
    }

    /** Step 1 → Step 2. App name/package are optional, so no validation is needed. */
    private fun onNextFromWebsite() {
        // Version code, when the user typed one, must be a positive whole number.
        // A blank field is fine — it falls back to the default at build time.
        stepWebsiteBinding?.let { b ->
            val vc = b.versionCodeInput.text?.toString()?.trim().orEmpty()
            if (vc.isNotEmpty() && (vc.toLongOrNull()?.let { it >= 1 } != true)) {
                b.versionCodeLayout.error = getString(R.string.error_version_code)
                return
            }
            b.versionCodeLayout.error = null
        }
        goToStep(STEP_FEATURES)
    }

    /** User-entered version name, or the default when left blank. */
    private fun enteredVersionName(): String =
        stepWebsiteBinding?.versionNameInput?.text?.toString()?.trim()
            ?.takeIf { it.isNotEmpty() } ?: DEFAULT_VERSION_NAME

    /** User-entered version code (>=1), or the default when blank/invalid. */
    private fun enteredVersionCode(): Long =
        stepWebsiteBinding?.versionCodeInput?.text?.toString()?.trim()
            ?.toLongOrNull()?.takeIf { it >= 1 } ?: DEFAULT_VERSION_CODE

    // =========================================================================
    //  Step updates
    // =========================================================================


    /** Nav bar shows on the entry screen and hides on the numbered steps. */
    private fun updateNavForStep(position: Int) {
        (activity as? MainActivity)?.setBottomNavVisible(position == PAGE_ENTRY)
    }

    private fun updateTopBarForStep(step: Int) {
        // Hamburger is the leading control on the entry screen; the numbered steps
        // show a back arrow instead. GONE (not INVISIBLE) so the hidden control
        // reserves no space and the title lines up with the other fragments.
        binding.topBarMenu.visibility = if (step == PAGE_ENTRY) View.VISIBLE else View.GONE
        binding.topBarBack.visibility = if (step == PAGE_ENTRY) View.GONE else View.VISIBLE
        binding.topBarTitle.text = when (step) {
            PAGE_ENTRY       -> "WebCraft"
            STEP_WEBSITE     -> "App Details"
            STEP_FEATURES    -> "App Features"
            STEP_PERMISSIONS -> "Permissions"
            STEP_SIGNING     -> "Build & Signing"
            else             -> "WebCraft"
        }
    }

    private fun updateNextButtonForStep(step: Int) {
        when (step) {
            // Entry has its own full-width "Start Building" button; the floating Next
            // drives the three middle steps. The signing step has the Build button.
            STEP_WEBSITE, STEP_FEATURES, STEP_PERMISSIONS -> {
                binding.btnNext.visibility = View.VISIBLE
                binding.btnNext.text = "Next"
                binding.btnNext.setIconResource(R.drawable.ic_arrow_forward)
            }
            else -> binding.btnNext.visibility = View.GONE
        }
    }

    /**
     * Entry screen action: validate the URL locally, then ping it. Only a reachable
     * URL advances to Step 1; anything else surfaces an inline error. The button and
     * an indeterminate progress bar reflect the in-flight check.
     */
    private fun onStartBuilding() {
        if (pinging) return
        val entry = stepEntryBinding ?: return
        val url = entry.entryUrlInput.text?.toString()?.trim().orEmpty()

        entry.entryUrlLayout.error = null
        if (url.isEmpty()) { entry.entryUrlLayout.error = getString(R.string.error_url_required); return }
        if (!isValidHttpUrl(url)) { entry.entryUrlLayout.error = getString(R.string.error_url_invalid); return }
        if (isOffline()) { toast(R.string.error_no_network); return }

        pinging = true
        entry.startBuildingButton.isClickable = false
        entry.startBuildingButton.text = "Checking…"
        entry.entryProgress.visibility = View.VISIBLE

        BuildApi.ping(url) { reachable ->
            if (!isAdded || _binding == null) return@ping
            pinging = false
            entry.startBuildingButton.isClickable = true
            entry.startBuildingButton.text = "Start Building"
            entry.entryProgress.visibility = View.GONE

            if (reachable) {
                confirmedUrl = url
                // Every time a URL is confirmed we start a fresh build: never
                // carry over a keystore picked for a previous attempt. Nothing
                // about the last selection is stored.
                clearKeystoreSelection()
                // Prefill app name + package from the URL so the details page
                // is never blank. The user can edit or clear them (a cleared
                // field is auto-filled server-side at build time).
                prefillNamesFromUrl()
                // Bring back the logo stored for this site, if it has one: a logo
                // belongs to the URL it was designed for, so a different URL
                // starts without one.
                restoreLogoForUrl()
                stepWebsiteBinding?.let { updateLogoCard(it) }
                goToStep(STEP_WEBSITE)
            } else {
                entry.entryUrlLayout.error = getString(R.string.error_url_unreachable)
            }
        }
    }

    // =========================================================================
    //  Build flow — register & dispatch, then hand off to Build tab
    // =========================================================================

    private fun onBuildClicked() {
        if (buildDispatching) return
        if (isOffline()) { toast(R.string.error_no_network); return }

        val url         = confirmedUrl
        val appName     = stepWebsiteBinding?.appNameInput?.text?.toString()?.trim().orEmpty()
        val packageName = stepWebsiteBinding?.packageInput?.text?.toString()?.trim().orEmpty()

        if (url.isEmpty() || !isValidHttpUrl(url)) {
            // URL was confirmed on the entry screen; if it's somehow missing, send
            // the user back there to re-enter it.
            goToStep(PAGE_ENTRY)
            return
        }

        // Custom signing requires a keystore file + credentials. Validate before
        // dispatch and send the user back to the signing step if incomplete.
        if (signingMode == "custom") {
            if (customKeystoreB64 == null) {
                toast(R.string.keystore_required)
                goToStep(STEP_SIGNING)
                return
            }
            val storePw = stepSigningBinding?.storePwInput?.text?.toString().orEmpty()
            val keyAlias = stepSigningBinding?.keyAliasInput?.text?.toString()?.trim().orEmpty()
            if (storePw.isEmpty() || keyAlias.isEmpty()) {
                toast(R.string.keystore_fields_required)
                goToStep(STEP_SIGNING)
                return
            }
        }

        val user = auth?.currentUser ?: return
        buildDispatching = true
        stepSigningBinding?.buildButton?.isEnabled = false

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
            // Non-secret build metadata so History renders the right actions.
            "buildType"         to buildType,
            "outputs"           to if (buildType == "release") when (outputFormat) {
                                        "aab"  -> listOf("aab")
                                        "both" -> listOf("apk", "aab")
                                        else   -> listOf("apk")
                                    } else listOf("apk"),
            "signingMode"       to signingMode,
            "keystoreAvailable" to false,
            // Generated-app version the user chose on step 1. Recorded here so a
            // later "update" build can look it up and bump it; the CI confirms the
            // actually-built version on this doc after the build, and the keystore
            // details are added by the Worker post-build.
            "versionCode"       to enteredVersionCode(),
            "versionName"       to enteredVersionName(),
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
            // Version the user chose; the runner applies these to the generated
            // app's build.gradle so the built app carries them.
            put("version_name", enteredVersionName())
            put("version_code", enteredVersionCode())
        }
        for (opt in featureOptions) json.put(opt.key, featureSwitches[opt.key]?.isChecked ?: opt.default)
        val toRemove = LinkedHashSet<String>()
        for (opt in permissionOptions) if (permissionSwitches[opt.key]?.isChecked == false) toRemove.addAll(opt.removeKeywords)
        if (toRemove.isNotEmpty()) json.put("remove_permissions", BuildApi.jsonArrayOf(toRemove))

        // Build type / output / signing.
        json.put("build_type", buildType)
        if (buildType == "release") {
            // Map the UI choice to the Worker's outputs array.
            val outputs = when (outputFormat) {
                "aab"  -> listOf("aab")
                "both" -> listOf("apk", "aab")
                else   -> listOf("apk")
            }
            json.put("outputs", BuildApi.jsonArrayOf(outputs))
        }
        json.put("signing_mode", signingMode)
        if (signingMode == "custom") {
            // Keystore bytes + passwords travel to the Worker over HTTPS only;
            // they are never written to Firestore.
            json.put("keystore_b64", customKeystoreB64)
            json.put("store_password", stepSigningBinding?.storePwInput?.text?.toString().orEmpty())
            json.put("key_alias", stepSigningBinding?.keyAliasInput?.text?.toString()?.trim().orEmpty())
            val keyPw = stepSigningBinding?.keyPwInput?.text?.toString().orEmpty()
            if (keyPw.isNotEmpty()) json.put("key_password", keyPw)
            // Ask the Worker to save an on-device-generated keystore into the
            // per-user reuse store ({uid}/keystores/{package}) so future auto
            // builds of this package sign with the same key.
            if (customKeystoreGenerated) json.put("save_to_keystores", true)
        }

        // Custom launcher icon: the generated res/ ZIP travels to the Worker as
        // base64 (over HTTPS, never in the dispatch payload the runner logs). The
        // Worker stores it and hands the runner an icons_fetch_url to extract.
        logoZipPath?.let { path ->
            runCatching {
                val bytes = java.io.File(path).readBytes()
                json.put("icon_zip_b64", Base64.encodeToString(bytes, Base64.NO_WRAP))
            }
        }

        // Preview image: the same launcher icon as a single small WebP, sent the
        // same way as the ZIP above. The Worker stores it beside that ZIP and links
        // it from the build doc as `previewImage`, so a finished build can be shown
        // without downloading it. Optional and best-effort: a logo with no preview
        // stored yet simply leaves the field off the request.
        previewWebpBase64(logoPreviewPath)?.let { json.put("preview_image_b64", it) }
        return json
    }

    /**
     * [path]'s image re-encoded as a base64 WebP, or null when there is no file
     * or it cannot be read.
     *
     * WebP rather than the PNG [LogoStore] keeps on disk: it holds on to the
     * icon's transparency at a fraction of the size, which matters because these
     * bytes ride in the build request body. Quality 100 makes it lossless, so the
     * preview is the icon exactly as generated.
     */
    private fun previewWebpBase64(path: String?): String? {
        if (path == null) return null
        return runCatching {
            val bitmap = BitmapFactory.decodeFile(path) ?: return null
            val out = ByteArrayOutputStream()
            @Suppress("DEPRECATION")   // WEBP at quality 100 is lossless
            bitmap.compress(Bitmap.CompressFormat.WEBP, 100, out)
            Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        }.getOrNull()
    }

    // =========================================================================
    //  Helpers
    // =========================================================================

    /** Reset wizard to the entry screen so the user can start a new build. */
    private fun resetWizard() {
        buildDispatching = false
        confirmedUrl = ""
        namesDerivedForUrl = null
        stepSigningBinding?.let { b ->
            b.ownershipCheckbox.isChecked = false
            b.buildButton.isEnabled = false
        }
        binding.wizardPager.setCurrentItem(PAGE_ENTRY, false)
        stepEntryBinding?.entryUrlInput?.text?.clear()
        stepWebsiteBinding?.appNameInput?.text?.clear()
        stepWebsiteBinding?.packageInput?.text?.clear()
        stepWebsiteBinding?.versionNameInput?.text?.clear()
        stepWebsiteBinding?.versionCodeInput?.text?.clear()
        stepWebsiteBinding?.versionCodeLayout?.error = null
        clearLogoSelection()
        stepWebsiteBinding?.let { updateLogoCard(it) }
        // Reset signing selections back to the defaults for the next build.
        buildType = "debug"; signingMode = "auto"; outputFormat = "apk"
        clearKeystoreSelection()
        stepSigningBinding?.let { b ->
            b.buildTypeToggle.check(b.buildTypeDebug.id)
            b.signingToggle.check(b.signingAuto.id)
            b.outputToggle.check(b.outputApk.id)
            b.outputFormatSection.visibility = View.GONE
            b.customKeystoreSection.visibility = View.GONE
            b.keystoreFileName.visibility = View.GONE
            b.storePwInput.text?.clear(); b.keyAliasInput.text?.clear(); b.keyPwInput.text?.clear()
            applySigningLockForBuildType(b)
        }
        (activity as? MainActivity)?.setBottomNavVisible(true, animate = false)
    }

    private fun resetBuildButton() {
        buildDispatching = false
        stepSigningBinding?.let { b ->
            b.buildButton.isEnabled = b.ownershipCheckbox.isChecked
        }
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

    private fun setupButtonAnimations() {
        val entry = stepEntryBinding ?: return
        val shadow = entry.startBuildingShadow
        val btn = entry.startBuildingButton

        val shadowAnimator = ValueAnimator.ofFloat(-300f, 1500f).apply {
            duration = 2000
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { anim ->
                shadow.translationX = anim.animatedValue as Float
            }
        }
        shadowAnimator.start()

        val baseArrow = androidx.core.content.ContextCompat.getDrawable(requireContext(), R.drawable.ic_arrow_forward)?.mutate()
        if (baseArrow != null) {
            // Forcefully tint the base arrow to the exact current text color using a color filter
            baseArrow.setColorFilter(btn.currentTextColor, android.graphics.PorterDuff.Mode.SRC_IN)
            val maxSlide = dpToPx(8).toFloat()
            val slidingDrawable = object : android.graphics.drawable.Drawable() {
                var offset = 0f
                    set(value) { field = value; invalidateSelf() }
                
                // Ignore tint updates from MaterialButton to prevent it from overriding our text-matched tint
                override fun setTintList(tint: android.content.res.ColorStateList?) {}
                override fun setTint(tintColor: Int) {}
                override fun setTintMode(tintMode: android.graphics.PorterDuff.Mode?) {}
                
                override fun draw(canvas: android.graphics.Canvas) {
                    canvas.save()
                    canvas.translate(offset, 0f)
                    baseArrow.draw(canvas)
                    canvas.restore()
                }
                
                override fun setBounds(left: Int, top: Int, right: Int, bottom: Int) {
                    super.setBounds(left, top, right, bottom)
                    baseArrow.setBounds(left, top, left + baseArrow.intrinsicWidth, bottom)
                }
                
                override fun getIntrinsicWidth() = baseArrow.intrinsicWidth + maxSlide.toInt()
                override fun getIntrinsicHeight() = baseArrow.intrinsicHeight
                override fun setAlpha(alpha: Int) { baseArrow.alpha = alpha }
                override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) { baseArrow.colorFilter = colorFilter }
                @Deprecated("Deprecated in Java")
                override fun getOpacity() = baseArrow.opacity
            }
            btn.icon = slidingDrawable

            val iconAnimator = ValueAnimator.ofFloat(0f, maxSlide).apply {
                duration = 800
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.REVERSE
                interpolator = AccelerateDecelerateInterpolator()
                addUpdateListener { anim ->
                    slidingDrawable.offset = anim.animatedValue as Float
                }
            }
            iconAnimator.start()
            buttonAnimators = listOf(shadowAnimator, iconAnimator)
        } else {
            buttonAnimators = listOf(shadowAnimator)
        }
    }

    override fun onDestroyView() {
        buttonAnimators?.forEach { it.cancel() }
        buttonAnimators = null
        stepEntryBinding       = null
        stepWebsiteBinding     = null
        stepFeaturesBinding    = null
        stepPermissionsBinding = null
        stepSigningBinding     = null
        _binding = null
        super.onDestroyView()
    }

    private companion object {
        private const val TAG = "HomeFragment"

        /** Fallback package when the host yields nothing usable (matches the Worker). */
        private const val DEFAULT_PACKAGE = "com.bhg.webview"

        /** Version defaults for a first build; the build template also ships these. */
        private const val DEFAULT_VERSION_NAME = "1.0"
        private const val DEFAULT_VERSION_CODE = 1L

        /**
         * Java/Kotlin keywords that cannot be a package segment. Kept in sync with
         * the Worker's RESERVED_WORDS so the previewed package matches what the
         * server accepts (a reserved site label gets "app" appended instead).
         */
        private val RESERVED_PACKAGE_WORDS = setOf(
            "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class",
            "const", "continue", "default", "do", "double", "else", "enum", "extends", "final",
            "finally", "float", "for", "goto", "if", "implements", "import", "instanceof", "int",
            "interface", "long", "native", "new", "package", "private", "protected", "public",
            "return", "short", "static", "strictfp", "super", "switch", "synchronized", "this",
            "throw", "throws", "transient", "try", "void", "volatile", "while",
            "as", "fun", "in", "is", "object", "typealias", "val", "var", "when"
        )
    }
}
