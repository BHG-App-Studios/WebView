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
    private const val MONO_RESIZE = 69              // auto monochrome tracks the HTML default

    private fun newCanvas(size: Int): Bitmap =
        Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)

    /** Draws [src] centered on a [size]×[size] area; its longest side = size*pct/100. */
    private fun drawFitted(canvas: Canvas, src: Bitmap, size: Int, pct: Int) {
        val target = size * pct / 100f
        val scale = target / max(src.width, src.height)
        val w = src.width * scale
        val h = src.height * scale
        val left = (size - w) / 2f
        val top = (size - h) / 2f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = true }
        canvas.drawBitmap(src, null, RectF(left, top, left + w, top + h), paint)
    }

    fun renderForeground(cfg: Config, size: Int): Bitmap {
        val bmp = newCanvas(size)
        drawFitted(Canvas(bmp), cfg.foreground, size, cfg.fgResizePct)
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
    fun previewForeground(fg: Bitmap, pct: Int, size: Int): Bitmap {
        val bmp = newCanvas(size)
        drawFitted(Canvas(bmp), fg, size, pct)
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

    /** Auto silhouette: the foreground with every pixel forced black, alpha kept. */
    private fun renderMono(cfg: Config, size: Int): Bitmap {
        val bmp = newCanvas(size)
        drawFitted(Canvas(bmp), cfg.foreground, size, MONO_RESIZE)
        val px = IntArray(size * size)
        bmp.getPixels(px, 0, size, 0, 0, size, size)
        for (i in px.indices) px[i] = px[i] and 0xFF000000.toInt()  // keep alpha, RGB=0
        bmp.setPixels(px, 0, size, 0, 0, size, size)
        return bmp
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
                put("${dir}${name}_monochrome.webp", webp(renderMono(cfg, a)))
                if (bgIsImage) put("${dir}${name}_background.webp", webp(renderBackground(cfg, a)))
            }
            val bgRef = if (bgIsImage) "@mipmap/${name}_background" else "@color/${name}_background"
            val xml = """<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="$bgRef"/>
    <foreground android:drawable="@mipmap/${name}_foreground"/>
    <monochrome android:drawable="@mipmap/${name}_monochrome"/>
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
