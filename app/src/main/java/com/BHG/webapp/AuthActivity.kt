package com.BHG.webapp

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.BHG.webapp.databinding.ActivityAuthBinding
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider

/**
 * Gates the app behind a single "Sign in with Google" step backed by Firebase Auth.
 *
 * Mirrors the production sign-in flow: legacy Google Sign-In -> ID token -> Firebase credential.
 * Anonymous / skip sign-in is intentionally not supported.
 *
 * Crash-proof: every external result is null-checked and wrapped, the button is debounced against
 * double taps, and every async callback bails out if the activity is finishing/destroyed before it
 * touches a view.
 */
class AuthActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAuthBinding

    private var auth: FirebaseAuth? = null

    private var googleSignInClient: GoogleSignInClient? = null

    // Guards against double taps launching two sign-in flows at once.
    private var signInInProgress = false

    private val signInLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            handleSignInResult(result.data)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        auth = try {
            FirebaseAuth.getInstance()
        } catch (e: Exception) {
            Log.w(TAG, "FirebaseAuth init failed: ${e.message}")
            null
        }

        // Already authenticated -> skip straight to the app.
        val currentUser = try {
            auth?.currentUser
        } catch (e: Exception) {
            null
        }
        if (currentUser != null) {
            goToMain()
            return
        }

        binding = ActivityAuthBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            v.updatePadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(getString(R.string.default_web_client_id))
            .requestEmail()
            .build()
        googleSignInClient = GoogleSignIn.getClient(this, gso)

        binding.signInButton.setOnClickListener { startSignIn() }
    }

    private fun startSignIn() {
        if (signInInProgress) return

        if (isOffline()) {
            toast(R.string.sign_in_no_network)
            return
        }

        val client = googleSignInClient ?: run {
            toast(R.string.sign_in_failed)
            return
        }

        setLoading(true)

        // Sign out of the cached Google session first so the account chooser is always shown and a
        // stale/revoked token is never silently reused.
        client.signOut().addOnCompleteListener(this) {
            if (isFinishing || isDestroyed) return@addOnCompleteListener
            try {
                signInLauncher.launch(client.signInIntent)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to launch sign-in intent: ${e.message}")
                setLoading(false)
                toast(R.string.sign_in_failed)
            }
        }
    }

    private fun handleSignInResult(data: Intent?) {
        if (data == null) {
            setLoading(false)
            toast(R.string.sign_in_cancelled)
            return
        }

        val task = GoogleSignIn.getSignedInAccountFromIntent(data)
        try {
            val account = task.getResult(ApiException::class.java)
            val idToken = account?.idToken
            if (idToken.isNullOrEmpty()) {
                setLoading(false)
                toast(R.string.sign_in_failed)
                return
            }
            firebaseAuthWithGoogle(idToken)
        } catch (e: ApiException) {
            setLoading(false)
            when (e.statusCode) {
                CommonStatusCodes.CANCELED,
                CommonStatusCodes.SIGN_IN_REQUIRED -> toast(R.string.sign_in_cancelled)
                CommonStatusCodes.NETWORK_ERROR -> toast(R.string.sign_in_no_network)
                else -> toast(R.string.sign_in_failed)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Unexpected sign-in error: ${e.message}")
            setLoading(false)
            toast(R.string.sign_in_failed)
        }
    }

    private fun firebaseAuthWithGoogle(idToken: String) {
        val credential = GoogleAuthProvider.getCredential(idToken, null)
        val localAuth = auth ?: FirebaseAuth.getInstance()

        localAuth.signInWithCredential(credential).addOnCompleteListener(this) { task ->
            // addOnCompleteListener(this, ...) is not delivered after the activity is destroyed,
            // but guard defensively before touching any views.
            if (isFinishing || isDestroyed) return@addOnCompleteListener

            if (task.isSuccessful && localAuth.currentUser != null) {
                goToMain()
            } else {
                Log.w(TAG, "Firebase auth failed: ${task.exception?.message}")
                setLoading(false)
                toast(R.string.sign_in_failed)
            }
        }
    }

    private fun goToMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    private fun setLoading(loading: Boolean) {
        signInInProgress = loading
        if (!this::binding.isInitialized) return
        binding.authProgress.visibility = if (loading) View.VISIBLE else View.GONE
        binding.signInButton.isEnabled = !loading
    }

    private fun toast(resId: Int) {
        if (isFinishing || isDestroyed) return
        Toast.makeText(this, resId, Toast.LENGTH_SHORT).show()
    }

    private fun isOffline(): Boolean {
        val manager = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val network = manager.activeNetwork ?: return true
        val capabilities = manager.getNetworkCapabilities(network) ?: return true
        return !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private companion object {
        private const val TAG = "AuthActivity"
    }
}
