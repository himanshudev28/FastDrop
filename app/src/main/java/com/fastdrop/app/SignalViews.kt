package com.fastdrop.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout

/**
 * "Scan ticks": three 6 dp squares that light in sequence, 300 ms each. The only looping animation
 * in the app. It runs only while the view is on screen, and with animations off all three squares
 * stay in INK_3. [color] is the lit colour (INK on neutral, a job's ON colour on a panel).
 */
class ScanTicks(ctx: Context, private var color: Int = Theme.INK, private var dimColor: Int = Theme.INK_3) : View(ctx) {
    private val p = Paint()
    private val d = ctx.resources.displayMetrics.density
    private val size = 6f * d
    private val gap = 4f * d
    private var step = 0
    private val handler = Handler(Looper.getMainLooper())
    private val animate = Anim.on(ctx)
    private val loop = object : Runnable {
        override fun run() {
            step = (step + 1) % 3
            invalidate()
            handler.postDelayed(this, 300)
        }
    }

    fun setColors(lit: Int, dim: Int) { color = lit; dimColor = dim; invalidate() }

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension((3 * size + 2 * gap).toInt(), size.toInt())

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (animate) handler.postDelayed(loop, 300)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        handler.removeCallbacks(loop)
    }

    override fun onVisibilityChanged(changed: View, visibility: Int) {
        super.onVisibilityChanged(changed, visibility)
        handler.removeCallbacks(loop)
        if (animate && visibility == VISIBLE && isAttachedToWindow) handler.postDelayed(loop, 300)
    }

    override fun onDraw(c: Canvas) {
        for (i in 0 until 3) {
            p.color = if (animate && i == step) color else dimColor
            val x = i * (size + gap)
            c.drawRect(x, 0f, x + size, size, p)
        }
    }
}

/**
 * The transfer strip (docs/DESIGN.md 4.6): 56 dp, a solid fill in the transfer's colour with the
 * ON colour for text, and a 4 dp meter across the top edge. Tap opens the transfer sheet.
 */
class TransferStrip(ctx: Context, onClick: () -> Unit) : FrameLayout(ctx) {
    private val title = Ui.text(ctx, "", Type.ROW)
    private val meta = Ui.text(ctx, "", Type.META)
    private val fillPaint = Paint()
    private val emptyPaint = Paint()
    private var progress = 0f
    private val barH = 4f * ctx.resources.displayMetrics.density
    private var job: Job = Job.SEND

    init {
        setWillNotDraw(false)
        val g = Ui.gutter(ctx)
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(g, 0, g, 0)
        }
        col.addView(title)
        col.addView(meta)
        addView(col, LayoutParams(LayoutParams.MATCH_PARENT, Theme.dp(ctx, 56)))
        minimumHeight = Theme.dp(ctx, 56)
        isFocusable = true
        setOnClickListener { Ui.haptic(it); onClick() }
        paint(Job.SEND)
    }

    private fun paint(j: Job) {
        job = j
        setBackgroundColor(j.fill)
        fillPaint.color = j.on
        emptyPaint.color = j.shade
        title.setTextColor(j.on)
        meta.setTextColor(j.on)
    }

    /** [progress] 0..1, or negative for "no progress yet" (connecting). */
    fun update(j: Job, titleText: String, metaText: String, progress: Float) {
        if (j !== job) paint(j)
        if (title.text.toString() != titleText) title.text = titleText
        if (meta.text.toString() != metaText) meta.text = metaText
        meta.visibility = if (metaText.isEmpty()) GONE else VISIBLE
        val p = progress.coerceIn(0f, 1f)
        if (p != this.progress) { this.progress = p; invalidate() }
    }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val w = width.toFloat()
        c.drawRect(0f, 0f, w, barH, emptyPaint)
        c.drawRect(0f, 0f, w * progress, barH, fillPaint)
        if (isFocused) {
            // Focus (TV): a solid frame inside the strip in the ON colour.
            val t = 3f * resources.displayMetrics.density
            val h = height.toFloat()
            c.drawRect(0f, barH, w, barH + t, fillPaint)
            c.drawRect(0f, h - t, w, h, fillPaint)
            c.drawRect(0f, barH, t, h, fillPaint)
            c.drawRect(w - t, barH, w, h, fillPaint)
        }
    }

    override fun onFocusChanged(gained: Boolean, direction: Int, previouslyFocusedRect: android.graphics.Rect?) {
        super.onFocusChanged(gained, direction, previouslyFocusedRect)
        invalidate()
    }
}
