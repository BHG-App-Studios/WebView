package com.BHG.webapp

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.GravityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.drawerlayout.widget.DrawerLayout
import androidx.fragment.app.Fragment
import com.BHG.webapp.databinding.ActivityMainBinding
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions

/**
 * App shell: a Material toolbar, a start drawer of settings, and a three-tab
 * bottom navigation (Home / History / Profile) hosting fragments.
 *
 * The activity owns cross-cutting concerns — the auth guard, profile upkeep,
 * theme switching, and sign-out — while each tab's screen is a [Fragment].
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private var auth: FirebaseAuth? = null
    private var firestore: FirebaseFirestore? = null

    private var currentTab = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

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

        window.statusBarColor = getColor(R.color.status_bar_bg)
        window.navigationBarColor = getColor(R.color.navigation_bar_bg)

        applyInsets()
        setupToolbar()
        setupDrawer()
        setupBottomNav()

        if (savedInstanceState == null) {
            selectTab(R.id.nav_home)
        } else {
            currentTab = savedInstanceState.getInt(KEY_TAB, R.id.nav_home)
            updateTitle(currentTab)
        }
    }

    private fun applyInsets() {
        // Pad the whole content area by the system bars and consume the insets, exactly
        // like the reference app. This lifts the bottom bar above the system nav bar so it
        // keeps its compact 2dp/11dp height; the system nav strip itself is painted by
        // window.navigationBarColor, which matches the bar background.
        ViewCompat.setOnApplyWindowInsetsListener(binding.mainContainer) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
    }

    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener {
            binding.drawerLayout.openDrawer(GravityCompat.START)
        }
    }

    private fun setupBottomNav() {
        binding.bottomNavigation.setOnItemSelectedListener { item ->
            selectTab(item.itemId)
            true
        }
        binding.bottomNavigation.setOnItemReselectedListener { /* no-op: don't reload */ }
    }

    private fun selectTab(itemId: Int) {
        val fragment: Fragment = when (itemId) {
            R.id.nav_history -> HistoryFragment()
            R.id.nav_profile -> ProfileFragment()
            else -> HomeFragment()
        }
        currentTab = itemId
        updateTitle(itemId)
        supportFragmentManager.beginTransaction()
            .setReorderingAllowed(true)
            .replace(R.id.fragmentContainer, fragment)
            .commit()
    }

    private fun updateTitle(itemId: Int) {
        binding.toolbar.title = getString(
            when (itemId) {
                R.id.nav_history -> R.string.title_history
                R.id.nav_profile -> R.string.title_profile
                else -> R.string.title_home
            }
        )
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
        } else if (currentTab != R.id.nav_home) {
            binding.bottomNavigation.selectedItemId = R.id.nav_home
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
