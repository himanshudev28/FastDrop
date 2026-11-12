package com.fastdrop.app

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The top of the transfer sheet (docs/DESIGN.md 4.6): a full-bleed solid block whose colour is the
 * transfer's state (orange sending, ultramarine receiving, green done, red failed, neutral cancelled).
 * It hosts the title, the [DotReadout], the [SegmentMeter] and the status lines, all in the panel's
 * ON colour. [setJob] switches the colour instantly: the change of state is the moment.
 */
class TransferPanel(ctx: Context, onHide: () -> Unit) : LinearLayout(ctx) {
    private val tvMode = Ui.tv(ctx)
    private val pitch = if (tvMode) 8f else 6f
    private fun dp(v: Int) = Theme.dp(context, v)

    val title: TextView = Ui.text(ctx, "", Type.TITLE)
    val hide: TextView = Ui.quietButton(ctx, ctx.getString(R.string.action_hide), false, onHide)
    val readout = DotReadout(ctx, pitch, if (tvMode) 6f else 4.6f, "8.88")
    val unit: TextView = Ui.text(ctx, "", Type.ROW)
    val crc: TextView = Ui.tag(ctx, ctx.getString(R.string.tag_crc))
    val meter = SegmentMeter(ctx, if (tvMode) 32 else 24)
    val percent: TextView = Ui.text(ctx, "", Type.FIGURE)
    val meta: TextView = Ui.text(ctx, "", Type.META)
    val note: TextView = Ui.text(ctx, "", Type.META)
    val body: TextView = Ui.text(ctx, "", Type.BODY)
    val codeLabel: TextView = Ui.text(ctx, "", Type.ROW)
    val code = DotReadout(ctx, 5f, 3.8f, "0000")
    val codeHelp: TextView = Ui.text(ctx, "", Type.META)
    val ticks = ScanTicks(ctx)

    private val titleRow = LinearLayout(ctx)
    private val readoutRow = LinearLayout(ctx)
    private val meterRow = LinearLayout(ctx)
    private val codeRow = LinearLayout(ctx)
    private val waitRow = LinearLayout(ctx)
    private var job: Job? = Job.SEND
    private var topInset = 0

    init {
        orientation = VERTICAL
        val g = Ui.gutter(ctx)
        fun padBase() = setPadding(g, dp(8), g, dp(20))
        padBase()
        title.maxLines = 2
        titleRow.orientation = HORIZONTAL
        titleRow.gravity = Gravity.CENTER_VERTICAL
        titleRow.addView(title, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { topMargin = dp(8); bottomMargin = dp(8) })
        titleRow.addView(hide, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginEnd = -dp(12) })
        addView(titleRow, LayoutParams(-1, -2))

        // code (waiting for accept)
        codeRow.orientation = HORIZONTAL
        codeRow.gravity = Gravity.CENTER_VERTICAL
        codeRow.addView(codeLabel, LayoutParams(-2, -2).apply { marginEnd = dp(16) })
        codeRow.addView(code, LayoutParams(-2, -2))
        addView(codeRow, LayoutParams(-1, -2).apply { topMargin = dp(8) })
        waitRow.orientation = HORIZONTAL
        waitRow.gravity = Gravity.CENTER_VERTICAL
        waitRow.addView(codeHelp, LayoutParams(0, -2, 1f))
        waitRow.addView(ticks, LayoutParams(-2, -2).apply { marginStart = dp(12) })
        addView(waitRow, LayoutParams(-1, -2).apply { topMargin = dp(12) })

        // speed readout + unit + CRC tag
        readoutRow.orientation = HORIZONTAL
        readoutRow.gravity = Gravity.BOTTOM
        readoutRow.addView(readout, LayoutParams(-2, -2))
        val unitCol = LinearLayout(ctx).apply { orientation = VERTICAL; gravity = Gravity.END }
        unitCol.addView(crc, LayoutParams(-2, -2).apply { bottomMargin = dp(6) })
        unitCol.addView(unit, LayoutParams(-2, -2))
        readoutRow.addView(unitCol, LayoutParams(-2, -2).apply { marginStart = dp(12) })
        addView(readoutRow, LayoutParams(-1, -2).apply { topMargin = dp(8) })

        // meter + percent
        meterRow.orientation = HORIZONTAL
        meterRow.gravity = Gravity.CENTER_VERTICAL
        meterRow.addView(meter, LayoutParams(0, -2, 1f))
        percent.gravity = Gravity.END
        meterRow.addView(percent, LayoutParams(dp(if (tvMode) 100 else 84), -2).apply { marginStart = dp(12) })
        addView(meterRow, LayoutParams(-1, -2).apply { topMargin = dp(16) })

        addView(meta, LayoutParams(-1, -2).apply { topMargin = dp(8) })
        addView(note, LayoutParams(-1, -2).apply { topMargin = dp(2) })
        addView(body, LayoutParams(-1, -2).apply { topMargin = dp(8) })
        setJob(Job.SEND)
    }

    /** Which blocks are visible. */
    fun layout(readoutOn: Boolean, meterOn: Boolean, codeOn: Boolean, waitOn: Boolean = codeOn) {
        readoutRow.visibility = if (readoutOn) VISIBLE else GONE
        meterRow.visibility = if (meterOn) VISIBLE else GONE
        codeRow.visibility = if (codeOn) VISIBLE else GONE
        waitRow.visibility = if (waitOn) VISIBLE else GONE
    }

    fun setBang(on: Boolean) {
        if (on) {
            val d = context.getDrawable(R.drawable.ic_alert)?.mutate()
            if (d != null) {
                val s = dp(22)
                d.setTint(job?.on ?: Theme.INK)
                d.setBounds(0, 0, s, s)
                title.setCompoundDrawablesRelative(d, null, null, null)
                title.compoundDrawablePadding = dp(8)
            }
        } else title.setCompoundDrawablesRelative(null, null, null, null)
    }

    /** Instant colour switch (null = neutral cancelled panel). */
    fun setJob(j: Job?) {
        job = j
        val on = j?.on ?: Theme.INK
        setBackgroundColor(j?.fill ?: Theme.SURFACE_2)
        for (t in arrayOf(title, unit, percent, meta, note, body, codeLabel, codeHelp)) t.setTextColor(on)
        hide.setTextColor(Ui.states(on, Theme.BG, Ui.tv(context)))
        readout.setJob(j)
        code.setJob(j)
        meter.setJob(j)
        ticks.setColors(on, j?.shade ?: Theme.INK_3)
        // The CRC tag is outlined in the ON colour.
        crc.setTextColor(on)
        crc.background = GradientDrawable().apply {
            setColor(0)
            cornerRadius = Ui.dpf(context, Theme.R_CTRL)
            setStroke(Theme.rule(context), on)
        }
        if (title.compoundDrawablesRelative[0] != null) setBang(true)
    }
}
