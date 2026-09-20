package com.BHG.webapp

import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.CancellationSignal
import android.text.Spannable
import android.text.SpannableString
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.util.Log
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
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
import androidx.credentials.exceptions.GetCredentialInterruptedException
import androidx.credentials.exceptions.NoCredentialException
import com.BHG.webapp.databinding.ActivityAuthBinding
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import com.google.firebase.FirebaseException
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.FirebaseTooManyRequestsException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
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

    // Looping arrow animation in the hero.
    private var arrowAnimator: ValueAnimator? = null

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
        binding.helpButton.setOnClickListener { openUrl(HELP_URL) }

        setupTermsLinks()
        runEntranceAnimations()
    }

    /** Builds the "By continuing…" line with tappable Privacy / Terms links. */
    private fun setupTermsLinks() {
        val privacy = getString(R.string.auth_terms_privacy)
        val tos = getString(R.string.auth_terms_tos)
        val full = getString(R.string.auth_terms, privacy, tos)

        val spannable = SpannableString(full)
        linkify(spannable, full, privacy, PRIVACY_URL)
        linkify(spannable, full, tos, TERMS_URL)

        binding.termsText.text = spannable
        binding.termsText.movementMethod = LinkMovementMethod.getInstance()
        binding.termsText.highlightColor = Color.TRANSPARENT
    }

    private fun linkify(spannable: SpannableString, full: String, label: String, url: String) {
        val start = full.indexOf(label)
        if (start < 0) return
        spannable.setSpan(object : ClickableSpan() {
            override fun onClick(widget: View) = openUrl(url)
            override fun updateDrawState(ds: android.text.TextPaint) {
                super.updateDrawState(ds)
                ds.isUnderlineText = false
            }
        }, start, start + label.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)))
        } catch (e: Exception) {
            Log.w(TAG, "openUrl failed: ${e.message}")
        }
    }

    /**
     * Staggered entrance for the hero, title, subtitle, button, and terms, plus
     * a gentle looping nudge on the arrow so the "web → app" idea reads as motion.
     * All views are guarded and animations are cancelled on destroy.
     */
    private fun runEntranceAnimations() {
        val globe = binding.heroGlobe
        val apk = binding.heroApk
        val arrow = binding.heroArrow

        // Start hidden.
        listOf(globe, apk, arrow, binding.titleText, binding.subtitleText,
            binding.signInButton, binding.termsText).forEach { it.alpha = 0f }

        globe.translationX = -60f
        apk.translationX = 60f
        arrow.scaleX = 0f
        arrow.scaleY = 0f

        globe.animate().alpha(1f).translationX(0f)
            .setStartDelay(80).setDuration(480)
            .setInterpolator(DecelerateInterpolator()).start()

        apk.animate().alpha(1f).translationX(0f)
            .setStartDelay(220).setDuration(480)
            .setInterpolator(DecelerateInterpolator()).start()

        arrow.animate().alpha(1f).scaleX(1f).scaleY(1f)
            .setStartDelay(460).setDuration(360)
            .setInterpolator(OvershootInterpolator())
            .withEndAction { startArrowLoop() }.start()

        fadeUp(binding.titleText, 520)
        fadeUp(binding.subtitleText, 620)
        fadeUp(binding.signInButton, 740)
        fadeUp(binding.termsText, 840)
    }

    private fun fadeUp(view: View, delay: Long) {
        view.translationY = 40f
        view.animate().alpha(1f).translationY(0f)
            .setStartDelay(delay).setDuration(460)
            .setInterpolator(DecelerateInterpolator()).start()
    }

    /** Subtle, infinite left-right nudge on the arrow. */
    private fun startArrowLoop() {
        if (isFinishing || isDestroyed) return
        val arrow = binding.heroArrow
        arrowAnimator?.cancel()
        arrowAnimator = ValueAnimator.ofFloat(0f, 10f, 0f).apply {
            duration = 1100
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { va ->
                if (this@AuthActivity.isFinishing || this@AuthActivity.isDestroyed) return@addUpdateListener
                arrow.translationX = va.animatedValue as Float
            }
            start()
        }
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
                        toast(messageForCredentialError(e))
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
                toast(messageForFirebaseError(task.exception))
            }
        }
    }

    /** Maps a Credential Manager error to the right user-facing message. */
    private fun messageForCredentialError(e: GetCredentialException): Int = when (e) {
        is GetCredentialCancellationException -> R.string.sign_in_cancelled
        is NoCredentialException -> R.string.sign_in_no_account
        is GetCredentialInterruptedException ->
            if (isOffline()) R.string.sign_in_no_network else R.string.sign_in_unable
        else -> {
            Log.w(TAG, "getCredential failed: ${e.javaClass.simpleName}: ${e.message}")
            if (isOffline()) R.string.sign_in_no_network else R.string.sign_in_failed
        }
    }

    /** Maps a Firebase sign-in failure to the right user-facing message. */
    private fun messageForFirebaseError(e: Exception?): Int = when {
        isOffline() -> R.string.sign_in_no_network
        e is FirebaseNetworkException -> R.string.sign_in_no_network
        e is FirebaseAuthInvalidUserException &&
            e.errorCode == "ERROR_USER_DISABLED" -> R.string.sign_in_account_disabled
        e is FirebaseTooManyRequestsException -> R.string.sign_in_server
        e is FirebaseAuthException -> R.string.sign_in_unable
        e is FirebaseException -> R.string.sign_in_server
        else -> R.string.sign_in_unknown
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

        // Spinner sits centered on the button; hide the button's label + logo
        // while it spins so nothing shifts and the button keeps its size/color.
        binding.authProgress.visibility = if (loading) View.VISIBLE else View.GONE
        binding.signInButton.text = if (loading) "" else getString(R.string.sign_in_with_google)
        binding.signInButton.icon =
            if (loading) null else ContextCompat.getDrawable(this, R.drawable.ic_google_logo)
        // Keep the filled look (don't grey it out); re-entry is blocked by signInInProgress.
        binding.signInButton.isClickable = !loading
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
        arrowAnimator?.cancel()
        arrowAnimator = null
        super.onDestroy()
    }

    private companion object {
        private const val TAG = "AuthActivity"
        private const val PRIVACY_URL = "https://sites.google.com/view/webcraft-privacy"
        private const val TERMS_URL = "https://sites.google.com/view/webcraft-terms"
        private const val HELP_URL = "https://sites.google.com/view/webcraft-help"
    }
}
