package com.fastdrop.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import java.util.Random

/**
 * One-shot confetti burst. Put it over a screen (match parent) and call [burst].
 * Particles live in fixed arrays; the view stops invalidating once everything has faded.
 */
class Confetti(ctx: Context) : View(ctx) {
    private val n = 90
    private val px = FloatArray(n)
    private val py = FloatArray(n)
    private val vx = FloatArray(n)
    private val vy = FloatArray(n)
    private val rot = FloatArray(n)
    private val vrot = FloatArray(n)
    private val size = FloatArray(n)
    private val color = IntArray(n)
    private val round = BooleanArray(n)
    private val delay = FloatArray(n)

    private val palette = intArrayOf(
        Theme.ACCENT, Theme.ACCENT_2, Theme.RECV_A, Theme.WEB_A, Theme.WEB_B, Theme.SEND_A, Color.WHITE
    )
    private val rnd = Random()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val density = ctx.resources.displayMetrics.density

    private var age = 0f
    private var life = 2.4f
    private var playing = false
    private var last = 0L

    init {
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /** Fires the burst from the upper middle of the view. */
    fun burst() {
        if (!Anim.on(context) || width == 0 || height == 0) {
            if (width == 0 || height == 0) {
                // Not laid out yet: fire as soon as we have a size.
                post { if (width > 0) burst() }
            }
            return
        }
        val ox = width / 2f
        val oy = height * 0.34f
        for (i in 0 until n) {
            val a = (-Math.PI / 2 + (rnd.nextFloat() - 0.5f) * Math.PI * 1.25).toFloat()
            val speed = (380f + rnd.nextFloat() * 760f) * density
            px[i] = ox + (rnd.nextFloat() - 0.5f) * 40f * density
            py[i] = oy
            vx[i] = Math.cos(a.toDouble()).toFloat() * speed
            vy[i] = Math.sin(a.toDouble()).toFloat() * speed
            rot[i] = rnd.nextFloat() * 360f
            vrot[i] = (rnd.nextFloat() - 0.5f) * 720f
            size[i] = (5f + rnd.nextFloat() * 6f) * density
            color[i] = palette[rnd.nextInt(palette.size)]
            round[i] = rnd.nextInt(4) == 0
            delay[i] = rnd.nextFloat() * 0.12f
        }
        age = 0f
        last = 0L
        playing = true
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        if (!playing) return
        val now = System.nanoTime()
        val dt = if (last == 0L) 0f else minOf(0.04f, (now - last) / 1_000_000_000f)
        last = now
        age += dt
        if (age >= life) {
            playing = false
            return
        }
        val g = 1900f * density
        val drag = 1f - 1.5f * dt
        val fade = ((life - age) / 0.7f).coerceIn(0f, 1f)
        for (i in 0 until n) {
            if (age < delay[i]) continue
            vy[i] += g * dt
            vx[i] *= drag
            vy[i] *= drag
            px[i] += vx[i] * dt
            py[i] += vy[i] * dt
            rot[i] += vrot[i] * dt
            if (py[i] > height + 40f) continue
            paint.color = color[i]
            paint.alpha = (255 * fade).toInt()
            val s = size[i]
            if (round[i]) {
                c.drawCircle(px[i], py[i], s * 0.45f, paint)
            } else {
                val save = c.save()
                c.translate(px[i], py[i])
                c.rotate(rot[i])
                rect.set(-s / 2f, -s * 0.3f, s / 2f, s * 0.3f)
                c.drawRoundRect(rect, s * 0.12f, s * 0.12f, paint)
                c.restoreToCount(save)
            }
        }
        postInvalidateOnAnimation()
    }

    override fun onDetachedFromWindow() {
        playing = false
        super.onDetachedFromWindow()
    }

    // Never steal touches from what's underneath.
    override fun onTouchEvent(event: MotionEvent): Boolean = false
}
