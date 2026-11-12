package com.fastdrop.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/** One picker tab: its id, label and category colour ([Job] null = neutral INK, used for Files). */
internal class PickTab(val id: String, val label: String, val job: Job?)

/**
 * The picker's tab strip (docs 4.2). Phone: one row, equal widths. TV ([vertical]): a left rail.
 * The selected tab is a solid 6 dp block in its category colour with that colour's ON text.
 *
 * The block is drawn in onDraw rather than added as a child view. A plain View child with
 * match_parent height would make this wrap_content bar take all the free height, leaving the
 * list area below it zero tall. Do not turn it into a child.
 */
internal class PickerTabBar(
    ctx: Context, private val tabs: List<PickTab>, private val vertical: Boolean,
    private val onPick: (Int) -> Unit,
) : FrameLayout(ctx) {
    private val labels = ArrayList<TextView>()
    private val indicator = GradientDrawable().apply { cornerRadius = Theme.dp(ctx, Theme.R_CTRL).toFloat() }
    private val pad = Theme.dp(ctx, 4)
    private val itemH = Theme.dp(ctx, if (Ui.tv(ctx)) 48 else 40)
    private var index = 0

    init {
        setWillNotDraw(false)
        setPadding(pad, pad, pad, pad)
        val row = LinearLayout(ctx).apply { orientation = if (vertical) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL }
        tabs.forEachIndexed { i, d ->
            val t = Ui.text(ctx, d.label, if (Ui.tv(ctx)) Type.ROW else Type.META, Theme.INK_2).apply {
                typeface = Theme.MEDIUM
                gravity = if (vertical) Gravity.CENTER_VERTICAL or Gravity.START else Gravity.CENTER
                if (vertical) setPadding(Theme.dp(ctx, 16), 0, Theme.dp(ctx, 12), 0)
                maxLines = 1
                contentDescription = d.label
            }
            Ui.focusFill(t, d.job, 1.06f, Theme.R_CTRL, null)
            t.setOnClickListener { Ui.haptic(it, Haptic.TICK); onPick(i) }
            labels.add(t)
            row.addView(t, if (vertical) LinearLayout.LayoutParams(-1, itemH).apply { bottomMargin = Theme.dp(ctx, 4) }
            else LinearLayout.LayoutParams(0, itemH, 1f))
        }
        addView(row, LayoutParams(-1, -2))
        paint()
    }

    override fun onDraw(c: Canvas) {
        if (tabs.isEmpty()) return
        indicator.setColor(tabs[index].job?.fill ?: Theme.INK)
        if (vertical) {
            val top = pad + index * (itemH + Theme.dp(context, 4))
            indicator.setBounds(pad, top, width - pad, top + itemH)
        } else {
            val tw = (width - 2 * pad) / tabs.size.toFloat()
            if (tw <= 0f) return
            val l = pad + index * tw
            indicator.setBounds(l.toInt(), pad, (l + tw).toInt(), height - pad)
        }
        indicator.draw(c)
    }

    private fun paint() {
        labels.forEachIndexed { n, t ->
            val job = tabs[n].job
            val on = job?.on ?: Theme.BG
            // Selected: the block behind is the tab's colour, so the text takes its ON colour.
            // Unselected: INK_2, flipping to the focus fill's ON colour while focused.
            if (n == index) t.setTextColor(on) else t.setTextColor(Ui.states(Theme.INK_2, on, Ui.tv(context)))
            t.isSelected = n == index
        }
    }

    fun select(i: Int) {
        index = i.coerceIn(0, tabs.size - 1)
        paint()
        invalidate()
    }

    fun focusTab(i: Int) { labels.getOrNull(i)?.requestFocus() }

    /** D-pad: the first tab, for focus wiring from other panes. */
    fun tabView(i: Int): View? = labels.getOrNull(i)
}
