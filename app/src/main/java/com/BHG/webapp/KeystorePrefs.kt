package com.BHG.webapp

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Encrypted, on-device storage for custom-keystore credentials the user asks us
 * to remember. This is the "Remember on this device" checkbox.
 *
 * Security intent:
 *  - Stored ONLY on the device, encrypted at rest via [EncryptedSharedPreferences]
 *    (AES-256, key held in the Android Keystore).
 *  - NEVER sent to Firestore or persisted on the server. The passwords still
 *    travel to the Worker at build time over HTTPS (the signing happens in CI),
 *    but nothing here is written server-side.
 *  - Only the passwords + alias are remembered, not the keystore file itself —
 *    the user re-picks the .jks each build, so a stolen device without the file
 *    cannot sign anything.
 *
 * All access is wrapped in try/catch: on the rare device where the AndroidX
 * security provider fails to initialise, remembering silently degrades to
 * "not remembered" rather than crashing the build flow.
 */
class KeystorePrefs(context: Context) {

    private val prefs: SharedPreferences? = runCatching {
        val masterKey = MasterKey.Builder(context.applicationContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context.applicationContext,
            FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }.getOrElse {
        Log.w(TAG, "EncryptedSharedPreferences unavailable: ${it.message}")
        null
    }

    data class Credentials(val storePassword: String, val keyAlias: String, val keyPassword: String)

    /** Persist credentials (encrypted). No-op if secure storage is unavailable. */
    fun save(creds: Credentials) {
        val p = prefs ?: return
        runCatching {
            p.edit()
                .putString(KEY_STORE_PW, creds.storePassword)
                .putString(KEY_ALIAS, creds.keyAlias)
                .putString(KEY_KEY_PW, creds.keyPassword)
                .apply()
        }.onFailure { Log.w(TAG, "save failed: ${it.message}") }
    }

    /** Returns remembered credentials, or null if none/unavailable. */
    fun load(): Credentials? {
        val p = prefs ?: return null
        return runCatching {
            val store = p.getString(KEY_STORE_PW, null) ?: return null
            val alias = p.getString(KEY_ALIAS, null) ?: return null
            val keyPw = p.getString(KEY_KEY_PW, null) ?: store
            Credentials(store, alias, keyPw)
        }.getOrNull()
    }

    /** Forget any remembered credentials (checkbox unticked). */
    fun clear() {
        val p = prefs ?: return
        runCatching { p.edit().clear().apply() }
    }

    private companion object {
        private const val TAG = "KeystorePrefs"
        private const val FILE_NAME = "keystore_creds_secure"
        private const val KEY_STORE_PW = "store_password"
        private const val KEY_ALIAS = "key_alias"
        private const val KEY_KEY_PW = "key_password"
    }
}
