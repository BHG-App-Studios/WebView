package com.BHG.webapp

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import com.BHG.webapp.databinding.SheetDownloadOptionsBinding
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

/**
 * Lets the user choose which artifact to download for a ready build — the APK
 * or the AAB — with a short explanation of each. The APK copy is tailored to
 * the build type (a release APK is shareable/production-like but off-Play; a
 * debug APK is bigger, slower, test-only). Returns the choice ("apk"/"aab") to
 * the host via the Fragment Result API; the host performs the actual download.
 */
class DownloadOptionsSheet : BottomSheetDialogFragment() {

    private var _binding: SheetDownloadOptionsBinding? = null
    private val binding get() = _binding!!

    override fun getTheme(): Int = R.style.ThemeOverlay_App_FloatingSheet

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = SheetDownloadOptionsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val hasApk = arguments?.getBoolean(ARG_HAS_APK) ?: false
        val hasAab = arguments?.getBoolean(ARG_HAS_AAB) ?: false
        val isRelease = arguments?.getString(ARG_BUILD_TYPE) == "release"

        binding.apkDesc.setText(
            if (isRelease) R.string.download_apk_desc_release
            else R.string.download_apk_desc_debug
        )

        binding.apkOption.visibility = if (hasApk) View.VISIBLE else View.GONE
        binding.aabOption.visibility = if (hasAab) View.VISIBLE else View.GONE

        binding.sheetClose.setOnClickListener { dismiss() }
        binding.apkOption.setOnClickListener { choose(CHOICE_APK) }
        binding.aabOption.setOnClickListener { choose(CHOICE_AAB) }
    }

    private fun choose(choice: String) {
        parentFragmentManager.setFragmentResult(RESULT_KEY, bundleOf(ARG_CHOICE to choice))
        dismiss()
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    companion object {
        const val TAG = "DownloadOptionsSheet"
        const val RESULT_KEY = "download_options_result"
        const val ARG_CHOICE = "choice"
        const val CHOICE_APK = "apk"
        const val CHOICE_AAB = "aab"

        private const val ARG_HAS_APK = "has_apk"
        private const val ARG_HAS_AAB = "has_aab"
        private const val ARG_BUILD_TYPE = "build_type"

        fun newInstance(hasApk: Boolean, hasAab: Boolean, buildType: String) =
            DownloadOptionsSheet().apply {
                arguments = bundleOf(
                    ARG_HAS_APK to hasApk,
                    ARG_HAS_AAB to hasAab,
                    ARG_BUILD_TYPE to buildType
                )
            }
    }
}
