package com.fastdrop.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View

/**
 * The scanner overlay (docs/DESIGN.md 4.9): the camera dimmed 50 % outside a square window and four
 * flat corner brackets (3 dp, square caps). No moving line. On success the brackets turn green.
 * [win] is read from the decode thread to crop the frame, so it is volatile.
 */
internal class ScanOverlay(ctx: Context) : View(ctx) {
    class Win(val rect: RectF, val vw: Int, val vh: Int)

    @Volatile var win: Win? = null

    private val d = ctx.resources.displayMetrics.density
    private val dimPaint = Paint().apply { color = 0x80000000.toInt() }
    private val bracketPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3 * d
        strokeCap = Paint.Cap.SQUARE
        strokeJoin = Paint.Join.MITER
    }
    private val dim = Path()
    private val bracket = Path()
    private val arm = 32 * d
    private var succeeded = false

    fun showSuccess() {
        succeeded = true
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        val landscape = w > h
        val want = Theme.dp(context, if (Ui.tv(context)) 320 else 240).toFloat()
        val side = minOf(want, minOf(w, h) * (if (landscape) 0.6f else 0.72f))
        val cx = w / 2f
        val cy = h * (if (landscape) 0.5f else 0.42f)
        win = Win(RectF(cx - side / 2, cy - side / 2, cx + side / 2, cy + side / 2), w, h)
    }

    override fun onDraw(canvas: Canvas) {
        val r = win?.rect ?: return
        dim.reset()
        dim.addRect(0f, 0f, width.toFloat(), height.toFloat(), Path.Direction.CW)
        dim.addRect(r, Path.Direction.CW)
        dim.fillType = Path.FillType.EVEN_ODD
        canvas.drawPath(dim, dimPaint)

        bracketPaint.color = if (succeeded) Theme.C_OK else 0xFFFFFFFF.toInt()
        bracket(canvas, r.left, r.top, 1, 1)
        bracket(canvas, r.right, r.top, -1, 1)
        bracket(canvas, r.left, r.bottom, 1, -1)
        bracket(canvas, r.right, r.bottom, -1, -1)
    }

    /** One L-shaped corner. sx / sy point into the window. */
    private fun bracket(c: Canvas, x: Float, y: Float, sx: Int, sy: Int) {
        bracket.reset()
        bracket.moveTo(x, y + sy * arm)
        bracket.lineTo(x, y)
        bracket.lineTo(x + sx * arm, y)
        c.drawPath(bracket, bracketPaint)
    }
}
