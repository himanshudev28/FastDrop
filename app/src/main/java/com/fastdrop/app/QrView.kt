package com.fastdrop.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.view.View
import java.util.Random

/**
 * A QR code on a flat plate: black square modules on a white plate, 0 radius, in both themes
 * (docs/DESIGN.md 2.2). No ripple, no shimmer: scanners want a calm, high-contrast code. While
 * there is no text yet it draws a quiet grey placeholder pattern.
 */
class QrView(ctx: Context) : View(ctx) {

    var dark: Int = Theme.QR_INK
        set(v) { field = v; invalidate() }
    var light: Int = Theme.QR_PLATE
        set(v) { field = v; invalidate() }

    /** Kept for source compatibility. A centre logo is no longer drawn on the flat plate. */
    var logo: Drawable? = null

    private var text: String? = null
    private var grid: BooleanArray? = null
    private var n = 0

    private val quiet = 2f                 // quiet zone in modules, inside the plate
    private val paint = Paint().apply { isAntiAlias = false; style = Paint.Style.FILL }
    private val placeholder: BooleanArray by lazy {
        val rnd = Random(7)
        BooleanArray(PLACEHOLDER_N * PLACEHOLDER_N) { rnd.nextFloat() < 0.45f }
    }

    init {
        contentDescription = context.getString(R.string.qr_cd)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    /** null shows the placeholder. */
    fun setText(text: String?) {
        val t = text?.takeIf { it.isNotEmpty() }
        if (t == this.text && (t == null || grid != null)) return
        this.text = t
        grid = null
        n = 0
        if (t != null) {
            val m = Qr.matrix(t, highEcc = false)
            if (m != null) {
                val size = m.width
                val g = BooleanArray(size * size)
                for (y in 0 until size) for (x in 0 until size) g[y * size + x] = m.get(x, y)
                n = size
                grid = g
            }
        }
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val wm = MeasureSpec.getMode(widthMeasureSpec)
        val hm = MeasureSpec.getMode(heightMeasureSpec)
        val ws = MeasureSpec.getSize(widthMeasureSpec)
        val hs = MeasureSpec.getSize(heightMeasureSpec)
        val def = Theme.dp(context, 240)
        val size = when {
            wm == MeasureSpec.UNSPECIFIED && hm == MeasureSpec.UNSPECIFIED -> def
            wm == MeasureSpec.UNSPECIFIED -> hs
            hm == MeasureSpec.UNSPECIFIED -> ws
            else -> minOf(ws, hs)
        }
        setMeasuredDimension(size, size)
    }

    override fun onDraw(canvas: Canvas) {
        val side = minOf(width, height).toFloat()
        if (side <= 0f) return
        val ox0 = (width - side) / 2f
        val oy0 = (height - side) / 2f
        paint.color = light
        canvas.drawRect(ox0, oy0, ox0 + side, oy0 + side, paint)
        val g = grid
        if (g != null && n > 0) drawGrid(canvas, g, n, side, ox0, oy0, dark)
        else drawGrid(canvas, placeholder, PLACEHOLDER_N, side, ox0, oy0, 0xFFE4E2DC.toInt())
    }

    private fun drawGrid(c: Canvas, g: BooleanArray, count: Int, side: Float, ox0: Float, oy0: Float, color: Int) {
        val total = count + quiet * 2f
        // Whole-pixel cells keep module edges crisp so cameras do not see seams.
        val cell = Math.floor((side / total).toDouble()).toFloat().coerceAtLeast(1f)
        val used = cell * count
        val ox = ox0 + (side - used) / 2f
        val oy = oy0 + (side - used) / 2f
        paint.color = color
        for (y in 0 until count) {
            var x = 0
            while (x < count) {
                if (!g[y * count + x]) { x++; continue }
                var x2 = x
                while (x2 < count && g[y * count + x2]) x2++      // merge runs: fewer draw calls
                c.drawRect(ox + x * cell, oy + y * cell, ox + x2 * cell, oy + (y + 1) * cell, paint)
                x = x2
            }
        }
    }

    companion object {
        private const val PLACEHOLDER_N = 25
    }
}
