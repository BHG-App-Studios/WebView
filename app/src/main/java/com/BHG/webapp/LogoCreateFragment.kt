package com.BHG.webapp

import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.os.bundleOf
import androidx.fragment.app.DialogFragment
import com.BHG.webapp.databinding.FragmentLogoCreateBinding
import java.io.File

/**
 * Full-screen logo creator. Collects a foreground image + size and a background
 * (color or image + size), previews the adaptive icon live, and — via
 * [IconRenderer] — packages the whole res/ icon set as a ZIP on "Use this logo".
 * The monochrome (themed) layer is generated automatically from the foreground;
 * there is deliberately no monochrome control in the UI.
 *
 * Returns to [HomeFragment] through the Fragment Result API: paths to the
 * generated ZIP and a preview PNG in the cache dir (bytes are read at build
 * time to keep the result bundle small), or a "removed" flag.
 */
class LogoCreateFragment : DialogFragment() {

    private var _b: FragmentLogoCreateBinding? = null
    private val b get() = _b!!

    private var fg: Bitmap? = null
    private var bgImg: Bitmap? = null
    private var bgIsColor = true
    private var bgColor = Color.parseColor("#FAFAFA")
    private var fgResize = 69
    private var fgRotation = 0
    private var bgResize = 100
    private var busy = false

    private lateinit var pickFg: ActivityResultLauncher<String>
    private lateinit var pickBg: ActivityResultLauncher<String>

    override fun getTheme(): Int = R.style.Theme_WebsiteAppBuilder_FullScreenDialog

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pickFg = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) { fg = decode(uri); onFgPicked(); refresh() }
        }
        pickBg = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) { bgImg = decode(uri); refresh() }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _b = FragmentLogoCreateBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        loadDefaultImages()
        b.logoClose.setOnClickListener { if (!busy) dismiss() }
        b.logoReset.setOnClickListener { if (!busy) resetToDefaults() }
        b.fgPickButton.setOnClickListener { pickFg.launch("image/*") }
        b.bgPickButton.setOnClickListener { pickBg.launch("image/*") }

        b.fgSizeSlider.value = fgResize.toFloat()
        b.fgSizeValue.text = pct(fgResize)
        b.fgSizeSlider.addOnChangeListener { _, v, _ ->
            fgResize = v.toInt(); b.fgSizeValue.text = pct(fgResize); refresh()
        }
        b.bgSizeSlider.value = bgResize.toFloat()
        b.bgSizeValue.text = pct(bgResize)
        b.bgSizeSlider.addOnChangeListener { _, v, _ ->
            bgResize = v.toInt(); b.bgSizeValue.text = pct(bgResize); refresh()
        }

        b.fgRotationSlider.value = fgRotation.toFloat()
        b.fgRotationValue.text = deg(fgRotation)
        b.fgRotationSlider.addOnChangeListener { _, v, _ ->
            fgRotation = v.toInt(); b.fgRotationValue.text = deg(fgRotation); refresh()
        }
        b.fgRotateMinus.setOnClickListener { nudgeRotation(-1) }
        b.fgRotatePlus.setOnClickListener { nudgeRotation(1) }

        b.bgTypeToggle.check(if (bgIsColor) b.bgTypeColor.id else b.bgTypeImage.id)
        b.bgTypeToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) { bgIsColor = checkedId == b.bgTypeColor.id; applyBgType(); refresh() }
        }

        b.bgColorHexInput.setText(hex(bgColor))
        b.bgColorHexInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b2: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b2: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { parseHex(s?.toString()) }
        })
        // The swatch and the field's trailing icon both open the real picker.
        b.bgColorSwatchCard.setOnClickListener { openColorPicker() }
        b.bgColorHexLayout.setEndIconOnClickListener { openColorPicker() }

        b.useLogoButton.setOnClickListener { onUse() }
        b.removeLogoButton.setOnClickListener { sendRemoved() }
        if (arguments?.getBoolean(ARG_EDITING) == true) b.removeLogoButton.visibility = View.VISIBLE

        applyBgType()
        refresh()
    }

    /** After the first foreground pick, relabel the picker to "Change image". */
    private fun onFgPicked() {
        b.fgPickButton.setText(R.string.logo_change_image)
    }

    /**
     * Preselect the bundled default foreground/background so the creator opens
     * with a ready-made icon instead of empty pickers. Only runs when the user
     * hasn't already chosen images (e.g. before a config restore).
     */
    private fun loadDefaultImages() {
        if (fg == null) {
            fg = android.graphics.BitmapFactory.decodeResource(resources, R.drawable.default_fore)
            if (fg != null) { onFgPicked() }
        }
        if (bgImg == null) {
            bgImg = android.graphics.BitmapFactory.decodeResource(resources, R.drawable.default_bg)
            if (bgImg != null) {
                bgIsColor = false
                b.bgPickButton.setText(R.string.logo_change_image)
            }
        }
    }

    private fun applyBgType() {
        b.bgColorRow.visibility = if (bgIsColor) View.VISIBLE else View.GONE
        b.bgImageRow.visibility = if (bgIsColor) View.GONE else View.VISIBLE
    }

    /** Toolbar reset: restore the bundled default images and all default settings. */
    private fun resetToDefaults() {
        fg = null
        bgImg = null
        bgIsColor = true
        bgColor = Color.parseColor("#FAFAFA")
        fgResize = 69
        bgResize = 100
        fgRotation = 0
        loadDefaultImages()   // repopulates fg/bg from defaults, sets bgIsColor=false

        b.fgSizeSlider.value = fgResize.toFloat(); b.fgSizeValue.text = pct(fgResize)
        b.bgSizeSlider.value = bgResize.toFloat(); b.bgSizeValue.text = pct(bgResize)
        b.fgRotationSlider.value = fgRotation.toFloat(); b.fgRotationValue.text = deg(fgRotation)
        b.bgColorHexInput.setText(hex(bgColor))
        b.bgTypeToggle.check(if (bgIsColor) b.bgTypeColor.id else b.bgTypeImage.id)
        applyBgType()
        refresh()
        Toast.makeText(requireContext(), R.string.logo_reset_done, Toast.LENGTH_SHORT).show()
    }

    private fun parseHex(raw: String?) {
        var v = raw?.trim().orEmpty()
        if (v.isEmpty()) return
        if (!v.startsWith("#")) v = "#$v"
        if (Regex("^#[0-9a-fA-F]{6}$").matches(v)) {
            bgColor = Color.parseColor(v)
            refresh()
        }
    }

    private fun setColor(hexStr: String) {
        bgColor = Color.parseColor(hexStr)
        b.bgColorHexInput.setText(hexStr.uppercase())
        b.bgColorHexInput.setSelection(b.bgColorHexInput.text?.length ?: 0)
        refresh()
    }

    private fun dp(v: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics
    ).toInt()

    private fun hex(color: Int): String = String.format("#%06X", 0xFFFFFF and color)

    /** Builds the current config, or null if required inputs are missing. */
    private fun currentConfig(): IconRenderer.Config? {
        val f = fg ?: return null
        if (!bgIsColor && bgImg == null) return null
        return IconRenderer.Config(
            foreground = f,
            fgResizePct = fgResize,
            bgIsColor = bgIsColor,
            bgColor = bgColor,
            bgImage = bgImg,
            bgResizePct = bgResize,
            fgRotationDeg = fgRotation
        )
    }

    private fun refresh() {
        b.bgColorSwatch.setBackgroundColor(bgColor)

        // Separate layer previews so each choice is visible on its own.
        val f = fg
        if (f != null) b.fgPreview.setImageBitmap(IconRenderer.previewForeground(f, fgResize, 216, fgRotation))
        else b.fgPreview.setImageDrawable(null)

        if (!bgIsColor) {
            b.bgPreview.setImageBitmap(
                IconRenderer.previewBackground(false, bgColor, bgImg, bgResize, 216)
            )
        }

        // Composite adaptive icon in both mask shapes.
        val cfg = currentConfig()
        if (cfg == null) {
            b.previewRound.setImageDrawable(null)
            b.previewCircle.setImageDrawable(null)
            return
        }
        b.previewRound.setImageBitmap(IconRenderer.renderLegacy(cfg, 216, "square"))
        b.previewCircle.setImageBitmap(IconRenderer.renderLegacy(cfg, 216, "circle"))
    }

    private fun pct(v: Int): String = "$v%"

    private fun deg(v: Int): String = "$v°"

    /** Steps rotation by [delta]°, wrapping to the slider's -180..180 range. */
    private fun nudgeRotation(delta: Int) {
        var r = fgRotation + delta
        r = ((r + 180) % 360 + 360) % 360 - 180
        b.fgRotationSlider.value = r.toFloat()   // fires the listener → updates state + preview
    }

    /** Opens the real HSV colour picker seeded with the current background colour. */
    private fun openColorPicker() {
        val ctx = requireContext()
        val pad = dp(20)
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, dp(8), pad, 0)
        }

        val picker = ColorPickerView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(260)
            )
            setColor(bgColor)
        }
        val hexField = android.widget.EditText(ctx).apply {
            setText(hex(bgColor))
            setSingleLine()
            filters = arrayOf(android.text.InputFilter.LengthFilter(7))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12) }
        }
        container.addView(picker)
        container.addView(hexField)

        var chosen = bgColor
        var syncing = false
        picker.onColorChanged = { c ->
            chosen = c
            if (!syncing) { syncing = true; hexField.setText(hex(c)); syncing = false }
        }
        hexField.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b2: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b2: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (syncing) return
                var v = s?.toString()?.trim().orEmpty()
                if (!v.startsWith("#")) v = "#$v"
                if (Regex("^#[0-9a-fA-F]{6}$").matches(v)) {
                    chosen = Color.parseColor(v)
                    syncing = true; picker.setColor(chosen); syncing = false
                }
            }
        })

        com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.logo_color_picker_title)
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ -> setColor(hex(chosen)) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun decode(uri: Uri): Bitmap? = try {
        requireContext().contentResolver.openInputStream(uri)?.use { input ->
            val bytes = input.readBytes()
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            val maxDim = 1024
            while (bounds.outWidth / sample > maxDim || bounds.outHeight / sample > maxDim) sample *= 2
            val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        }
    } catch (e: Exception) {
        Toast.makeText(requireContext(), R.string.logo_failed, Toast.LENGTH_SHORT).show()
        null
    }

    private fun onUse() {
        if (busy) return
        if (fg == null) { Toast.makeText(requireContext(), R.string.logo_need_foreground, Toast.LENGTH_SHORT).show(); return }
        if (!bgIsColor && bgImg == null) { Toast.makeText(requireContext(), R.string.logo_need_background, Toast.LENGTH_SHORT).show(); return }
        val cfg = currentConfig() ?: return

        setBusy(true)
        val cacheDir = requireContext().cacheDir
        val main = Handler(Looper.getMainLooper())
        Thread {
            try {
                val zip = IconRenderer.buildZip(cfg)
                val zipFile = File(cacheDir, "logo_icons.zip")
                zipFile.writeBytes(zip)

                val previewFile = File(cacheDir, "logo_preview.png")
                previewFile.outputStream().use { os ->
                    IconRenderer.renderLegacy(cfg, 192, "square")
                        .compress(Bitmap.CompressFormat.PNG, 100, os)
                }

                // Hand HomeFragment the freshly built files for this build. Nothing
                // is persisted — the selection lives only for the current session.
                main.post {
                    if (_b == null) return@post
                    parentFragmentManager.setFragmentResult(
                        RESULT_KEY,
                        bundleOf(
                            ARG_ZIP_PATH to zipFile.absolutePath,
                            ARG_PREVIEW_PATH to previewFile.absolutePath
                        )
                    )
                    dismiss()
                }
            } catch (e: Exception) {
                main.post {
                    if (_b == null) return@post
                    setBusy(false)
                    Toast.makeText(requireContext(), R.string.logo_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun sendRemoved() {
        parentFragmentManager.setFragmentResult(RESULT_KEY, bundleOf(ARG_REMOVED to true))
        dismiss()
    }

    private fun setBusy(on: Boolean) {
        busy = on
        b.logoProgress.visibility = if (on) View.VISIBLE else View.GONE
        b.useLogoButton.isEnabled = !on
        b.useLogoButton.text = getString(if (on) R.string.logo_generating else R.string.logo_use)
        isCancelable = !on
    }

    override fun onDestroyView() {
        _b = null
        super.onDestroyView()
    }

    companion object {
        const val TAG = "LogoCreateFragment"
        const val RESULT_KEY = "logo_create_result"
        const val ARG_ZIP_PATH = "zip_path"
        const val ARG_PREVIEW_PATH = "preview_path"
        const val ARG_REMOVED = "removed"
        const val ARG_EDITING = "editing"
        const val ARG_URL = "url"

        fun newInstance(editing: Boolean, url: String): LogoCreateFragment =
            LogoCreateFragment().apply { arguments = bundleOf(ARG_EDITING to editing, ARG_URL to url) }
    }
}

