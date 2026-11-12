package com.fastdrop.app

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RectF
import android.graphics.SweepGradient
import android.text.TextPaint
import android.view.View
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator

/**
 * Circular progress: gradient arc with round caps over a track, smooth animated progress,
 * indeterminate spinner, success (arc completes, check draws in) and error (shake, red, cross).
 */
class ProgressRing(ctx: Context) : View(ctx) {
    var colors: IntArray = Theme.SEND
        set(value) {
            field = if (value.size >= 2) value else intArrayOf(
                value.firstOrNull() ?: Theme.ACCENT, value.firstOrNull() ?: Theme.ACCENT
            )
            rebuild()
            invalidate()
        }

    /** Spinner mode: ignores progress, rotates a short arc. */
    var indeterminate: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            updateSpin()
            invalidate()
        }

    var centerText: String? = null
        set(value) { field = value; invalidate() }
    var subText: String? = null
        set(value) { field = value; invalidate() }

    private enum class State { IDLE, SUCCESS, ERROR }

    private val density = ctx.resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    private var state = State.IDLE
    private var shown = 0f          // progress currently drawn, 0..1
    private var spin = 0f           // degrees, indeterminate
    private var doneT = 0f          // 0..1 for success/error morph
    private var progAnim: ValueAnimator? = null
    private var spinAnim: ValueAnimator? = null
    private var doneAnim: ValueAnimator? = null

    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Theme.STROKE }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; alpha = 38 }
    private val mark = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val big = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Theme.TEXT; typeface = Theme.BLACK; textAlign = Paint.Align.CENTER }
    private val small = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Theme.MUTED; typeface = Theme.MEDIUM; textAlign = Paint.Align.CENTER }

    private val oval = RectF()
    private val matrixTmp = Matrix()
    private var normalShader: SweepGradient? = null
    private var okShader: SweepGradient? = null
    private var errShader: SweepGradient? = null

    private val checkPath = Path()
    private val checkSeg = Path()
    private val checkMeasure = PathMeasure()
    private var checkLen = 0f
    private val overshoot = OvershootInterpolator(2.2f)
    private var ri = 0f   // radius of the arc centre line

    init {
        isClickable = false
        isFocusable = false
    }

    // ------------------------------------------------------------------ public API

    /** Sets progress 0..1; eases to it when [animate] and animations are on. */
    fun setProgress(p: Float, animate: Boolean = true) {
        if (state != State.IDLE) return
        val target = p.coerceIn(0f, 1f)
        progAnim?.cancel()
        if (!animate || !Anim.on(context) || !isAttachedToWindow) {
            shown = target
            invalidate()
            return
        }
        progAnim = ValueAnimator.ofFloat(shown, target).apply {
            duration = 320
            interpolator = Theme.EASE_OUT
            addUpdateListener { shown = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    /** Arc completes in green, then a check mark draws in. */
    fun showSuccess() {
        progAnim?.cancel()
        state = State.SUCCESS
        indeterminate = false
        updateSpin()
        startDone(withArc = true)
    }

    /** Ring turns red, shakes, and a cross draws in. */
    fun showError() {
        progAnim?.cancel()
        state = State.ERROR
        indeterminate = false
        updateSpin()
        startDone(withArc = false)
        Anim.shake(this)
    }

    /** Back to an empty idle ring. */
    fun reset() {
        progAnim?.cancel()
        doneAnim?.cancel()
        state = State.IDLE
        shown = 0f
        doneT = 0f
        translationX = 0f
        invalidate()
    }

    private fun startDone(withArc: Boolean) {
        doneAnim?.cancel()
        val from = shown
        if (!Anim.on(context) || !isAttachedToWindow) {
            if (withArc) shown = 1f
            doneT = 1f
            invalidate()
            return
        }
        doneT = 0f
        doneAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = if (withArc) 900 else 480
            interpolator = LinearInterpolator()
            addUpdateListener {
                val t = it.animatedValue as Float
                if (withArc) {
                    // First 40%: finish the arc. Rest: draw the check.
                    val a = (t / 0.4f).coerceIn(0f, 1f)
                    shown = from + (1f - from) * (1f - (1f - a) * (1f - a))
                    doneT = ((t - 0.4f) / 0.6f).coerceIn(0f, 1f)
                } else doneT = t
                invalidate()
            }
            start()
        }
    }

    // ------------------------------------------------------------------ geometry + shaders

    override fun onMeasure(wSpec: Int, hSpec: Int) {
        val w = resolveSize(dp(180f).toInt(), wSpec)
        val h = resolveSize(w, hSpec)
        setMeasuredDimension(w, h)
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        val side = minOf(w, h).toFloat()
        val stroke = maxOf(dp(4f), side * 0.075f)
        track.strokeWidth = stroke
        arc.strokeWidth = stroke
        halo.strokeWidth = stroke * 2f
        mark.strokeWidth = maxOf(dp(3f), stroke * 0.85f)
        ri = side / 2f - stroke * 1.1f
        oval.set(-ri, -ri, ri, ri)
        // Check mark (relative to centre).
        checkPath.reset()
        checkPath.moveTo(-0.34f * ri, 0.02f * ri)
        checkPath.lineTo(-0.10f * ri, 0.27f * ri)
        checkPath.lineTo(0.36f * ri, -0.24f * ri)
        checkMeasure.setPath(checkPath, false)
        checkLen = checkMeasure.length
        rebuild()
    }

    private fun sweepOf(a: Int, b: Int) = SweepGradient(
        0f, 0f, intArrayOf(a, b, a), floatArrayOf(0f, 0.5f, 1f)
    )

    private fun rebuild() {
        if (ri <= 0f) return
        normalShader = sweepOf(colors[0], colors[colors.size - 1])
        okShader = sweepOf(Theme.SUCCESS, 0xFF6EE7B7.toInt())
        errShader = sweepOf(Theme.DANGER, 0xFFFF9AAE.toInt())
    }

    // ------------------------------------------------------------------ drawing

    override fun onDraw(c: Canvas) {
        if (ri <= 0f) return
        val save = c.save()
        c.translate(width / 2f, height / 2f)

        c.drawCircle(0f, 0f, ri, track)

        val shader = when (state) {
            State.SUCCESS -> okShader
            State.ERROR -> errShader
            State.IDLE -> normalShader
        }
        val spinning = indeterminate && state == State.IDLE
        val start: Float
        val sweep: Float
        if (spinning) {
            start = -90f + spin
            sweep = 100f + 50f * Math.sin(Math.toRadians((spin * 2f).toDouble())).toFloat()
            matrixTmp.setRotate(start)
        } else {
            start = -90f
            sweep = 360f * shown
            matrixTmp.setRotate(-90f)
        }
        shader?.setLocalMatrix(matrixTmp)
        arc.shader = shader
        halo.shader = shader
        if (sweep > 0.5f) {
            c.drawArc(oval, start, sweep, false, halo)
            c.drawArc(oval, start, sweep, false, arc)
        }

        when (state) {
            State.SUCCESS -> drawCheck(c)
            State.ERROR -> drawCross(c)
            State.IDLE -> if (!spinning) drawTexts(c)
        }
        c.restoreToCount(save)
    }

    private fun drawTexts(c: Canvas) {
        val main = centerText
        val sub = subText
        if (main.isNullOrEmpty() && sub.isNullOrEmpty()) return
        val maxW = ri * 1.35f
        var subH = 0f
        if (!sub.isNullOrEmpty()) {
            small.textSize = ri * 0.22f
            val w = small.measureText(sub)
            if (w > maxW) small.textSize = small.textSize * maxW / w
            subH = small.textSize * 1.25f
        }
        if (!main.isNullOrEmpty()) {
            big.textSize = ri * 0.62f
            val w = big.measureText(main)
            if (w > maxW) big.textSize = big.textSize * maxW / w
            val base = -(big.ascent() + big.descent()) / 2f - subH / 2f
            c.drawText(main, 0f, base, big)
            if (!sub.isNullOrEmpty()) c.drawText(sub, 0f, base + big.descent() + small.textSize * 1.15f, small)
        } else if (!sub.isNullOrEmpty()) {
            c.drawText(sub, 0f, -(small.ascent() + small.descent()) / 2f, small)
        }
    }

    private fun drawCheck(c: Canvas) {
        if (doneT <= 0f) return
        mark.color = Theme.SUCCESS
        val s = 0.82f + 0.18f * overshoot.getInterpolation(doneT)
        val save = c.save()
        c.scale(s, s)
        checkSeg.rewind()
        checkMeasure.getSegment(0f, checkLen * doneT, checkSeg, true)
        c.drawPath(checkSeg, mark)
        c.restoreToCount(save)
    }

    private fun drawCross(c: Canvas) {
        if (doneT <= 0f) return
        mark.color = Theme.DANGER
        val a = 0.26f * ri
        val t1 = (doneT * 2f).coerceIn(0f, 1f)
        val t2 = (doneT * 2f - 1f).coerceIn(0f, 1f)
        c.drawLine(-a, -a, -a + 2 * a * t1, -a + 2 * a * t1, mark)
        if (t2 > 0f) c.drawLine(a, -a, a - 2 * a * t2, -a + 2 * a * t2, mark)
    }

    // ------------------------------------------------------------------ spinner lifecycle

    private fun updateSpin() {
        val should = indeterminate && state == State.IDLE && isAttachedToWindow && isShown &&
            windowVisibility == VISIBLE && Anim.on(context)
        if (should) {
            if (spinAnim == null) {
                spinAnim = ValueAnimator.ofFloat(0f, 360f).apply {
                    duration = 1100
                    interpolator = LinearInterpolator()
                    repeatCount = ValueAnimator.INFINITE
                    addUpdateListener { spin = it.animatedValue as Float; invalidate() }
                    start()
                }
            }
        } else {
            spinAnim?.cancel()
            spinAnim = null
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // A success/error morph cut short by a detach is completed instantly.
        if (state != State.IDLE && doneAnim?.isRunning != true) {
            doneT = 1f
            if (state == State.SUCCESS) shown = 1f
        }
        updateSpin()
    }

    override fun onDetachedFromWindow() {
        spinAnim?.cancel(); spinAnim = null
        progAnim?.cancel()
        doneAnim?.removeAllListeners()
        doneAnim?.cancel()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        updateSpin()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        updateSpin()
    }
}
