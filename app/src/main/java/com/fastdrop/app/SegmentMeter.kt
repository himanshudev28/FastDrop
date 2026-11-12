package com.fastdrop.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View

/**
 * The transfer meter (docs/DESIGN.md 4.6): [segments] flat rectangles (24 on phone, 32 on TV),
 * 8 dp tall, 2 dp gaps, 0 radius. Filled segments use the panel's ON colour, empty ones its shade.
 * A 2 dp mark in the ON colour shows where a resumed transfer picked up. Steps with each update, no
 * tween, no allocations in onDraw.
 */
class SegmentMeter(ctx: Context, private val segments: Int = 24) : View(ctx) {
    private val fillPaint = Paint()
    private val emptyPaint = Paint()
    private val density = ctx.resources.displayMetrics.density
    private val segH = 8f * density
    private val gap = 2f * density
    private val markH = 3f * density            // the resume mark reaches this far above and below the segments
    private var filled = 0
    private var resumeAt = -1f

    init {
        fillPaint.color = Theme.INK
        emptyPaint.color = Theme.LINE
    }

    fun setJob(job: Job?) {
        val a = job?.on ?: Theme.INK
        val b = job?.shade ?: Theme.LINE
        if (fillPaint.color == a && emptyPaint.color == b) return
        fillPaint.color = a; emptyPaint.color = b
        invalidate()
    }

    /** [done] and [resumedAt] are fractions 0..1; [resumedAt] < 0 means none. */
    fun setProgress(done: Float, resumedAt: Float = -1f) {
        val f = (done.coerceIn(0f, 1f) * segments + 0.0001f).toInt().coerceIn(0, segments)
        if (f == filled && resumedAt == resumeAt) return
        filled = f
        resumeAt = resumedAt
        invalidate()
    }

    override fun onMeasure(wSpec: Int, hSpec: Int) {
        val w = MeasureSpec.getSize(wSpec)
        setMeasuredDimension(w, (segH + 2 * markH).toInt())
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val sw = (w - gap * (segments - 1)) / segments
        val top = markH
        val bottom = top + segH
        for (i in 0 until segments) {
            val x = i * (sw + gap)
            c.drawRect(x, top, x + sw, bottom, if (i < filled) fillPaint else emptyPaint)
        }
        if (resumeAt > 0f && resumeAt < 1f) {
            val x = (resumeAt * segments).toInt() * (sw + gap) - gap
            c.drawRect(x, 0f, x + 2f * density, height.toFloat(), fillPaint)
        }
    }
}
