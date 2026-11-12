package com.fastdrop.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator

/**
 * Deep ink background with a few soft aurora glows drifting slowly. Draws the Theme.BG fill itself.
 * Cheap: three cached radial shaders, no per-frame allocation, ~30 fps, stops when detached or hidden.
 */
class Backdrop(ctx: Context) : View(ctx) {
    /** Glow colours (cycled over the blobs). */
    var colors: IntArray = intArrayOf(Theme.ACCENT, Theme.ACCENT_2, Theme.RECV_A)
        set(value) {
            field = if (value.isEmpty()) intArrayOf(Theme.ACCENT) else value
            rebuild()
            invalidate()
        }

    private class Blob(
        val ax: Float, val ay: Float,   // anchor, fraction of width/height
        val rx: Float, val ry: Float,   // drift radius, fraction
        val sx: Float, val sy: Float,   // drift speed multipliers
        val ph: Float,                  // phase offset (radians)
        val size: Float                 // glow radius as a fraction of the larger side
    ) {
        var shader: RadialGradient? = null
    }

    private val blobs = arrayOf(
        Blob(0.15f, 0.10f, 0.12f, 0.06f, 1f, 1.3f, 0f, 0.62f),
        Blob(0.90f, 0.35f, 0.10f, 0.09f, 1.2f, 0.8f, 2.1f, 0.55f),
        Blob(0.30f, 0.95f, 0.14f, 0.05f, 0.7f, 1.1f, 4.2f, 0.60f)
    )
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var phase = 0f           // 0..2π
    private var anim: ValueAnimator? = null
    private var lastFrame = 0L

    init {
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private fun rebuild() {
        val big = maxOf(width, height).toFloat()
        if (big <= 0f) return
        blobs.forEachIndexed { i, b ->
            val c = colors[i % colors.size]
            val r = big * b.size
            // Shader is centred on the origin; onDraw translates the canvas to the blob centre.
            b.shader = RadialGradient(
                0f, 0f, r,
                intArrayOf(Theme.alpha(c, 0.30f), Theme.alpha(c, 0.10f), Theme.alpha(c, 0f)),
                floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP
            )
        }
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        rebuild()
    }

    override fun onDraw(c: Canvas) {
        c.drawColor(Theme.BG)
        val w = width.toFloat()
        val h = height.toFloat()
        val big = maxOf(w, h)
        for (b in blobs) {
            val s = b.shader ?: continue
            val x = (b.ax + b.rx * Math.sin((phase * b.sx + b.ph).toDouble()).toFloat()) * w
            val y = (b.ay + b.ry * Math.cos((phase * b.sy + b.ph).toDouble()).toFloat()) * h
            paint.shader = s
            val save = c.save()
            c.translate(x, y)
            c.drawCircle(0f, 0f, big * b.size, paint)
            c.restoreToCount(save)
        }
    }

    private var covered = false
    private var polling = false
    private val poll = object : Runnable {
        override fun run() {
            polling = false
            if (!isAttachedToWindow) return
            val c = isCovered()
            if (c != covered) { covered = c; update() }
            polling = true
            postDelayed(this, 400)
        }
    }

    /** True when a later-drawn, fully visible Backdrop (a full-screen screen's own) hides this one. */
    private fun isCovered(): Boolean {
        val top = rootView as? ViewGroup ?: return false
        var seenSelf = false
        var covers = false
        fun opaque(v: View): Boolean {
            var x: View? = v
            while (x != null) {
                if (x.visibility != VISIBLE || x.alpha < 0.99f || x.translationX != 0f || x.translationY != 0f ||
                    x.scaleX < 0.99f || x.scaleY < 0.99f) return false
                x = x.parent as? View
            }
            return true
        }
        fun walk(g: ViewGroup) {
            for (i in 0 until g.childCount) {
                val c = g.getChildAt(i)
                if (c === this) seenSelf = true
                else if (c is Backdrop && seenSelf && c.width >= width && c.height >= height && opaque(c)) covers = true
                else if (c is ViewGroup) walk(c)
            }
        }
        walk(top)
        return covers
    }

    private fun update() {
        val shouldRun = isAttachedToWindow && isShown && windowVisibility == VISIBLE &&
            width > 0 && Anim.on(context) && !covered
        if (shouldRun) {
            if (anim == null) {
                anim = ValueAnimator.ofFloat(0f, (Math.PI * 2).toFloat()).apply {
                    duration = 45000
                    repeatCount = ValueAnimator.INFINITE
                    interpolator = LinearInterpolator()
                    addUpdateListener {
                        val now = System.nanoTime()
                        if (now - lastFrame < 30_000_000L) return@addUpdateListener   // ~30 fps is plenty
                        lastFrame = now
                        phase = it.animatedValue as Float
                        invalidate()
                    }
                    start()
                }
            }
        } else {
            anim?.cancel()
            anim = null
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!polling) { polling = true; postDelayed(poll, 400) }
        update()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(poll)
        polling = false
        covered = false
        anim?.cancel()
        anim = null
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        update()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        update()
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        if (anim == null) update()
    }
}
