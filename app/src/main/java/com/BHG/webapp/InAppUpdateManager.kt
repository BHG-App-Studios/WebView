package com.BHG.webapp

import android.app.Activity
import android.content.IntentSender
import android.util.Log
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.play.core.appupdate.AppUpdateInfo
import com.google.android.play.core.appupdate.AppUpdateManager
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.UpdateAvailability

/**
 * Drives Google Play **immediate** in-app updates for a single activity.
 *
 * Immediate updates present a full-screen, Play-owned blocking UI and Play itself
 * restarts the app once the download completes — so there is no download-progress
 * listener and no manual `completeUpdate()` to manage. This helper only has to:
 *   1. offer the update on launch ([checkForUpdate]), and
 *   2. re-enter the flow if the user backgrounded the app mid-update
 *      ([resumeIfInProgress]), so the app can never be left half-updated.
 *
 * Design guarantees:
 *  - **Crash-proof.** Every Play Core entry point is wrapped; a device with no Play
 *    Store, an outdated Play Store, or a transient RPC failure degrades to "no
 *    update offered" instead of throwing into the activity.
 *  - **Thread-safe.** Play Core delivers all task callbacks on the main thread, and
 *    this class touches nothing off it; results are ignored once the activity is
 *    finishing or destroyed, so no work races a torn-down window.
 *  - **Lifecycle-correct.** The Activity Result launcher is registered in the
 *    constructor, which the host invokes from `onCreate` — before the activity is
 *    STARTED, as the Activity Result API requires.
 */
class InAppUpdateManager(private val activity: AppCompatActivity) {

    private val manager: AppUpdateManager? = try {
        AppUpdateManagerFactory.create(activity.applicationContext)
    } catch (e: Throwable) {
        Log.w(TAG, "AppUpdateManager unavailable: ${e.message}")
        null
    }

    /**
     * Registered unconditionally at construction (i.e. during the host's onCreate),
     * as required by the Activity Result contract. For an immediate update a
     * non-OK code means the user dismissed it or the flow failed; [resumeIfInProgress]
     * will re-offer it on the next foreground.
     */
    private val launcher: ActivityResultLauncher<IntentSenderRequest> =
        activity.registerForActivityResult(
            ActivityResultContracts.StartIntentSenderForResult()
        ) { result ->
            if (result.resultCode != Activity.RESULT_OK) {
                Log.w(TAG, "Immediate update flow not completed: code=${result.resultCode}")
            }
        }

    /** Call from `Activity.onCreate` (after `setContentView`) to offer an update. */
    fun checkForUpdate() {
        val mgr = manager ?: return
        try {
            mgr.appUpdateInfo
                .addOnSuccessListener { info ->
                    if (activity.isFinishing || activity.isDestroyed) return@addOnSuccessListener
                    val available = info.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE
                    if (available && info.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE)) {
                        startImmediate(mgr, info)
                    }
                }
                .addOnFailureListener { e -> Log.w(TAG, "appUpdateInfo failed: ${e.message}") }
        } catch (e: Throwable) {
            Log.w(TAG, "checkForUpdate error: ${e.message}")
        }
    }

    /**
     * Call from `Activity.onResume`. If an immediate update was already accepted but
     * the user left before it finished, Play reports
     * [UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS]; we re-enter the
     * full-screen flow so the update always runs to completion.
     */
    fun resumeIfInProgress() {
        val mgr = manager ?: return
        try {
            mgr.appUpdateInfo
                .addOnSuccessListener { info ->
                    if (activity.isFinishing || activity.isDestroyed) return@addOnSuccessListener
                    if (info.updateAvailability() ==
                        UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS
                    ) {
                        startImmediate(mgr, info)
                    }
                }
                .addOnFailureListener { e -> Log.w(TAG, "resume appUpdateInfo failed: ${e.message}") }
        } catch (e: Throwable) {
            Log.w(TAG, "resumeIfInProgress error: ${e.message}")
        }
    }

    private fun startImmediate(mgr: AppUpdateManager, info: AppUpdateInfo) {
        try {
            mgr.startUpdateFlowForResult(
                info,
                launcher,
                AppUpdateOptions.newBuilder(AppUpdateType.IMMEDIATE).build()
            )
        } catch (e: IntentSender.SendIntentException) {
            Log.w(TAG, "startUpdateFlow SendIntentException: ${e.message}")
        } catch (e: Throwable) {
            Log.w(TAG, "startUpdateFlow error: ${e.message}")
        }
    }

    private companion object {
        private const val TAG = "InAppUpdateManager"
    }
}
