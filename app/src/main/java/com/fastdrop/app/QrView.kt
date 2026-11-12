package com.fastdrop.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.view.View
import android.view.animation.LinearInterpolator
import java.util.Random

/**
 * Premium QR card: white rounded card, round "dot" modules, solid rounded finder eyes, optional
 * centre logo, ripple reveal when the text changes and a shimmer while there is no text yet.
 * Always dark-on-light with a quiet zone so scanners read it reliably.
 */
class QrView(ctx: Context) : View(ctx) {

    var dark: Int = 0xFF0B0D14.toInt()
        set(v) { field = v; invalidate() }
    var light: Int = Color.WHITE
        set(v) { field = v; invalidate() }
    var logo: Drawable? = null
        set(v) {
            field = v
            if (text != null) encode()
            invalidate()
        }

    private var text: String? = null
    private var grid: BooleanArray? = null
    private var n = 0

    private val quiet = 3f                 // quiet zone in modules (card padding)
    private var reveal = 1f
    private var revealAnim: ValueAnimator? = null
    private var shimmer = 0f
    private var shimmerAnim: ValueAnimator? = null

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val shaderMatrix = Matrix()
    private var shader: LinearGradient? = null
    private var shaderBand = 0f

    private val placeholder: BooleanArray by lazy { buildPlaceholder() }

    init {
        contentDescription = "QR code"
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    /** null shows the shimmering placeholder; new text animates its modules in from the centre. */
    fun setText(text: String?) {
        val t = text?.takeIf { it.isNotEmpty() }
        if (t == this.text && (t == null || grid != null)) return
        this.text = t
        if (t == null) {
            grid = null
            revealAnim?.cancel()
            reveal = 1f
        } else {
            encode()
            startReveal()
        }
        updateShimmer()
        invalidate()
    }

    private fun encode() {
        val t = text ?: return
        val m = Qr.matrix(t, highEcc = logo != null)
        if (m == null) {
            grid = null
            n = 0
            return
        }
        val size = m.width
        val g = BooleanArray(size * size)
        for (y in 0 until size) for (x in 0 until size) g[y * size + x] = m.get(x, y)
        n = size
        grid = g
    }

    private fun startReveal() {
        revealAnim?.cancel()
        if (!Theme.animationsOn(context)) {
            reveal = 1f
            return
        }
        reveal = 0f
        revealAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 520
            interpolator = LinearInterpolator()
            addUpdateListener { reveal = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    // ---- shimmer lifecycle: only runs while the placeholder is visible on screen ----

    private fun updateShimmer() {
        val want = grid == null && isAttachedToWindow && isShown && windowVisibility == VISIBLE &&
            Theme.animationsOn(context)
        if (want) {
            if (shimmerAnim == null) {
                shimmerAnim = ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = 1500
                    repeatCount = ValueAnimator.INFINITE
                    interpolator = LinearInterpolator()
                    addUpdateListener { shimmer = it.animatedValue as Float; invalidate() }
                    start()
                }
            }
        } else {
            shimmerAnim?.cancel()
            shimmerAnim = null
        }
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); updateShimmer() }
    override fun onDetachedFromWindow() {
        revealAnim?.cancel()
        reveal = 1f
        shimmerAnim?.cancel(); shimmerAnim = null
        super.onDetachedFromWindow()
    }
    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility); updateShimmer()
    }
    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility); updateShimmer()
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

    // ---- drawing ----

    override fun onDraw(canvas: Canvas) {
        val side = minOf(width, height).toFloat()
        if (side <= 0f) return
        val ox0 = (width - side) / 2f
        val oy0 = (height - side) / 2f

        // card
        val radius = minOf(Theme.dp(context, 22).toFloat(), side * 0.09f)
        paint.shader = null
        paint.style = Paint.Style.FILL
        paint.color = light
        rect.set(ox0, oy0, ox0 + side, oy0 + side)
        canvas.drawRoundRect(rect, radius, radius, paint)

        val g = grid
        if (g != null && n > 0) {
            drawGrid(canvas, g, n, side, ox0, oy0, reveal, dark, true)
        } else {
            drawPlaceholder(canvas, side, ox0, oy0)
        }
    }

    private fun drawPlaceholder(canvas: Canvas, side: Float, ox0: Float, oy0: Float) {
        val base = 0xFFE4E7F0.toInt()
        val band = 0xFFC7CCDC.toInt()
        val bandW = side * 0.7f
        if (shader == null || shaderBand != bandW) {
            shader = LinearGradient(0f, 0f, bandW, 0f, intArrayOf(base, band, base), null, Shader.TileMode.CLAMP)
            shaderBand = bandW
        }
        val sh = shader!!
        val x = -bandW + shimmer * (side + bandW * 2f) + ox0
        shaderMatrix.setTranslate(x, 0f)
        sh.setLocalMatrix(shaderMatrix)
        drawGrid(canvas, placeholder, PLACEHOLDER_N, side, ox0, oy0, 1f, base, false, sh)
    }

    /** Draws modules. [reveal] drives the centre-out ripple; [shader] (placeholder) replaces the colour. */
    private fun drawGrid(
        canvas: Canvas, g: BooleanArray, count: Int, side: Float, ox0: Float, oy0: Float,
        reveal: Float, color: Int, useLogo: Boolean, shader: Shader? = null,
    ) {
        val total = count + quiet * 2f
        val cell = side / total
        val ox = ox0 + quiet * cell
        val oy = oy0 + quiet * cell
        val mid = count / 2f
        val maxD = Math.hypot(mid.toDouble(), mid.toDouble()).toFloat().coerceAtLeast(1f)

        paint.style = Paint.Style.FILL
        paint.shader = shader
        paint.color = color

        // logo clear zone (square, in modules)
        val logoDrawable = if (useLogo) logo else null
        var zone = 0f
        if (logoDrawable != null && count >= 25) {
            var lm = Math.round(count * 0.2f)
            if (lm % 2 == 0) lm += 1
            zone = lm / 2f + 1f
        }
        val showLogo = zone > 0f

        val r = cell * 0.47f
        for (y in 0 until count) {
            for (x in 0 until count) {
                if (!g[y * count + x]) continue
                if (inFinder(x, y, count)) continue
                val cx = x + 0.5f
                val cy = y + 0.5f
                if (showLogo && Math.abs(cx - mid) < zone && Math.abs(cy - mid) < zone) continue
                val s = if (reveal >= 1f) 1f else ripple(cx, cy, mid, maxD, reveal)
                if (s <= 0.01f) continue
                canvas.drawCircle(ox + cx * cell, oy + cy * cell, r * s, paint)
            }
        }

        // three finder eyes: solid rounded rings with a rounded centre
        val eye = 7f
        val corners = arrayOf(0f to 0f, (count - 7).toFloat() to 0f, 0f to (count - 7).toFloat())
        for ((ex, ey) in corners) {
            val s = if (reveal >= 1f) 1f else ripple(ex + eye / 2f, ey + eye / 2f, mid, maxD, reveal)
            if (s <= 0.01f) continue
            val cx = ox + (ex + eye / 2f) * cell
            val cy = oy + (ey + eye / 2f) * cell
            canvas.save()
            canvas.scale(s, s, cx, cy)
            val l = ox + ex * cell
            val t = oy + ey * cell
            // outer ring
            paint.shader = shader; paint.color = color
            rect.set(l, t, l + 7 * cell, t + 7 * cell)
            canvas.drawRoundRect(rect, 2.2f * cell, 2.2f * cell, paint)
            // hole
            paint.shader = null; paint.color = light
            rect.set(l + cell, t + cell, l + 6 * cell, t + 6 * cell)
            canvas.drawRoundRect(rect, 1.4f * cell, 1.4f * cell, paint)
            // pupil
            paint.shader = shader; paint.color = color
            rect.set(l + 2 * cell, t + 2 * cell, l + 5 * cell, t + 5 * cell)
            canvas.drawRoundRect(rect, 1.1f * cell, 1.1f * cell, paint)
            canvas.restore()
        }

        // centre logo on a light rounded plate
        if (showLogo && logoDrawable != null) {
            val plateHalf = (zone - 0.35f) * cell
            val cx = ox + mid * cell
            val cy = oy + mid * cell
            val a = if (reveal >= 1f) 1f else ((reveal - 0.4f) / 0.6f).coerceIn(0f, 1f)
            paint.shader = null
            paint.color = light
            paint.alpha = (255 * a).toInt()
            rect.set(cx - plateHalf, cy - plateHalf, cx + plateHalf, cy + plateHalf)
            canvas.drawRoundRect(rect, plateHalf * 0.38f, plateHalf * 0.38f, paint)
            paint.alpha = 255
            val inset = plateHalf * 0.82f
            logoDrawable.setBounds(
                (cx - inset).toInt(), (cy - inset).toInt(), (cx + inset).toInt(), (cy + inset).toInt()
            )
            logoDrawable.alpha = (255 * a).toInt()
            logoDrawable.draw(canvas)
        }
        paint.shader = null
        paint.color = color
    }

    private fun ripple(cx: Float, cy: Float, mid: Float, maxD: Float, reveal: Float): Float {
        val d = Math.hypot((cx - mid).toDouble(), (cy - mid).toDouble()).toFloat() / maxD
        val l = (reveal * 1.55f - d * 0.55f).coerceIn(0f, 1f)
        val inv = 1f - l
        return 1f - inv * inv * inv
    }

    private fun inFinder(x: Int, y: Int, count: Int): Boolean =
        (x < 7 && y < 7) || (x >= count - 7 && y < 7) || (x < 7 && y >= count - 7)

    private fun buildPlaceholder(): BooleanArray {
        val c = PLACEHOLDER_N
        val rnd = Random(7)
        val g = BooleanArray(c * c)
        for (i in g.indices) g[i] = rnd.nextFloat() < 0.5f
        // finder areas are drawn separately; mark them dark so the shape matches a real code
        for (y in 0 until c) for (x in 0 until c) if (inFinder(x, y, c)) g[y * c + x] = false
        return g
    }

    companion object {
        private const val PLACEHOLDER_N = 25
    }
}
