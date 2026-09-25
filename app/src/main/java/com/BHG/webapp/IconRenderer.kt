package com.BHG.webapp

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Native port of tools/android-icon-generator.html. Renders an Android adaptive
 * launcher icon (background + foreground) plus the legacy masked icons and an
 * auto-computed monochrome silhouette, then packages them as a ZIP whose paths
 * mirror app/src/main/ — the build runner extracts it straight into the project.
 *
 * Monochrome is intentionally automatic (foreground turned into a black
 * silhouette with its alpha kept); Android tints it at runtime for themed icons.
 * Artwork with no shape to silhouette — a photo — is reduced to one colour from
 * its own tones instead, so the layer is always a shape rather than the solid
 * black block a naive silhouette would make of it. See [monoKind].
 */
object IconRenderer {

    /** User choices captured by [LogoCreateFragment]. Sizes are percentages 1..200. */
    data class Config(
        val foreground: Bitmap,
        val fgResizePct: Int,
        val bgIsColor: Boolean,
        val bgColor: Int,
        val bgImage: Bitmap?,
        val bgResizePct: Int,
        val fgRotationDeg: Int = 0,
        val name: String = "ic_launcher"
    )

    // Density → pixel size. Adaptive layers use the 108dp canvas; legacy icons 48dp.
    private val ADAPTIVE = linkedMapOf(
        "mdpi" to 108, "hdpi" to 162, "xhdpi" to 216, "xxhdpi" to 324, "xxxhdpi" to 432
    )
    private val LEGACY = linkedMapOf(
        "mdpi" to 48, "hdpi" to 72, "xhdpi" to 96, "xxhdpi" to 144, "xxxhdpi" to 192
    )
    private const val VIEW_RATIO = 72f / 108f       // legacy viewport within the 108dp canvas

    // Monochrome layer. A silhouette whose opaque pixels fill their own bounding
    // box this completely is a plain rectangle — the outline of the image file,
    // not of any artwork — so the layer is derived from the icon's tones instead.
    private const val MONO_FLAT_FILL = 0.99
    private const val MONO_ALPHA_MIN = 250          // "opaque" when measuring a silhouette
    private const val MONO_MIN_SPAN = 16            // tonal range below which artwork is flat
    private const val MONO_PROBE = 216              // px, canvas for the derivation test

    private fun newCanvas(size: Int): Bitmap =
        Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)

    /** Draws [src] centered on a [size]×[size] area; its longest side = size*pct/100.
     *  [rotationDeg] spins the image about the canvas centre. */
    private fun drawFitted(canvas: Canvas, src: Bitmap, size: Int, pct: Int, rotationDeg: Float = 0f) {
        val target = size * pct / 100f
        val scale = target / max(src.width, src.height)
        val w = src.width * scale
        val h = src.height * scale
        val left = (size - w) / 2f
        val top = (size - h) / 2f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = true }
        val save = canvas.save()
        if (rotationDeg != 0f) canvas.rotate(rotationDeg, size / 2f, size / 2f)
        canvas.drawBitmap(src, null, RectF(left, top, left + w, top + h), paint)
        canvas.restoreToCount(save)
    }

    fun renderForeground(cfg: Config, size: Int): Bitmap {
        val bmp = newCanvas(size)
        drawFitted(Canvas(bmp), cfg.foreground, size, cfg.fgResizePct, cfg.fgRotationDeg.toFloat())
        return bmp
    }

    fun renderBackground(cfg: Config, size: Int): Bitmap {
        val bmp = newCanvas(size)
        val c = Canvas(bmp)
        if (cfg.bgIsColor) {
            c.drawColor(cfg.bgColor)
        } else if (cfg.bgImage != null) {
            drawFitted(c, cfg.bgImage, size, cfg.bgResizePct)
        }
        return bmp
    }

    /** Adaptive composite = background + foreground on the full 108dp canvas. */
    fun renderComposite(cfg: Config, size: Int): Bitmap {
        val bmp = newCanvas(size)
        val c = Canvas(bmp)
        c.drawBitmap(renderBackground(cfg, size), 0f, 0f, null)
        c.drawBitmap(renderForeground(cfg, size), 0f, 0f, null)
        return bmp
    }

    /** Foreground layer alone (transparent elsewhere) — for the section preview. */
    fun previewForeground(fg: Bitmap, pct: Int, size: Int, rotationDeg: Int = 0): Bitmap {
        val bmp = newCanvas(size)
        drawFitted(Canvas(bmp), fg, size, pct, rotationDeg.toFloat())
        return bmp
    }

    /** Background layer alone (colour fill or fitted image) — for the section preview. */
    fun previewBackground(bgIsColor: Boolean, bgColor: Int, bgImage: Bitmap?, pct: Int, size: Int): Bitmap {
        val bmp = newCanvas(size)
        val c = Canvas(bmp)
        if (bgIsColor) c.drawColor(bgColor)
        else if (bgImage != null) drawFitted(c, bgImage, size, pct)
        return bmp
    }

    /**
     * How the monochrome layer is derived — see [renderMono]. Chosen once per
     * icon by [monoKind] so every density agrees.
     */
    private enum class MonoKind { ALPHA, TONE, NONE }

    /**
     * Picks the derivation at a fixed probe size, so the choice can't come out
     * differently for two densities of the same icon:
     *
     *  - [MonoKind.ALPHA] — the foreground has a shape to carve (a sticker, a
     *    disc, a wordmark): silhouette it, exactly as Android Studio would;
     *  - [MonoKind.TONE] — it doesn't (a photo, or anything that fills the
     *    canvas): no outline to trace, so the icon's own tones become the layer;
     *  - [MonoKind.NONE] — the artwork is one flat tone, so there is no shape in
     *    it either way, and a solid block is worse than no layer at all.
     */
    private fun monoKind(cfg: Config): MonoKind {
        val px = IntArray(MONO_PROBE * MONO_PROBE)

        val layer = newCanvas(MONO_PROBE)
        drawFitted(Canvas(layer), cfg.foreground, MONO_PROBE, cfg.fgResizePct, cfg.fgRotationDeg.toFloat())
        layer.getPixels(px, 0, MONO_PROBE, 0, 0, MONO_PROBE, MONO_PROBE)
        if (hasShape(px, MONO_PROBE)) return MonoKind.ALPHA

        renderComposite(cfg, MONO_PROBE).getPixels(px, 0, MONO_PROBE, 0, 0, MONO_PROBE, MONO_PROBE)
        return if (hasToneRange(px)) MonoKind.TONE else MonoKind.NONE
    }

    /** True when the opaque pixels of [px] don't simply fill their bounding box. */
    private fun hasShape(px: IntArray, size: Int): Boolean {
        var minX = size; var minY = size; var maxX = -1; var maxY = -1; var opaque = 0
        for (i in px.indices) {
            if (px[i] ushr 24 < MONO_ALPHA_MIN) continue
            opaque++
            val x = i % size
            val y = i / size
            if (x < minX) minX = x
            if (x > maxX) maxX = x
            if (y < minY) minY = y
            if (y > maxY) maxY = y
        }
        if (opaque == 0) return false     // blank foreground: nothing to tint either

        // Antialiased edges fall below MONO_ALPHA_MIN, so they shrink the bounds
        // and the count together and drop out of the ratio. A photo fills its own
        // rectangle, which is why it needs the tonal derivation instead.
        val bounds = (maxX - minX + 1).toLong() * (maxY - minY + 1).toLong()
        return opaque.toDouble() / bounds < MONO_FLAT_FILL
    }

    /** True when the opaque part of [px] spans enough luminance to be more than one tone. */
    private fun hasToneRange(px: IntArray): Boolean {
        var min = 255
        var max = 0
        var opaque = 0
        for (p in px) {
            if (p ushr 24 < MONO_ALPHA_MIN) continue
            val l = luminance(p)
            opaque++
            if (l < min) min = l
            if (l > max) max = l
        }
        return opaque > 0 && max - min >= MONO_MIN_SPAN
    }

    /** Rec. 601 luma — close enough to how dark a colour reads. */
    private fun luminance(color: Int): Int =
        (77 * (color shr 16 and 0xFF) + 150 * (color shr 8 and 0xFF) + 29 * (color and 0xFF)) shr 8

    /**
     * Monochrome layer for themed icons. The system tints it at runtime, so it has
     * to be one colour plus transparency — and in themed mode it also stands in
     * for the foreground layer, which is why it is drawn at the foreground's own
     * size and rotation: any difference would make the icon jump the moment the
     * user switches themed icons on.
     *
     * [MonoKind.ALPHA] keeps the foreground's alpha and forces RGB to black, the
     * usual silhouette. [MonoKind.TONE] takes background and foreground together
     * and gives every pixel an alpha from how dark it is, so a photo — which has
     * no outline to trace — still becomes "the icon in one colour" instead of the
     * solid black block that silhouetting it would produce. The tonal range is
     * stretched first, so a dim or washed-out image still spans the full alpha
     * range, and the smaller of the two halves takes the ink, so the result reads
     * as a shape rather than a filled square.
     */
    private fun renderMono(cfg: Config, size: Int, kind: MonoKind): Bitmap {
        val px = IntArray(size * size)
        if (kind == MonoKind.ALPHA) {
            val layer = newCanvas(size)
            drawFitted(Canvas(layer), cfg.foreground, size, cfg.fgResizePct, cfg.fgRotationDeg.toFloat())
            layer.getPixels(px, 0, size, 0, 0, size, size)
            for (i in px.indices) px[i] = px[i] and 0xFF000000.toInt()  // keep alpha, RGB=0
        } else {
            renderComposite(cfg, size).getPixels(px, 0, size, 0, 0, size, size)
            toneToInk(px)
        }
        val bmp = newCanvas(size)
        bmp.setPixels(px, 0, size, 0, 0, size, size)
        return bmp
    }

    /**
     * Rewrites [px] in place as black ink over transparency, its alpha taken from
     * each pixel's luminance. Assumes [hasToneRange] held for these pixels.
     *
     * Only the opaque part of the composite counts as artwork: a fitted background
     * image can leave see-through margins, and those stay see-through rather than
     * becoming a ring of ink around the icon.
     */
    private fun toneToInk(px: IntArray) {
        var min = 255
        var max = 0
        var opaque = 0
        for (p in px) {
            if (p ushr 24 < MONO_ALPHA_MIN) continue
            val l = luminance(p)
            opaque++
            if (l < min) min = l
            if (l > max) max = l
        }
        val span = (max - min).coerceAtLeast(1)

        val ink = IntArray(px.size)
        var inked = 0
        for (i in px.indices) {
            if (px[i] ushr 24 < MONO_ALPHA_MIN) continue
            val v = 255 - (luminance(px[i]) - min) * 255 / span      // 255 = darkest
            ink[i] = v
            if (v > 127) inked++
        }

        // The ink is whichever half is smaller: a glyph is the artwork, not the
        // sheet it sits on. Without this a dark icon goes solid and a bright one
        // vanishes, and neither reads as anything.
        val invert = inked * 2 > opaque
        for (i in px.indices) {
            if (px[i] ushr 24 < MONO_ALPHA_MIN) {
                px[i] = 0
                continue
            }
            val v = if (invert) 255 - ink[i] else ink[i]
            px[i] = v shl 24                                          // black, alpha = v
        }
    }

    /** Masks the central 72dp viewport of the composite to a legacy [shape]. */
    fun renderLegacy(cfg: Config, size: Int, shape: String): Bitmap {
        val render = (size / VIEW_RATIO).roundToInt()   // composite size so viewport == size
        val comp = renderComposite(cfg, render)
        val crop = (render - size) / 2f
        val bmp = newCanvas(size)
        val c = Canvas(bmp)
        val path = Path()
        val s = size.toFloat()
        when (shape) {
            "circle" -> path.addCircle(s / 2, s / 2, s / 2, Path.Direction.CW)
            "squircle" -> path.addRoundRect(RectF(0f, 0f, s, s), s * 0.24f, s * 0.24f, Path.Direction.CW)
            else -> path.addRoundRect(RectF(0f, 0f, s, s), s * 0.16f, s * 0.16f, Path.Direction.CW)
        }
        c.clipPath(path)
        c.drawBitmap(comp, android.graphics.Rect(crop.toInt(), crop.toInt(), (crop + size).toInt(), (crop + size).toInt()),
            RectF(0f, 0f, s, s), Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = true })
        return bmp
    }

    private fun webp(bmp: Bitmap): ByteArray {
        val out = ByteArrayOutputStream()
        @Suppress("DEPRECATION")
        bmp.compress(Bitmap.CompressFormat.WEBP, 100, out)
        return out.toByteArray()
    }

    private fun hex(color: Int): String =
        String.format("#%06X", 0xFFFFFF and color)

    /**
     * Builds the full res/ asset set as a ZIP (paths relative to app/src/main/).
     * Overwrites the template's default ic_launcher* when extracted.
     */
    fun buildZip(cfg: Config): ByteArray {
        val name = cfg.name
        val bgIsImage = !cfg.bgIsColor
        // Silhouette, tonal stencil or nothing at all — see [monoKind]. Decided
        // once here so the files and the XML element can't disagree.
        val monoKind = monoKind(cfg)
        val monoOn = monoKind != MonoKind.NONE
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            fun put(path: String, bytes: ByteArray) {
                zip.putNextEntry(ZipEntry(path))
                zip.write(bytes)
                zip.closeEntry()
            }
            for ((d, a) in ADAPTIVE) {
                val l = LEGACY.getValue(d)
                val dir = "res/mipmap-$d/"
                put("$dir$name.webp", webp(renderLegacy(cfg, l, "square")))
                put("${dir}${name}_round.webp", webp(renderLegacy(cfg, l, "circle")))
                put("${dir}${name}_foreground.webp", webp(renderForeground(cfg, a)))
                if (monoOn) put("${dir}${name}_monochrome.webp", webp(renderMono(cfg, a, monoKind)))
                if (bgIsImage) put("${dir}${name}_background.webp", webp(renderBackground(cfg, a)))
            }
            val bgRef = if (bgIsImage) "@mipmap/${name}_background" else "@color/${name}_background"
            val monoLine =
                if (monoOn) "\n    <monochrome android:drawable=\"@mipmap/${name}_monochrome\"/>" else ""
            val xml = """<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="$bgRef"/>
    <foreground android:drawable="@mipmap/${name}_foreground"/>$monoLine
</adaptive-icon>
"""
            put("res/mipmap-anydpi-v26/$name.xml", xml.toByteArray())
            put("res/mipmap-anydpi-v26/${name}_round.xml", xml.toByteArray())
            if (cfg.bgIsColor) {
                val col = """<?xml version="1.0" encoding="utf-8"?>
<resources>
    <color name="${name}_background">${hex(cfg.bgColor)}</color>
</resources>
"""
                put("res/values/${name}_background.xml", col.toByteArray())
            }
        }
        return out.toByteArray()
    }
}
