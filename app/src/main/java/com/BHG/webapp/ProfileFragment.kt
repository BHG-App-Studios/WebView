package com.BHG.webapp

import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.BHG.webapp.databinding.FragmentProfileBinding
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Shows the signed-in user's Google profile and live build stats. Sign-out is
 * delegated to the host [MainActivity] so the whole back stack is cleared.
 */
class ProfileFragment : Fragment() {

    private var _binding: FragmentProfileBinding? = null
    private val binding get() = _binding!!

    private val auth: FirebaseAuth? by lazy { runCatching { FirebaseAuth.getInstance() }.getOrNull() }
    private val firestore: FirebaseFirestore? by lazy { runCatching { FirebaseFirestore.getInstance() }.getOrNull() }

    private var statsListener: ListenerRegistration? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentProfileBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val user = auth?.currentUser
        if (user == null) {
            (activity as? MainActivity)?.signOut()
            return
        }

        binding.profileName.text = user.displayName?.takeIf { it.isNotBlank() } ?: "—"
        binding.profileEmail.text = user.email ?: ""
        binding.profileUid.text = user.uid

        binding.topBarMenu.setOnClickListener { (activity as? MainActivity)?.openDrawer() }

        loadAvatar(user.photoUrl?.toString())
        loadStats(user.uid)

        binding.signOutButton.setOnClickListener {
            (activity as? MainActivity)?.signOut()
        }
    }

    private fun loadStats(uid: String) {
        val db = firestore ?: return
        statsListener = db.collection("users").document(uid)
            .collection("builds")
            .addSnapshotListener { snapshot, error ->
                if (_binding == null || error != null) return@addSnapshotListener
                val docs = snapshot?.documents.orEmpty()
                val total = docs.size
                val ready = docs.count { it.getString("status") == "READY" }
                binding.statTotal.text = total.toString()
                binding.statReady.text = ready.toString()
            }
    }

    /** Lightweight one-shot avatar loader — no image library dependency. */
    private fun loadAvatar(url: String?) {
        if (url.isNullOrEmpty()) return
        IO.execute {
            val bmp = try {
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 10_000
                    readTimeout = 10_000
                    instanceFollowRedirects = true
                }
                conn.inputStream.use { BitmapFactory.decodeStream(it) }
            } catch (e: Exception) {
                Log.w(TAG, "Avatar load failed: ${e.message}")
                null
            }
            if (bmp != null) {
                MAIN.post {
                    if (_binding != null) {
                        binding.profileAvatar.setImageBitmap(bmp)
                        binding.profileAvatar.imageTintList = null
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        statsListener?.remove()
        statsListener = null
        _binding = null
        super.onDestroyView()
    }

    private companion object {
        private const val TAG = "ProfileFragment"
        private val IO = Executors.newSingleThreadExecutor { r ->
            Thread(r, "ProfileIO").apply { isDaemon = true }
        }
        private val MAIN = Handler(Looper.getMainLooper())
    }
}
