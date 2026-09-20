package com.BHG.webapp

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.CancellationSignal
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.credentials.Credential
import androidx.credentials.CredentialManager
import androidx.credentials.CredentialManagerCallback
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetCredentialResponse
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.BHG.webapp.databinding.ActivityAuthBinding
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Gates the app behind a single "Sign in with Google" step.
 *
 * Uses the modern Credential Manager API (androidx.credentials) with [GetSignInWithGoogleOption]
 * — the successor to the deprecated GoogleSignIn SDK — to obtain a Google ID token, which is then
 * exchanged for a Firebase credential. Anonymous / skip sign-in is intentionally not supported.
 *
 * Design notes:
 *  - Thread-safe: [CredentialManager.getCredentialAsync] runs off the main thread; its callback is
 *    dispatched back onto the main thread via [ContextCompat.getMainExecutor], and Firebase's
 *    listener is bound to this activity, so all UI work happens on the main thread only.
 *  - Crash-proof: every callback bails out if the activity is finishing/destroyed, all parsing is
 *    wrapped, and the in-flight request is cancelled in onDestroy.
 *  - Replay-protected: a random nonce is hashed (SHA-256) for the credential request and the raw
 *    nonce is handed to Firebase for verification.
 */
class AuthActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAuthBinding

    private var auth: FirebaseAuth? = null

    private var credentialManager: CredentialManager? = null

    // Guards against double taps launching two credential requests at once.
    private var signInInProgress = false

    // Allows an in-flight credential request to be cancelled on teardown.
    private var cancellationSignal: CancellationSignal? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        // Swaps the splash theme for Theme.WebsiteAppBuilder (postSplashScreenTheme) before any
        // view is inflated, so Material attributes resolve correctly.
        installSplashScreen()
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

        credentialManager = try {
            CredentialManager.create(this)
        } catch (e: Exception) {
            Log.w(TAG, "CredentialManager init failed: ${e.message}")
            null
        }

        binding.signInButton.setOnClickListener { startSignIn() }
    }

    private fun startSignIn() {
        if (signInInProgress) return

        if (isOffline()) {
            toast(R.string.sign_in_no_network)
            return
        }

        val manager = credentialManager ?: run {
            toast(R.string.sign_in_failed)
            return
        }

        setLoading(true)

        val rawNonce = newRawNonce()
        val hashedNonce = sha256(rawNonce)

        val option = GetSignInWithGoogleOption
            .Builder(getString(R.string.default_web_client_id))
            .setNonce(hashedNonce)
            .build()

        val request = GetCredentialRequest.Builder()
            .addCredentialOption(option)
            .build()

        val signal = CancellationSignal()
        cancellationSignal = signal

        try {
            manager.getCredentialAsync(
                context = this,
                request = request,
                cancellationSignal = signal,
                executor = ContextCompat.getMainExecutor(this),
                callback = object : CredentialManagerCallback<GetCredentialResponse, GetCredentialException> {
                    override fun onResult(result: GetCredentialResponse) {
                        if (isFinishing || isDestroyed) return
                        handleCredential(result.credential, rawNonce)
                    }

                    override fun onError(e: GetCredentialException) {
                        if (isFinishing || isDestroyed) return
                        setLoading(false)
                        when (e) {
                            is GetCredentialCancellationException -> toast(R.string.sign_in_cancelled)
                            is NoCredentialException -> toast(R.string.sign_in_no_account)
                            else -> {
                                Log.w(TAG, "getCredential failed: ${e.javaClass.simpleName}: ${e.message}")
                                toast(R.string.sign_in_failed)
                            }
                        }
                    }
                }
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to start credential request: ${e.message}")
            setLoading(false)
            toast(R.string.sign_in_failed)
        }
    }

    private fun handleCredential(credential: Credential, rawNonce: String) {
        if (credential is CustomCredential &&
            credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
        ) {
            val idToken = try {
                GoogleIdTokenCredential.createFrom(credential.data).idToken
            } catch (e: GoogleIdTokenParsingException) {
                Log.w(TAG, "Invalid Google ID token: ${e.message}")
                setLoading(false)
                toast(R.string.sign_in_failed)
                return
            }

            if (idToken.isNullOrEmpty()) {
                setLoading(false)
                toast(R.string.sign_in_failed)
                return
            }

            firebaseAuthWithGoogle(idToken, rawNonce)
        } else {
            Log.w(TAG, "Unexpected credential type: ${credential.type}")
            setLoading(false)
            toast(R.string.sign_in_failed)
        }
    }

    private fun firebaseAuthWithGoogle(idToken: String, rawNonce: String) {
        val credential = GoogleAuthProvider.getCredential(idToken, rawNonce)
        val localAuth = auth ?: FirebaseAuth.getInstance()

        localAuth.signInWithCredential(credential).addOnCompleteListener(this) { task ->
            // addOnCompleteListener(this, ...) is not delivered after the activity is destroyed,
            // but guard defensively before touching any views.
            if (isFinishing || isDestroyed) return@addOnCompleteListener

            if (task.isSuccessful && localAuth.currentUser != null) {
                saveProfile(localAuth.currentUser!!)
                goToMain()
            } else {
                Log.w(TAG, "Firebase auth failed: ${task.exception?.message}")
                setLoading(false)
                toast(R.string.sign_in_failed)
            }
        }
    }

    /**
     * Upserts the user profile under users/{uid} the moment sign-in succeeds,
     * so a new user's details are stored immediately — not only on their first
     * build. Merge, so it never disturbs an existing builds subcollection.
     * Fire-and-forget: a failure here must not block entering the app.
     */
    private fun saveProfile(user: com.google.firebase.auth.FirebaseUser) {
        val db = try {
            FirebaseFirestore.getInstance()
        } catch (e: Exception) {
            Log.w(TAG, "Firestore init failed: ${e.message}")
            return
        }
        val profile = mapOf(
            "uid" to user.uid,
            "email" to (user.email ?: ""),
            "displayName" to (user.displayName ?: ""),
            "photoUrl" to (user.photoUrl?.toString() ?: ""),
            "firstOpenAt" to FieldValue.serverTimestamp()
        )
        db.collection("users").document(user.uid)
            .set(profile, SetOptions.merge())
            .addOnFailureListener { e -> Log.w(TAG, "Profile save failed: ${e.message}") }
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

    private fun newRawNonce(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    override fun onDestroy() {
        // Cancel any in-flight credential request so its callback can't fire post-teardown.
        cancellationSignal?.cancel()
        cancellationSignal = null
        super.onDestroy()
    }

    private companion object {
        private const val TAG = "AuthActivity"
    }
}
