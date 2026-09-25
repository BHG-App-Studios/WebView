package com.BHG.webapp

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View

/**
 * A real HSV colour picker: a saturation/value panel with a hue bar beneath it.
 * Drag anywhere to pick; [onColorChanged] fires live. Set the current colour via
 * [setColor]. Deliberately self-contained (no dependency) so it drops into the
 * logo creator's dialog.
 */
class ColorPickerView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    var onColorChanged: ((Int) -> Unit)? = null

    private val hsv = floatArrayOf(0f, 1f, 1f)   // H 0..360, S 0..1, V 0..1

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
    }
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0x55000000
    }

    private val svRect = RectF()
    private val hueRect = RectF()
    private val radius = dp(9f)
    private val gap = dp(18f)
    private val hueH = dp(22f)
    private val panelRadius = dp(14f)

    private var satShader: Shader? = null
    private var valShader: Shader? = null
    private var hueShader: Shader? = null

    init {
        stroke.strokeWidth = dp(2.5f)
        shadow.strokeWidth = dp(4f)
    }

    /** Adopt [color] (alpha ignored) without notifying the listener. */
    fun setColor(color: Int) {
        Color.colorToHSV(color, hsv)
        rebuildShaders()
        invalidate()
    }

    /** Current colour, fully opaque. */
    fun getColor(): Int = Color.HSVToColor(hsv)

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        val pad = dp(4f)
        val panel = h - hueH - gap - pad * 2
        svRect.set(pad, pad, w - pad, pad + panel)
        hueRect.set(pad, h - hueH - pad, w - pad, h - pad)
        valShader = LinearGradient(
            0f, svRect.top, 0f, svRect.bottom,
            Color.WHITE, Color.BLACK, Shader.TileMode.CLAMP
        )
        hueShader = LinearGradient(
            hueRect.left, 0f, hueRect.right, 0f,
            intArrayOf(
                Color.RED, Color.YELLOW, Color.GREEN,
                Color.CYAN, Color.BLUE, Color.MAGENTA, Color.RED
            ), null, Shader.TileMode.CLAMP
        )
        rebuildShaders()
    }

    private fun rebuildShaders() {
        if (svRect.width() <= 0f) return
        val hue = Color.HSVToColor(floatArrayOf(hsv[0], 1f, 1f))
        satShader = LinearGradient(
            svRect.left, 0f, svRect.right, 0f,
            Color.WHITE, hue, Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        // Saturation/value panel: white→hue horizontally, then white→black
        // vertically multiplied over it. Rounded corners for a modern look.
        val save = canvas.save()
        val clip = android.graphics.Path().apply {
            addRoundRect(svRect, panelRadius, panelRadius, android.graphics.Path.Direction.CW)
        }
        canvas.clipPath(clip)
        fill.shader = satShader
        canvas.drawRect(svRect, fill)
        fill.shader = valShader
        fill.xfermode = android.graphics.PorterDuffXfermode(PorterDuff.Mode.MULTIPLY)
        canvas.drawRect(svRect, fill)
        fill.xfermode = null
        fill.shader = null
        canvas.restoreToCount(save)

        // Hue bar
        fill.shader = hueShader
        canvas.drawRoundRect(hueRect, hueH / 2f, hueH / 2f, fill)
        fill.shader = null

        // SV selector
        val sx = svRect.left + hsv[1] * svRect.width()
        val sy = svRect.top + (1f - hsv[2]) * svRect.height()
        canvas.drawCircle(sx, sy, radius, shadow)
        canvas.drawCircle(sx, sy, radius, stroke)

        // Hue thumb
        val hx = hueRect.left + (hsv[0] / 360f) * hueRect.width()
        val hy = hueRect.centerY()
        canvas.drawCircle(hx, hy, hueH / 2f - dp(1f), shadow)
        canvas.drawCircle(hx, hy, hueH / 2f - dp(2f), stroke)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                handleTouch(event.x, event.y)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun handleTouch(x: Float, y: Float) {
        if (y >= hueRect.top - gap / 2f) {
            val h = ((x - hueRect.left) / hueRect.width()).coerceIn(0f, 1f) * 360f
            hsv[0] = h
            rebuildShaders()
        } else {
            hsv[1] = ((x - svRect.left) / svRect.width()).coerceIn(0f, 1f)
            hsv[2] = 1f - ((y - svRect.top) / svRect.height()).coerceIn(0f, 1f)
        }
        invalidate()
        onColorChanged?.invoke(getColor())
    }

    private fun dp(v: Float): Float = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics
    )
}
