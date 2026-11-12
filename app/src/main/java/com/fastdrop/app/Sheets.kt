package com.fastdrop.app

import android.content.Context
import android.graphics.Outline
import android.view.ViewOutlineProvider
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.LinearLayout

/**
 * Modal sheets: a scrim fades in and the sheet slides up from the bottom (SURFACE, 12 dp top
 * corners, 1 px LINE top edge, drag down to dismiss). On TV / wide windows it is a 600 dp centre
 * overlay instead. Back-press is handled by the caller, which should call [Handle.dismiss].
 * [padded] false lets the content run edge to edge (the transfer and offer panels); then
 * [dragArea] is the part of the content that drags the sheet. [full] makes a phone sheet full height.
 */
object Sheets {
    class Handle internal constructor() {
        internal var closer: (() -> Unit)? = null

        /** True from show() until dismissal starts. */
        var showing: Boolean = false
            internal set

        fun dismiss() {
            closer?.invoke()
        }
    }

    /** Linear layout that never grows taller than [frac] of the space it is offered. */
    private class SheetLayout(ctx: Context, private val frac: Float) : LinearLayout(ctx) {
        override fun onMeasure(w: Int, h: Int) {
            val size = MeasureSpec.getSize(h)
            if (size > 0 && MeasureSpec.getMode(h) != MeasureSpec.UNSPECIFIED) {
                val cap = (size * frac).toInt()
                val mode = if (MeasureSpec.getMode(h) == MeasureSpec.EXACTLY) MeasureSpec.EXACTLY else MeasureSpec.AT_MOST
                super.onMeasure(w, MeasureSpec.makeMeasureSpec(minOf(size, cap), mode))
            } else super.onMeasure(w, h)
        }
    }

    private const val K_BLOCK = 0x7f0fff10

    /** Per-root count of open sheets plus the screen's original focusability, restored on the last close. */
    private class BlockState {
        var count = 0
        val saved = ArrayList<Pair<ViewGroup, Int>>()
        var focused: java.lang.ref.WeakReference<View>? = null
        /** Open cancellable sheets, oldest first, so Back can close the top one. */
        val open = ArrayList<Handle>()
    }

    /** Closes the newest cancellable sheet on [root]. True when one was closed (Back key). */
    fun dismissTop(root: FrameLayout): Boolean {
        val st = root.getTag(K_BLOCK) as? BlockState ?: return false
        st.open.removeAll { !it.showing }
        val h = st.open.lastOrNull() ?: return false
        h.dismiss()
        st.open.remove(h)
        return true
    }

    private fun blockOpen(root: FrameLayout) {
        val st = (root.getTag(K_BLOCK) as? BlockState) ?: BlockState().also { root.setTag(K_BLOCK, it) }
        if (st.count++ == 0) {
            st.saved.clear()
            st.focused = root.findFocus()?.let { java.lang.ref.WeakReference(it) }
            for (i in 0 until root.childCount) {
                val ch = root.getChildAt(i)
                if (ch is ViewGroup) {
                    st.saved.add(Pair(ch, ch.descendantFocusability))
                    ch.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                }
            }
        } else {
            // Children added since the first sheet opened (not sheet containers) are blocked too.
        }
    }

    private fun blockClose(root: FrameLayout) {
        val st = root.getTag(K_BLOCK) as? BlockState ?: return
        if (st.count > 0 && --st.count == 0) {
            for ((g, mode) in st.saved) g.descendantFocusability = mode
            st.saved.clear()
            // Give D-pad focus back to what had it before the first sheet opened.
            val prev = st.focused?.get()
            st.focused = null
            if (prev != null) root.post {
                if (prev.isAttachedToWindow && prev.isFocusable && root.findFocus() == null && !prev.isInTouchMode) prev.requestFocus()
            }
        }
    }

    fun show(
        root: FrameLayout, content: View, cancellable: Boolean = true,
        padded: Boolean = true, full: Boolean = false, dragArea: View? = null,
        onDismiss: (() -> Unit)? = null
    ): Handle {
        val ctx = root.context
        val handle = Handle()
        val dialog = Ui.wide(ctx)
        val animate = Anim.on(ctx)
        val d = ctx.resources.displayMetrics.density
        fun dp(v: Int) = (v * d + 0.5f).toInt()

        // Block D-pad focus from wandering into the screen behind (TV).
        blockOpen(root)

        var onConfig: (() -> Unit)? = null
        val container = object : FrameLayout(ctx) {
            override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
                super.onConfigurationChanged(newConfig)
                onConfig?.invoke()
            }
        }.apply {
            isClickable = true       // swallow touches meant for the screen behind
            isFocusable = true
        }
        val scrim = View(ctx).apply {
            setBackgroundColor(Theme.SCRIM)
            alpha = if (animate) 0f else 1f
            setOnClickListener { if (cancellable) handle.dismiss() }
        }
        container.addView(scrim, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val sheet = SheetLayout(ctx, if (full && !dialog) 1f else 0.92f).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            val r = Ui.dpf(ctx, Theme.R_SHEET)
            background = Ui.panelBackground(ctx, Theme.R_SHEET, allCorners = dialog)
            // Clip the content (the colour panels) to the rounded corners.
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height + (if (dialog) 0 else r.toInt()), r)
                }
            }
        }
        val baseH = if (padded) Ui.gutter(ctx) else 0
        val baseTop = if (padded) dp(if (dialog) 20 else 4) + Theme.rule(ctx) else 0
        val baseBottom = if (padded) dp(20) else 0
        var bottomInset = 0
        var topInset = 0
        var lastKey = Int.MIN_VALUE
        fun pad() = sheet.setPadding(baseH, baseTop, baseH, baseBottom + bottomInset)

        // Drag target (phone): an invisible strip above padded content, or the caller's panel.
        var handleArea: View? = dragArea
        if (!dialog && padded) {
            val area = FrameLayout(ctx)
            sheet.addView(area, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(16)))
            handleArea = area
        }
        (content.parent as? ViewGroup)?.removeView(content)
        sheet.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            if (full && !dialog) 0 else ViewGroup.LayoutParams.WRAP_CONTENT, if (full && !dialog) 1f else 0f))
        pad()

        val screenW = ctx.resources.configuration.screenWidthDp
        val lp = if (dialog) {
            FrameLayout.LayoutParams(dp(minOf(600, screenW - 48)), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER)
        } else {
            FrameLayout.LayoutParams(
                if (screenW >= 600) dp(560) else ViewGroup.LayoutParams.MATCH_PARENT,
                if (full) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            )
        }
        container.addView(sheet, lp)
        // Rotation / window resize: re-measure the width from the new configuration.
        onConfig = {
            val w = ctx.resources.configuration.screenWidthDp
            val p = sheet.layoutParams as FrameLayout.LayoutParams
            p.width = if (dialog) dp(minOf(600, w - 48)) else if (w >= 600) dp(560) else ViewGroup.LayoutParams.MATCH_PARENT
            sheet.layoutParams = p
        }

        // Keep clear of the system bars.
        fun applyInsets(i: IntArray) {
            val key = ((i[0] * 31 + i[1]) * 31 + i[2]) * 31 + i[3]
            if (key == lastKey) return
            lastKey = key
            bottomInset = i[3]
            topInset = i[1]
            pad()
            (sheet.layoutParams as FrameLayout.LayoutParams).apply {
                leftMargin = i[0]; rightMargin = i[2]
                if (dialog) { topMargin = topInset; bottomMargin = bottomInset }
            }
            sheet.requestLayout()
        }
        container.setOnApplyWindowInsetsListener { _, ins ->
            applyInsets(Ui.insetsOf(ins, true))
            ins
        }

        // ---- dismissal
        if (cancellable) (root.getTag(K_BLOCK) as? BlockState)?.open?.add(handle)
        var finished = false
        fun finish() {
            if (finished) return
            finished = true
            handle.showing = false
            blockClose(root)
            if (container.parent === root) root.removeView(container)
            onDismiss?.invoke()
        }
        handle.showing = true
        handle.closer = close@{
            if (!handle.showing) return@close
            handle.showing = false
            if (!animate || !container.isAttachedToWindow) {
                finish()
                return@close
            }
            scrim.animate().cancel()
            sheet.animate().cancel()
            scrim.animate().alpha(0f).setDuration(Theme.D_SHEET_OUT).setInterpolator(Theme.EASE_EXIT).start()
            val anim = sheet.animate().setDuration(Theme.D_SHEET_OUT).setInterpolator(Theme.EASE_EXIT)
            if (dialog) anim.alpha(0f)
            else anim.translationY(sheet.height.toFloat() + dp(24))
            anim.withEndAction { finish() }.start()
        }
        container.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {}
            override fun onViewDetachedFromWindow(v: View) {
                // Removed from outside (e.g. the screen was torn down): clean up once.
                if (!finished) finish()
            }
        })

        // ---- drag to dismiss
        val area = handleArea
        if (area != null && cancellable) {
            var startY = 0f
            var lastY = 0f
            var lastT = 0L
            var speed = 0f
            area.setOnTouchListener { _, ev ->
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        startY = ev.rawY; lastY = ev.rawY; lastT = ev.eventTime; speed = 0f
                        sheet.animate().cancel()
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dy = maxOf(0f, ev.rawY - startY)
                        sheet.translationY = dy
                        scrim.alpha = 1f - 0.8f * (dy / maxOf(1, sheet.height)).coerceIn(0f, 1f)
                        val dt = ev.eventTime - lastT
                        if (dt > 0) speed = (ev.rawY - lastY) / dt   // px per ms
                        lastY = ev.rawY; lastT = ev.eventTime
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        val dy = sheet.translationY
                        if (ev.actionMasked == MotionEvent.ACTION_UP && (dy > sheet.height * 0.3f || speed > 1.2f)) {
                            handle.dismiss()
                        } else {
                            sheet.animate().translationY(0f).setDuration(Theme.D_SHEET).setInterpolator(Theme.EASE_ENTER).start()
                            scrim.animate().alpha(1f).setDuration(Theme.D_SHEET).start()
                        }
                    }
                }
                true
            }
        }

        root.addView(container, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        container.requestApplyInsets()
        container.post { if (container.isAttachedToWindow) applyInsets(Ui.currentInsets(container, true)) }

        // ---- entrance (set the start pose right before the first draw so nothing flashes)
        if (animate) {
            sheet.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    sheet.viewTreeObserver.removeOnPreDrawListener(this)
                    if (!handle.showing) return true
                    scrim.animate().alpha(1f).setDuration(Theme.D_SHEET).setInterpolator(Theme.EASE_ENTER).start()
                    if (dialog) {
                        sheet.alpha = 0f
                        sheet.animate().alpha(1f)
                            .setDuration(Theme.D_STATE).setInterpolator(Theme.EASE_ENTER).start()
                    } else {
                        sheet.translationY = sheet.height.toFloat() + dp(24)
                        sheet.animate().translationY(0f)
                            .setDuration(Theme.D_SHEET).setInterpolator(Theme.EASE_ENTER).start()
                    }
                    return true
                }
            })
        }

        // Put D-pad focus inside the sheet unless the caller already did.
        container.post {
            if (container.isAttachedToWindow && container.findFocus() == null) {
                if (!content.requestFocus()) container.requestFocus()
            }
        }
        return handle
    }
}
