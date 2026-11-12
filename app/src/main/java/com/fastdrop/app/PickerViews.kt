package com.fastdrop.app

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView

/** Grid cell and list-row views for the picker, plus the pane that hosts a list with its states. */

internal class SquareFrame(ctx: Context) : FrameLayout(ctx) {
    override fun onMeasure(w: Int, h: Int) = super.onMeasure(w, w)
}

/** A square thumbnail: 0 radius, selected = 4 dp orange frame inside the cell and an orange check. */
internal class Cell(
    val root: SquareFrame, val iv: ImageView,
    val dur: TextView, val check: PickUi.Check, val ring: View,
)

internal object PickerViews {

    fun newCell(ctx: Context): Cell {
        val root = SquareFrame(ctx)
        root.setBackgroundColor(Theme.SURFACE_2)
        val iv = ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        root.addView(iv, FrameLayout.LayoutParams(-1, -1))
        val dur = Ui.text(ctx, "", Type.MONO, Color.WHITE).apply {
            background = ColorDrawable(0x99000000.toInt())
            setPadding(Theme.dp(ctx, 6), Theme.dp(ctx, 2), Theme.dp(ctx, 6), Theme.dp(ctx, 2))
            visibility = View.GONE
        }
        root.addView(dur, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply {
            leftMargin = Theme.dp(ctx, 6); bottomMargin = Theme.dp(ctx, 6)
        })
        val ring = View(ctx).apply {
            background = GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                setStroke(Theme.dp(ctx, 4), Theme.C_SEND)
            }
            alpha = 0f
        }
        root.addView(ring, FrameLayout.LayoutParams(-1, -1))
        val check = PickUi.Check(ctx, 22, true)
        root.addView(check, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply {
            topMargin = Theme.dp(ctx, 8); rightMargin = Theme.dp(ctx, 8)
        })
        return Cell(root, iv, dur, check, ring).also { root.tag = it }
    }

    fun bindCell(c: Cell, sel: Boolean, animate: Boolean) {
        c.ring.animate().cancel()
        if (animate && Anim.on(c.root.context)) {
            c.ring.animate().alpha(if (sel) 1f else 0f).setStartDelay(0).setDuration(Theme.D_TAP).start()
        } else c.ring.alpha = if (sel) 1f else 0f
        c.check.set(sel, animate)
    }

    class RowHolder(
        val root: LinearLayout, val iv: ImageView, val title: TextView, val sub: TextView,
        val chip: TextView, val check: PickUi.Check,
    )

    /** A 64 dp list row: 40 dp thumbnail or icon, T_ROW title, T_META subtitle, optional tag, check. Full-bleed, rule below. */
    fun newRow(ctx: Context): RowHolder {
        val tv = Ui.tv(ctx)
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = Theme.dp(ctx, 64)
            val g = if (tv) Theme.dp(ctx, 16) else Ui.gutter(ctx)
            setPadding(g, Theme.dp(ctx, 8), g, Theme.dp(ctx, 8))
        }
        val s = Theme.dp(ctx, if (tv) 48 else 40)
        val iv = ImageView(ctx).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = ColorDrawable(Theme.SURFACE_2)
            outlineProvider = PickUi.roundOutline(Theme.dp(ctx, Theme.R_CTRL).toFloat())
            clipToOutline = true
        }
        root.addView(iv, LinearLayout.LayoutParams(s, s).apply { rightMargin = Theme.dp(ctx, 16) })
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val title = Ui.text(ctx, "", Type.ROW).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        val sub = Ui.text(ctx, "", Type.META).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        col.addView(title)
        col.addView(sub, LinearLayout.LayoutParams(-2, -2).apply { topMargin = Theme.dp(ctx, 2) })
        root.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        val chip = Ui.tag(ctx, ctx.getString(R.string.pick_split)).apply { visibility = View.GONE }
        root.addView(chip, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = Theme.dp(ctx, 8) })
        val check = PickUi.Check(ctx, 22, false)
        root.addView(check, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = Theme.dp(ctx, 12) })
        return RowHolder(root, iv, title, sub, chip, check).also { root.tag = it }
    }

    fun styleList(ctx: Context, l: ListView) {
        l.divider = ColorDrawable(Theme.LINE)
        l.dividerHeight = Theme.rule(ctx)
        l.selector = PickUi.selector(ctx, 0)
        l.setDrawSelectorOnTop(true)
        l.cacheColorHint = Color.TRANSPARENT
        l.clipToPadding = false
        l.overScrollMode = View.OVER_SCROLL_NEVER
        l.isVerticalScrollBarEnabled = false
    }
}

internal class RowsAdapter(
    private val ctx: Context, private val size: () -> Int, private val bind: (PickerViews.RowHolder, Int) -> Unit,
) : BaseAdapter() {
    override fun getCount() = size()
    override fun getItem(position: Int): Any? = null
    override fun getItemId(position: Int) = position.toLong()
    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        val h = (convertView?.tag as? PickerViews.RowHolder) ?: PickerViews.newRow(ctx)
        bind(h, position)
        return h.root
    }
}

internal class GridAdapter(
    private val ctx: Context, private val items: () -> List<MediaItem>?, private val video: Boolean,
    private val px: Int, private val isPicked: (MediaItem) -> Boolean,
) : BaseAdapter() {
    override fun getCount() = items()?.size ?: 0
    override fun getItem(position: Int): Any? = items()?.getOrNull(position)
    override fun getItemId(position: Int) = position.toLong()
    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        val c = (convertView?.tag as? Cell) ?: PickerViews.newCell(ctx)
        val m = items()!![position]
        Thumbs.media(c.iv, m, px)
        if (video && m.durationMs > 0) {
            c.dur.text = PickUi.fmtDuration(m.durationMs)
            c.dur.visibility = View.VISIBLE
        } else c.dur.visibility = View.GONE
        c.root.contentDescription = m.name
        PickerViews.bindCell(c, isPicked(m), false)
        return c.root
    }
}

/**
 * A list or grid with loading / empty / permission states in one pattern (docs 4.2): a 32 dp INK_2
 * icon, T_ROW title, T_META reason and an optional outlined button. [top] sits above the list.
 */
internal class PickPane(
    private val ctx: Context, val list: AbsListView, val adapter: BaseAdapter, top: View?,
    private val bottomInset: () -> Int,
) {
    val view: View
    private val stateBox = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(Theme.dp(ctx, 32), Theme.dp(ctx, 24), Theme.dp(ctx, 32), Theme.dp(ctx, 80))
        visibility = View.GONE
    }
    private val side = if (Ui.tv(ctx)) Theme.dp(ctx, 16) else 0

    init {
        val frame = FrameLayout(ctx)
        frame.addView(list, FrameLayout.LayoutParams(-1, -1))
        frame.addView(stateBox, FrameLayout.LayoutParams(-1, -1))
        view = if (top == null) frame else LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(top, LinearLayout.LayoutParams(-1, -2))
            addView(frame, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        applyPadding()
    }

    fun applyPadding() {
        val grid = list is android.widget.GridView
        val h = if (grid) (if (Ui.tv(ctx)) Theme.dp(ctx, 16) else Ui.gutter(ctx)) else side
        list.setPadding(h, if (grid) Theme.dp(ctx, 4) else 0, h, Theme.dp(ctx, 96) + bottomInset())
    }

    fun showContent() {
        if (stateBox.visibility == View.VISIBLE) Anim.crossfade(stateBox, list)
    }

    fun showMessage(
        icon: Int, title: String, msg: String,
        action: String? = null, onAction: (() -> Unit)? = null, loading: Boolean = false,
    ) {
        stateBox.removeAllViews()
        if (loading) stateBox.addView(ScanTicks(ctx), LinearLayout.LayoutParams(-2, -2))
        else stateBox.addView(Ui.icon(ctx, icon, Theme.INK_2, 32), LinearLayout.LayoutParams(Theme.dp(ctx, 32), Theme.dp(ctx, 32)))
        stateBox.addView(Ui.text(ctx, title, Type.ROW).apply { gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(-2, -2).apply { topMargin = Theme.dp(ctx, 16) })
        stateBox.addView(Ui.text(ctx, msg, Type.META).apply { gravity = Gravity.CENTER; maxWidth = Theme.dp(ctx, 320) },
            LinearLayout.LayoutParams(-2, -2).apply { topMargin = Theme.dp(ctx, 4) })
        if (action != null && onAction != null) {
            stateBox.addView(Ui.secondaryButton(ctx, action, null, false, onAction),
                LinearLayout.LayoutParams(-2, -2).apply { topMargin = Theme.dp(ctx, 20) })
        }
        if (stateBox.visibility != View.VISIBLE) Anim.crossfade(list, stateBox)
    }

    fun showLoading(msg: String) = showMessage(R.drawable.ic_refresh, msg, ctx.getString(R.string.pick_loading_meta), loading = true)
}
