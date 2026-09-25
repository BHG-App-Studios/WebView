package com.BHG.webapp

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
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
import java.io.ByteArrayOutputStream

/**
 * Full-screen logo creator. Collects a foreground image + size and a background
 * (color or image + size), previews the adaptive icon live, and — via
 * [IconRenderer] — packages the whole res/ icon set as a ZIP on "Use this logo".
 * The monochrome (themed) layer is generated automatically from the foreground;
 * there is deliberately no monochrome control in the UI.
 *
 * The design is tied to the site it was made for ([ARG_URL]) and persisted by
 * [LogoStore], so it survives leaving the sheet and restarting the app:
 *
 *  - opening the sheet restores the design saved for that URL, or the defaults
 *    when that site has none — never another site's logo;
 *  - "Use this logo" stores the generated ZIP + preview alongside the design and
 *    hands [HomeFragment] the stored paths through the Fragment Result API;
 *  - closing the sheet mid-edit keeps the tweaked design (only when it actually
 *    differs from what was on screen at open, so a plain open/close writes
 *    nothing);
 *  - "Remove logo" deletes both the design and the generated icons, so a
 *    removed logo does not come back after a restart.
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

    /** Design as it stood when the sheet opened — the diff base for the exit save. */
    private var baseline: LogoStore.Draft? = null

    /** Set once the user picks an image, so a slow restore can't overwrite that pick. */
    private var userPicked = false

    /** True while [applyDraft] writes the widgets, to collapse the re-renders. */
    private var applying = false

    /** Set when the design was already stored or deliberately removed. */
    private var skipExitSave = false

    private lateinit var pickFg: ActivityResultLauncher<String>
    private lateinit var pickBg: ActivityResultLauncher<String>

    /** Site this logo belongs to; the key [LogoStore] persists it under. */
    private val logoUrl: String get() = arguments?.getString(ARG_URL).orEmpty()

    override fun getTheme(): Int = R.style.Theme_WebsiteAppBuilder_FullScreenDialog

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pickFg = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null && _b != null) {
                fg = decode(uri)
                userPicked = true
                onFgPicked()
                refresh()
            }
        }
        pickBg = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null && _b != null) {
                bgImg = decode(uri)
                userPicked = true
                refresh()
            }
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
        loadInitialDesign()
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

    /**
     * Seeds the sheet: the bundled defaults first, so the preview is never blank,
     * then — a frame later and off the main thread — the design stored for this
     * URL, which replaces them. A site with nothing saved keeps the defaults;
     * another site's logo is deliberately not offered. Best-effort throughout:
     * anything unreadable simply leaves the defaults in place.
     */
    private fun loadInitialDesign() {
        loadDefaultImages()
        baseline = currentDraft()

        val appContext = requireContext().applicationContext
        val url = logoUrl
        Thread {
            val draft = runCatching { LogoStore.loadDraft(appContext, url) }.getOrNull()
            if (draft != null) {
                Handler(Looper.getMainLooper()).post {
                    // The sheet may already be gone, or the user may have picked
                    // an image while the read was in flight — never overwrite either.
                    if (_b == null || userPicked) return@post
                    applyDraft(draft)
                    baseline = currentDraft()
                    refresh()
                }
            }
        }.start()
    }

    /** Writes a stored design into the state and the controls in a single pass. */
    private fun applyDraft(d: LogoStore.Draft) {
        applying = true
        fg = d.fg
        bgImg = d.bgImage
        bgIsColor = d.bgIsColor
        bgColor = d.bgColor
        fgResize = d.fgResize
        bgResize = d.bgResize
        fgRotation = d.fgRotation

        // A stored design always has a foreground, and a background image too if
        // one was used, so both pickers read as "already chosen".
        b.fgPickButton.setText(R.string.logo_change_image)
        if (d.bgImage != null) b.bgPickButton.setText(R.string.logo_change_image)
        b.fgSizeSlider.value = fgResize.toFloat(); b.fgSizeValue.text = pct(fgResize)
        b.bgSizeSlider.value = bgResize.toFloat(); b.bgSizeValue.text = pct(bgResize)
        b.fgRotationSlider.value = fgRotation.toFloat(); b.fgRotationValue.text = deg(fgRotation)
        b.bgTypeToggle.check(if (bgIsColor) b.bgTypeColor.id else b.bgTypeImage.id)
        b.bgColorHexInput.setText(hex(bgColor))
        applyBgType()
        applying = false
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

    /**
     * The design currently on screen, or null when it is incomplete — no
     * foreground, or an image background with no image. Values always sit inside
     * the slider ranges, since [LogoStore] clamps them on the way out.
     */
    private fun currentDraft(): LogoStore.Draft? {
        val f = fg ?: return null
        if (!bgIsColor && bgImg == null) return null
        return LogoStore.Draft(f, bgImg, bgIsColor, bgColor, fgResize, bgResize, fgRotation)
    }

    /** Builds the current config for [IconRenderer], or null if inputs are missing. */
    private fun currentConfig(): IconRenderer.Config? {
        val d = currentDraft() ?: return null
        return IconRenderer.Config(
            foreground = d.fg,
            fgResizePct = d.fgResize,
            bgIsColor = d.bgIsColor,
            bgColor = d.bgColor,
            bgImage = d.bgImage,
            bgResizePct = d.bgResize,
            fgRotationDeg = d.fgRotation
        )
    }

    private fun refresh() {
        if (applying) return   // a bulk restore re-renders once, at the end
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
        val ctx = context ?: return null
        ctx.contentResolver.openInputStream(uri)?.use { input ->
            val bytes = input.readBytes()
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            val maxDim = 1024
            while (bounds.outWidth / sample > maxDim || bounds.outHeight / sample > maxDim) sample *= 2
            val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        }
    } catch (t: Throwable) {
        // A picker can hand back anything, including a file too large to decode.
        Log.w(TAG, "image decode failed", t)
        toast(R.string.logo_failed)
        null
    }

    /** Toast that survives the view going away between the pick and the result. */
    private fun toast(resId: Int) {
        val ctx = context ?: return
        Toast.makeText(ctx, resId, Toast.LENGTH_SHORT).show()
    }

    private fun onUse() {
        if (busy) return
        val draft = currentDraft()
        if (draft == null) {
            Toast.makeText(
                requireContext(),
                if (fg == null) R.string.logo_need_foreground else R.string.logo_need_background,
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        val cfg = currentConfig() ?: return

        setBusy(true)
        val appContext = requireContext().applicationContext
        val url = logoUrl
        val main = Handler(Looper.getMainLooper())
        Thread {
            try {
                val zip = IconRenderer.buildZip(cfg)
                val preview = ByteArrayOutputStream().use { os ->
                    IconRenderer.renderLegacy(cfg, PREVIEW_PX, "square")
                        .compress(Bitmap.CompressFormat.PNG, 100, os)
                    os.toByteArray()
                }

                // Store the icon set and the design that produced it. Nothing is
                // written to the cache: the paths handed back live in filesDir, so
                // both this build and the next launch read the same logo.
                val saved = LogoStore.commit(appContext, url, draft, zip, preview)

                main.post {
                    if (_b == null) return@post
                    if (saved == null) {
                        setBusy(false)
                        Toast.makeText(
                            context ?: return@post, R.string.logo_save_failed, Toast.LENGTH_SHORT
                        ).show()
                        return@post
                    }
                    skipExitSave = true   // the commit already stored this design
                    parentFragmentManager.setFragmentResult(
                        RESULT_KEY,
                        bundleOf(
                            ARG_ZIP_PATH to saved.zipPath,
                            ARG_PREVIEW_PATH to saved.previewPath
                        )
                    )
                    dismiss()
                }
            } catch (t: Throwable) {
                // Includes OutOfMemoryError from the icon bitmaps: a failed logo
                // must never take the app down with it.
                Log.w(TAG, "logo build failed", t)
                main.post {
                    if (_b == null) return@post
                    setBusy(false)
                    Toast.makeText(
                        context ?: return@post, R.string.logo_failed, Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }.start()
    }

    private fun sendRemoved() {
        // Removal has to stick, or the logo would reappear on the next launch.
        skipExitSave = true
        val appContext = context?.applicationContext
        if (appContext != null) {
            val url = logoUrl
            Thread { runCatching { LogoStore.clear(appContext, url) } }.start()
        }
        parentFragmentManager.setFragmentResult(RESULT_KEY, bundleOf(ARG_REMOVED to true))
        dismiss()
    }

    /**
     * Keeps the design the user left behind, so reopening the creator for this
     * site restores it. Skipped when the design is unchanged (a plain open/close
     * writes nothing) and when it was already committed or removed.
     */
    private fun saveDraftOnExit() {
        if (skipExitSave) return
        val appContext = context?.applicationContext ?: return
        val base = baseline ?: return
        val draft = currentDraft() ?: return
        if (!differs(draft, base)) return

        val url = logoUrl
        Thread { runCatching { LogoStore.saveDraft(appContext, url, draft) } }.start()
    }

    /** Field-by-field comparison; the images are compared by identity. */
    private fun differs(draft: LogoStore.Draft, base: LogoStore.Draft): Boolean =
        draft.fg !== base.fg || draft.bgImage !== base.bgImage ||
            draft.bgIsColor != base.bgIsColor || draft.bgColor != base.bgColor ||
            draft.fgResize != base.fgResize || draft.bgResize != base.bgResize ||
            draft.fgRotation != base.fgRotation

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

    override fun onDestroy() {
        saveDraftOnExit()
        super.onDestroy()
    }

    companion object {
        const val TAG = "LogoCreateFragment"

        /** Preview PNG handed to [HomeFragment] for the Step 1 card, in pixels. */
        private const val PREVIEW_PX = 192

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
