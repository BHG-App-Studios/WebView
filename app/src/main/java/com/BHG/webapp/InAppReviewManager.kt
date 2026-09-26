package com.BHG.webapp

import android.content.Context
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import com.google.android.play.core.review.ReviewManager
import com.google.android.play.core.review.ReviewManagerFactory
import com.google.firebase.firestore.FirebaseFirestore

/**
 * Drives Google Play's **In-App Review** flow for a single activity.
 *
 * The Play-owned review card is only offered once the user has something worth
 * reviewing: [requestReviewIfEligible] first confirms the signed-in user has at
 * least one **READY** build in Firestore, and only then asks Play to show the
 * card. Play itself decides whether to actually display it (it is quota-limited
 * and will silently no-op when the quota is spent), and never tells us whether a
 * review was left — so the flow is fire-and-forget.
 *
 * Design guarantees (mirroring [InAppUpdateManager]):
 *  - **Crash-proof.** Every Play Core and Firestore entry point is wrapped; a
 *    device with no/outdated Play Store, or a transient RPC failure, degrades to
 *    "no card shown" instead of throwing into the activity.
 *  - **Thread-safe.** Play Core and Firestore deliver their task callbacks on the
 *    main thread; this class touches nothing off it and drops every callback once
 *    the activity is finishing or destroyed, so no work races a torn-down window.
 *  - **Not spammy.** At most one attempt per process ([attemptedThisSession]) and,
 *    across launches, no sooner than [MIN_INTERVAL_MS] since the last attempt.
 *    This sits on top of Play's own quota, never fighting it.
 */
class InAppReviewManager(private val activity: AppCompatActivity) {

    private val manager: ReviewManager? = try {
        ReviewManagerFactory.create(activity.applicationContext)
    } catch (e: Throwable) {
        Log.w(TAG, "ReviewManager unavailable: ${e.message}")
        null
    }

    /** One attempt per process, regardless of how many times onResume fires. */
    private var attemptedThisSession = false

    /**
     * Offer the review card if the user is eligible. Safe to call on every
     * foreground — the session flag and persisted cooldown keep it from firing
     * more than intended.
     *
     * @param firestore the app's Firestore instance (null → no-op)
     * @param uid       the signed-in user's uid (null/blank → no-op)
     */
    fun requestReviewIfEligible(firestore: FirebaseFirestore?, uid: String?) {
        val mgr = manager ?: return
        val db = firestore ?: return
        if (uid.isNullOrEmpty()) return
        if (attemptedThisSession) return
        if (!cooldownElapsed()) return
        attemptedThisSession = true

        try {
            // Eligibility: at least one build that finished successfully. limit(1)
            // keeps this to a single-doc read.
            db.collection("users").document(uid)
                .collection("builds")
                .whereEqualTo("status", "READY")
                .limit(1)
                .get()
                .addOnSuccessListener { snapshot ->
                    if (activity.isFinishing || activity.isDestroyed) return@addOnSuccessListener
                    if (snapshot.isEmpty) return@addOnSuccessListener   // no READY build yet
                    launchReviewFlow(mgr)
                }
                .addOnFailureListener { e -> Log.w(TAG, "eligibility query failed: ${e.message}") }
        } catch (e: Throwable) {
            Log.w(TAG, "requestReviewIfEligible error: ${e.message}")
        }
    }

    private fun launchReviewFlow(mgr: ReviewManager) {
        try {
            mgr.requestReviewFlow()
                .addOnCompleteListener { request ->
                    if (activity.isFinishing || activity.isDestroyed) return@addOnCompleteListener
                    if (!request.isSuccessful) {
                        Log.w(TAG, "requestReviewFlow failed: ${request.exception?.message}")
                        return@addOnCompleteListener
                    }
                    val info = request.result ?: return@addOnCompleteListener
                    try {
                        mgr.launchReviewFlow(activity, info)
                            .addOnCompleteListener {
                                // Play never reports whether a review was left or even
                                // shown; we only record that an attempt happened so the
                                // cooldown starts, then carry on with the app flow.
                                markAttempted()
                            }
                    } catch (e: Throwable) {
                        Log.w(TAG, "launchReviewFlow error: ${e.message}")
                    }
                }
        } catch (e: Throwable) {
            Log.w(TAG, "requestReviewFlow error: ${e.message}")
        }
    }

    // ---- Persisted cooldown --------------------------------------------------

    private fun prefs() =
        activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun cooldownElapsed(): Boolean {
        val last = try { prefs().getLong(KEY_LAST_ATTEMPT, 0L) } catch (e: Throwable) { 0L }
        return System.currentTimeMillis() - last >= MIN_INTERVAL_MS
    }

    private fun markAttempted() {
        try {
            prefs().edit().putLong(KEY_LAST_ATTEMPT, System.currentTimeMillis()).apply()
        } catch (e: Throwable) {
            Log.w(TAG, "markAttempted failed: ${e.message}")
        }
    }

    private companion object {
        private const val TAG = "InAppReviewManager"
        private const val PREFS = "in_app_review"
        private const val KEY_LAST_ATTEMPT = "last_attempt_ms"

        /** Don't re-attempt sooner than this across launches (14 days). */
        private const val MIN_INTERVAL_MS = 14L * 24 * 60 * 60 * 1000
    }
}
