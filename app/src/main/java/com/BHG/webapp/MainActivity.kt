package com.BHG.webapp

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.ViewGroup
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

    private var currentTab = 0

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
            WindowInsetsCompat.CONSUMED
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

    private fun selectTab(itemId: Int) {
        val fragment: Fragment = when (itemId) {
            R.id.nav_home -> HomeFragment()
            R.id.nav_history -> HistoryFragment()
            R.id.nav_profile -> ProfileFragment()
            else -> LandingFragment()
        }
        currentTab = itemId
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

    // ---- Drawer / settings ---------------------------------------------------

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

    @Deprecated("Base API deprecated; drawer + tab back handling still needed here")
    override fun onBackPressed() {
        if (binding.drawerLayout.isDrawerOpen(GravityCompat.START)) {
            binding.drawerLayout.closeDrawer(GravityCompat.START)
        } else if (currentTab != R.id.nav_landing) {
            goToTab(R.id.nav_landing)
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_TAB, currentTab)
    }

    private companion object {
        private const val TAG = "MainActivity"
        private const val KEY_TAB = "current_tab"
        private const val PRIVACY_URL = "https://sites.google.com/view/website-app-builder-privacy"
        private const val TERMS_URL = "https://sites.google.com/view/website-app-builder-terms"
    }
}
