package com.fastdrop.app

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import java.util.Locale

/** Small shared helpers for the two picker screens (PickerActivity and BrowserActivity). */
internal object PickUi {

    /** Transparent system bars, content drawn under them; screens pad with Ui.applyInsets. */
    @Suppress("DEPRECATION")
    fun edgeToEdge(a: Activity) {
        val w = a.window
        w.setBackgroundDrawable(ColorDrawable(Theme.BG))
        w.statusBarColor = Color.TRANSPARENT
        w.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= 29) {
            w.isNavigationBarContrastEnforced = false
            w.isStatusBarContrastEnforced = false
        }
        if (Build.VERSION.SDK_INT >= 30) {
            w.setDecorFitsSystemWindows(false)
        } else {
            w.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }
    }

    /** Back that works with the predictive-back callback (33+) and the old callback below. */
    fun registerBack(a: Activity, onBack: () -> Unit) {
        if (Build.VERSION.SDK_INT >= 33) {
            a.onBackInvokedDispatcher.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT, OnBackInvokedCallback { onBack() })
        }
    }

    /** D-pad focus frame for list and grid items: a flat 3 dp INK outline, no fill, drawn over the item. */
    fun selector(ctx: Context, @Suppress("UNUSED_PARAMETER") radiusDp: Int): GradientDrawable = GradientDrawable().apply {
        setColor(Color.TRANSPARENT)
        cornerRadius = 0f
        setStroke(Theme.dp(ctx, 3), Theme.INK)
    }

    fun rounded(ctx: Context, color: Int, radiusDp: Int, stroke: Int = 0, strokeDp: Int = 1): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = Theme.dp(ctx, radiusDp).toFloat()
            if (stroke != 0) setStroke(Theme.dp(ctx, strokeDp), stroke)
        }

    fun roundOutline(radiusPx: Float): ViewOutlineProvider = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: android.graphics.Outline) {
            outline.setRoundRect(0, 0, view.width, view.height, radiusPx)
        }
    }

    fun fmtDuration(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, sec)
        else String.format(Locale.US, "%d:%02d", m, sec)
    }

    /**
     * Square selection mark (22 dp, 6 dp radius). On: solid [Job.SEND] with an ink check, so it reads as
     * "this will be sent". Off: a LINE_STRONG outline (a dark scrim on photos).
     */
    class Check(ctx: Context, sizeDp: Int, private val onPhoto: Boolean) : FrameLayout(ctx) {
        private val mark = ImageView(ctx)
        private var on: Boolean? = null

        init {
            val s = Theme.dp(ctx, if (sizeDp > 22) 22 else sizeDp)
            mark.setImageResource(R.drawable.ic_check)
            mark.setColorFilter(Theme.ON_DARK, PorterDuff.Mode.SRC_IN)
            mark.scaleType = ImageView.ScaleType.FIT_CENTER
            val p = s / 5
            mark.setPadding(p, p, p, p)
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            addView(mark, LayoutParams(s, s, Gravity.CENTER))
            set(false, false)
        }

        fun set(v: Boolean, animate: Boolean) {
            if (on == v) return
            on = v
            val ctx = context
            val r = Theme.dp(ctx, Theme.R_CTRL).toFloat()
            background = GradientDrawable().apply {
                cornerRadius = r
                if (v) setColor(Theme.C_SEND)
                else {
                    setColor(if (onPhoto) 0x59000000 else Color.TRANSPARENT)
                    setStroke(Theme.rule(ctx) * 2, if (onPhoto) Color.WHITE else Theme.LINE_STRONG)
                }
            }
            mark.animate().cancel()
            if (!v) {
                mark.alpha = 0f
            } else if (animate && Anim.on(ctx)) {
                mark.alpha = 0f
                mark.animate().alpha(1f).setDuration(Theme.D_TAP).setInterpolator(Theme.EASE_ENTER).start()
            } else mark.alpha = 1f
        }
    }

    /**
     * The picker's docked selection bar: SURFACE, 1 px rule on top, "12 selected" in T_ROW with the
     * total size under it, and an orange action button. Appears and disappears with the selection.
     */
    class FloatingBar(ctx: Context, private val label: String, onNext: () -> Unit) : LinearLayout(ctx) {
        private val countText = Ui.text(ctx, "", Type.ROW)
        private val subText = Ui.text(ctx, "", Type.META)
        val button: TextView = Ui.jobButton(ctx, label, Job.SEND, null, onNext)
        private var shown = false

        init {
            visibility = GONE
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Ui.panelBackground(ctx, 0f)
            val g = Ui.gutter(ctx)
            setPadding(g, Theme.dp(ctx, 12) + Theme.rule(ctx), g, Theme.dp(ctx, 12))
            val col = LinearLayout(ctx).apply { orientation = VERTICAL }
            col.addView(countText)
            col.addView(subText, LayoutParams(-2, -2).apply { topMargin = Theme.dp(ctx, 2) })
            addView(col, LayoutParams(0, -2, 1f))
            addView(button, LayoutParams(-2, -2).apply { marginStart = Theme.dp(ctx, 12) })
            Ui.applyInsets(this, top = false, bottom = true)
        }

        fun update(count: Int, sub: String, noun: String = context.getString(R.string.pick_selected_word), anim: Boolean = true) =
            updateLine(if (count > 0) "$count $noun" else "", sub, count > 0, anim)

        /** The same bar for a custom line (the Text tab shows "Text, 128 characters"). */
        fun updateLine(title: String, sub: String, on: Boolean, anim: Boolean = true) {
            if (on) {
                countText.text = title
                subText.text = sub
                subText.visibility = if (sub.isEmpty()) GONE else VISIBLE
                show(anim)
            } else hide(anim)
        }

        fun setBusy(busy: Boolean) {
            button.isEnabled = !busy
            button.text = if (busy) context.getString(R.string.pick_preparing) else label
        }

        private fun show(anim: Boolean) {
            if (shown) return
            shown = true
            animate().cancel()
            visibility = VISIBLE
            if (anim && Anim.on(context)) {
                translationY = Theme.dp(context, 80).toFloat()
                animate().translationY(0f).setDuration(Theme.D_SHEET).setInterpolator(Theme.EASE_ENTER).start()
            } else translationY = 0f
        }

        private fun hide(anim: Boolean) {
            if (!shown) return
            shown = false
            animate().cancel()
            if (anim && Anim.on(context)) {
                animate().translationY(Theme.dp(context, 80).toFloat()).setDuration(Theme.D_SHEET_OUT)
                    .setInterpolator(Theme.EASE_EXIT)
                    .withEndAction { if (!shown) visibility = GONE }.start()
            } else visibility = GONE
        }
    }
}
