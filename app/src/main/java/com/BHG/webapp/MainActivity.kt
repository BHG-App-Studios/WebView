package com.BHG.webapp

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.GravityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.fragment.app.Fragment
import com.BHG.webapp.databinding.ActivityMainBinding
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions

/**
 * App shell: a start drawer of settings and a three-tab bottom navigation
 * (Home / History / Profile) hosting fragments. Each fragment carries its own
 * 64dp top bar, matching the reference app.
 *
 * The activity owns cross-cutting concerns — the auth guard, profile upkeep,
 * theme switching, drawer access, and sign-out — while each tab's screen is a
 * [Fragment].
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private var auth: FirebaseAuth? = null
    private var firestore: FirebaseFirestore? = null

    /** Google Play immediate in-app updates. Null until past the auth guard. */
    private var inAppUpdateManager: InAppUpdateManager? = null

    /** Google Play in-app review. Null until past the auth guard. */
    private var inAppReviewManager: InAppReviewManager? = null

    private var currentTab = 0

    /**
     * When the user taps "Update" on a saved app, its details are stashed here
     * and the wizard tab is opened; HomeFragment consumes this once to pre-fill
     * the URL, package and a bumped version so the next build updates that app.
     */
    var pendingUpdatePrefill: BuildPrefill? = null
    private var backPressedTime: Long = 0
    private var navVisible = true

    private lateinit var navButtons: List<Pair<Int, MaterialButton>>
    private val navPillMargin by lazy { resources.getDimensionPixelSize(R.dimen.nav_pill_margin) }

    private val navLabels = mapOf(
        R.id.nav_landing to R.string.nav_landing,
        R.id.nav_home to R.string.nav_home,
        R.id.nav_history to R.string.nav_history,
        R.id.nav_profile to R.string.nav_profile
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()
        window.statusBarColor = getColor(R.color.status_bar_bg)
        window.navigationBarColor = getColor(R.color.navigation_bar_bg)

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

        applyInsets()
        setupDrawer()
        setupBottomNav()

        if (savedInstanceState == null) {
            selectTab(R.id.nav_landing)
        } else {
            currentTab = savedInstanceState.getInt(KEY_TAB, R.id.nav_landing)
        }
        syncNavSelection()
        setupBackPress()

        // Offer a Google Play immediate update if one is live. Registered here in
        // onCreate (before the activity is STARTED) as the Activity Result API needs.
        inAppUpdateManager = InAppUpdateManager(this).also { it.checkForUpdate() }

        // In-app review: only ever shown once the user has a build that reached
        // READY (checked against Firestore inside the manager). Attempted from
        // onResume so an update flow, if any, takes precedence first.
        inAppReviewManager = InAppReviewManager(this)

        // When an overlay (Trash) is popped off the back stack, bring the capsule
        // nav back with a smooth slide.
        supportFragmentManager.addOnBackStackChangedListener {
            if (supportFragmentManager.backStackEntryCount == 0) {
                setBottomNavVisible(true, animate = true)
            }
        }
    }

    private fun applyInsets() {

        ViewCompat.setOnApplyWindowInsetsListener(binding.mainContainer) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, 0)
            // The capsule floats above the gesture bar: keep its bottom margin above
            // the system inset so it never sits under the navigation bar.
            val lp = binding.bottomNav.navCapsule.layoutParams as ViewGroup.MarginLayoutParams
            lp.bottomMargin = navPillMargin + systemBars.bottom
            binding.bottomNav.navCapsule.layoutParams = lp
            // Don't consume: the wizard pager reads the same inset to reserve the
            // gesture/nav-bar area under its content.
            insets
        }
    }

    /**
     * Wires the four capsule buttons. Selecting one checks it (which fills its pill
     * and reveals its label) and clears the others; the row's animateLayoutChanges
     * makes the grow/shrink smooth. Reselecting the current tab is a no-op.
     */
    private fun setupBottomNav() {
        navButtons = listOf(
            R.id.nav_landing to binding.bottomNav.navLanding,
            R.id.nav_home to binding.bottomNav.navBuild,
            R.id.nav_history to binding.bottomNav.navApps,
            R.id.nav_profile to binding.bottomNav.navAccount
        )
        val labels = mapOf(
            R.id.nav_landing to R.string.nav_landing,
            R.id.nav_home to R.string.nav_home,
            R.id.nav_history to R.string.nav_history,
            R.id.nav_profile to R.string.nav_profile
        )
        navButtons.forEach { (id, button) ->
            button.isCheckable = true
            button.setOnClickListener {
                if (id != currentTab) selectTab(id)
                syncNavSelection()
            }
        }
        syncNavSelection()
    }

    /** Checks the active button (label shown) and collapses the rest to icon-only. */
    private fun syncNavSelection() {
        if (!::navButtons.isInitialized) return
        navButtons.forEach { (id, button) ->
            val selected = id == currentTab
            button.isChecked = selected
            button.text = if (selected) getString(navLabels.getValue(id)) else ""
        }
    }

    /** Opens the drawer. Called from each fragment's own top bar menu button. */
    fun openDrawer() {
        binding.drawerLayout.openDrawer(GravityCompat.START)
    }

    /** Shows the floating options bottom sheet. */
    fun openOptionsSheet() {
        OptionsSheet().show(supportFragmentManager, OptionsSheet.TAG)
    }

    /**
     * Shows or hides the floating capsule nav with a slide + fade. Used by the
     * builder wizard: the bar stays on the entry screen (and every other tab) but
     * slides away while the user is inside the numbered build steps, so the wizard
     * gets the full height.
     */
    fun setBottomNavVisible(visible: Boolean, animate: Boolean = true) {
        val capsule = binding.bottomNav.navCapsule
        if (visible == navVisible && animate) return
        navVisible = visible

        // Distance to travel: the capsule's height plus its bottom margin, so it
        // clears the screen edge entirely when hidden.
        val lp = capsule.layoutParams as ViewGroup.MarginLayoutParams
        val travel = (capsule.height + lp.bottomMargin).toFloat().coerceAtLeast(1f)

        if (!animate) {
            capsule.translationY = if (visible) 0f else travel
            capsule.alpha = if (visible) 1f else 0f
            capsule.visibility = if (visible) View.VISIBLE else View.GONE
            return
        }

        if (visible) capsule.visibility = View.VISIBLE
        capsule.animate()
            .translationY(if (visible) 0f else travel)
            .alpha(if (visible) 1f else 0f)
            .setDuration(220L)
            .withEndAction { if (!visible) capsule.visibility = View.GONE }
            .start()
    }

    private fun selectTab(itemId: Int) {
        val fragment: Fragment = when (itemId) {
            R.id.nav_home    -> BuildFragment()
            R.id.nav_history -> HistoryFragment()
            R.id.nav_profile -> ProfileFragment()
            else             -> HomeFragment()   // nav_landing = wizard
        }
        currentTab = itemId
        // Any tab switch lands on a screen that shows the nav (the wizard only hides
        // it once you advance past its entry screen), so restore it here.
        setBottomNavVisible(true, animate = false)
        supportFragmentManager.beginTransaction()
            .setReorderingAllowed(true)
            .replace(R.id.fragmentContainer, fragment)
            .commit()
    }

    /** Public entry point for a fragment to switch tabs (e.g. Home's quick actions). */
    fun goToTab(itemId: Int) {
        if (itemId == currentTab) return
        selectTab(itemId)
        syncNavSelection()
    }

    /** Open the wizard pre-filled to build an update of an existing app. */
    fun startAppUpdate(prefill: BuildPrefill) {
        pendingUpdatePrefill = prefill
        goToTab(R.id.nav_landing)
    }

    /**
     * Show the Trash screen over the current tab, on the back stack so the system
     * Back button (and the screen's own Back arrow) returns to My Apps.
     */
    fun showTrash() {
        // Trash is a full-screen overlay: slide the capsule nav away while it's up.
        setBottomNavVisible(false, animate = true)
        supportFragmentManager.beginTransaction()
            .setReorderingAllowed(true)
            .replace(R.id.fragmentContainer, TrashFragment())
            .addToBackStack("trash")
            .commit()
    }

    /** Pop the Trash screen (or any back-stack entry) and return to the tab below. */
    fun goBackFromOverlay() {
        supportFragmentManager.popBackStack()
    }

    // ---- Drawer / settings ---------------------------------------------------

    private fun setupBackPress() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.drawerLayout.isDrawerOpen(GravityCompat.START)) {
                    binding.drawerLayout.closeDrawer(GravityCompat.START)
                    return
                }

                // An overlay screen (e.g. Trash) sits on the back stack: pop it
                // and return to the tab beneath instead of the exit prompt.
                if (supportFragmentManager.backStackEntryCount > 0) {
                    supportFragmentManager.popBackStack()
                    return
                }

                // From any tab other than the landing wizard, Back returns there
                // rather than prompting to exit.
                if (currentTab != R.id.nav_landing) {
                    goToTab(R.id.nav_landing)
                    return
                }

                if (backPressedTime + 2000 > System.currentTimeMillis()) {
                    finish()
                } else {
                    Toast.makeText(this@MainActivity, "Press back again to exit", Toast.LENGTH_SHORT).show()
                }
                backPressedTime = System.currentTimeMillis()
            }
        })
    }

    private fun setupDrawer() {
        val drawer = binding.navigationDrawer

        drawer.drawerClose.setOnClickListener {
            binding.drawerLayout.closeDrawer(GravityCompat.START)
        }

        // Theme toggle reflects the saved choice and applies changes immediately.
        when (ThemePrefs.getMode(this)) {
            ThemePrefs.LIGHT -> drawer.themeToggle.check(R.id.themeLight)
            ThemePrefs.DARK -> drawer.themeToggle.check(R.id.themeDark)
            else -> drawer.themeToggle.check(R.id.themeSystem)
        }
        drawer.themeToggle.addOnButtonCheckedListener(
            MaterialButtonToggleGroup.OnButtonCheckedListener { _, checkedId, isChecked ->
                if (!isChecked) return@OnButtonCheckedListener
                val mode = when (checkedId) {
                    R.id.themeLight -> ThemePrefs.LIGHT
                    R.id.themeDark -> ThemePrefs.DARK
                    else -> ThemePrefs.SYSTEM
                }
                if (mode != ThemePrefs.getMode(this)) {
                    // Persist + apply; AppCompat recreates the activity with the
                    // correct day/night resources.
                    ThemePrefs.setMode(this, mode)
                }
            }
        )

        drawer.drawerPrivacy.setOnClickListener { openUrl(PRIVACY_URL) }
        drawer.drawerTerms.setOnClickListener { openUrl(TERMS_URL) }
        drawer.drawerRate.setOnClickListener { openPlayStore() }
        drawer.drawerSignOut.setOnClickListener {
            binding.drawerLayout.closeDrawer(GravityCompat.START)
            signOut()
        }

        binding.drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED)
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)))
        } catch (e: Exception) {
            Log.w(TAG, "openUrl failed: ${e.message}")
        }
    }

    private fun openPlayStore() {
        val pkg = packageName
        try {
            startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("market://details?id=$pkg")))
        } catch (e: Exception) {
            openUrl("https://play.google.com/store/apps/details?id=$pkg")
        }
    }

    fun signOut() {
        try {
            auth?.signOut()
        } catch (e: Exception) {
            Log.w(TAG, "Sign-out failed: ${e.message}")
        }
        startActivity(Intent(this, AuthActivity::class.java))
        finish()
    }

    private fun saveProfile(user: FirebaseUser) {
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

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_TAB, currentTab)
    }

    override fun onResume() {
        super.onResume()
        // If an immediate update was accepted but interrupted (user backgrounded
        // the app mid-download), Play reports it in progress and we resume it.
        inAppUpdateManager?.resumeIfInProgress()

        // Offer the review card if eligible (≥1 READY build). No-ops until then,
        // and self-throttles so it never nags on every foreground.
        inAppReviewManager?.requestReviewIfEligible(firestore, auth?.currentUser?.uid)
    }

    private companion object {
        private const val TAG = "MainActivity"

        private const val KEY_TAB = "current_tab"
        private const val PRIVACY_URL = "https://bhg-app-studios.pages.dev/app-privacy.html?app=website-to-app-builder"
        private const val TERMS_URL = "https://bhg-app-studios.pages.dev/app-terms.html?app=website-to-app-builder"
    }
}

/** Pre-fill data carried from "My Apps → Update" into the build wizard. */
data class BuildPrefill(
    val url: String,
    val packageName: String,
    val versionName: String,
    val versionCode: Long
)
