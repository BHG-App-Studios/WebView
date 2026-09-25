package com.BHG.webapp

import android.os.Bundle
import android.util.Base64
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import com.BHG.webapp.databinding.SheetKeystoreCreateBinding
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

/**
 * Form that collects the distinguished-name fields and credentials, generates a
 * keystore on-device via [KeystoreGenerator], and returns the bytes (base64) plus
 * credentials to the build wizard through the Fragment Result API. The wizard then
 * treats it like any uploaded custom keystore.
 */
class KeystoreCreateSheet : BottomSheetDialogFragment() {

    private var _binding: SheetKeystoreCreateBinding? = null
    private val binding get() = _binding!!

    private var generating = false

    override fun getTheme(): Int = R.style.ThemeOverlay_App_FloatingSheet

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = SheetKeystoreCreateBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // Paint the system navigation-bar area with the sheet surface so the
        // welded-to-bottom sheet has no transparent strip behind the nav bar.
        dialog?.window?.navigationBarColor =
            ContextCompat.getColor(requireContext(), R.color.nav_bar_bg)
        binding.aliasInput.setText(getString(R.string.keystore_create_alias_default))
        binding.sheetClose.setOnClickListener { if (!generating) dismiss() }
        binding.generateButton.setOnClickListener { onGenerate() }
    }

    private fun onGenerate() {
        if (generating) return

        val alias = binding.aliasInput.text?.toString()?.trim().orEmpty()
        val storePw = binding.storePwInput.text?.toString().orEmpty()
        val keyPwRaw = binding.keyPwInput.text?.toString().orEmpty()
        val keyPw = keyPwRaw.ifEmpty { storePw }
        val name = binding.nameInput.text?.toString()?.trim().orEmpty()
        val country = binding.countryInput.text?.toString()?.trim().orEmpty()

        // Validation mirrors keytool's own constraints.
        if (alias.isEmpty()) { showError(binding.aliasLayout, R.string.keystore_create_alias_required); return }
        if (storePw.length < 6) { showError(binding.storePwLayout, R.string.keystore_create_pw_short); return }
        if (name.isEmpty()) { showError(binding.nameLayout, R.string.keystore_create_name_required); return }
        if (country.isNotEmpty() && country.length != 2) {
            showError(binding.countryLayout, R.string.keystore_create_country_len); return
        }
        clearErrors()

        val details = KeystoreGenerator.Details(
            alias = alias,
            storePassword = storePw,
            keyPassword = keyPw,
            commonName = name,
            orgUnit = binding.orgUnitInput.text?.toString()?.trim().orEmpty(),
            org = binding.orgInput.text?.toString()?.trim().orEmpty(),
            locality = binding.cityInput.text?.toString()?.trim().orEmpty(),
            state = binding.stateInput.text?.toString()?.trim().orEmpty(),
            country = country.uppercase()
        )

        setGenerating(true)
        KeystoreGenerator.generate(
            details,
            onSuccess = { result ->
                if (_binding == null) return@generate
                val b64 = Base64.encodeToString(result.bytes, Base64.NO_WRAP)
                parentFragmentManager.setFragmentResult(
                    RESULT_KEY,
                    bundleOf(
                        ARG_B64 to b64,
                        ARG_ALIAS to result.alias,
                        ARG_STORE_PW to result.storePassword,
                        ARG_KEY_PW to result.keyPassword
                    )
                )
                dismiss()
            },
            onError = {
                if (_binding == null) return@generate
                setGenerating(false)
                binding.storePwLayout.error = getString(R.string.keystore_create_failed)
            }
        )
    }

    private fun setGenerating(on: Boolean) {
        generating = on
        binding.generateProgress.visibility = if (on) View.VISIBLE else View.GONE
        binding.generateButton.isEnabled = !on
        binding.generateButton.text =
            getString(if (on) R.string.keystore_create_generating else R.string.keystore_create_generate)
        isCancelable = !on
    }

    private fun showError(layout: com.google.android.material.textfield.TextInputLayout, resId: Int) {
        clearErrors()
        layout.error = getString(resId)
    }

    private fun clearErrors() {
        binding.aliasLayout.error = null
        binding.storePwLayout.error = null
        binding.nameLayout.error = null
        binding.countryLayout.error = null
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    companion object {
        const val TAG = "KeystoreCreateSheet"
        const val RESULT_KEY = "keystore_create_result"
        const val ARG_B64 = "keystore_b64"
        const val ARG_ALIAS = "alias"
        const val ARG_STORE_PW = "store_pw"
        const val ARG_KEY_PW = "key_pw"
    }
}
