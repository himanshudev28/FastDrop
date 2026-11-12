package com.fastdrop.app

import android.annotation.SuppressLint
import android.app.UiModeManager
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.os.Build
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.text.TextUtils
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * The FastDrop design system: factory functions for every reusable component.
 * Everything reads [Theme] tokens, checks [Theme.animationsOn], and is usable with a TV remote.
 */
object Ui {
    // View tag keys (any id with a non-system package byte is valid for setTag(int, Object)).
    private const val K_RADIUS = 0x7f0fff01
    private const val K_SCALE = 0x7f0fff02
    private const val K_RING = 0x7f0fff03
    private const val K_SNACK = 0x7f0fff04
    private const val K_HIDE = 0x7f0fff05
    private const val K_LAYOUT = 0x7f0fff06

    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    fun dp(ctx: Context, v: Int): Int = Theme.dp(ctx, v)
    internal fun dpf(ctx: Context, v: Float): Float = v * ctx.resources.displayMetrics.density

    /** True on Android TV (falls back to UiModeManager if Hub is not initialised yet). */
    fun tv(ctx: Context): Boolean = try {
        Hub.isTv
    } catch (_: Throwable) {
        (ctx.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager)?.currentModeType ==
            Configuration.UI_MODE_TYPE_TELEVISION
    }

    /** TV or a large window: screens use two columns, sheets become centred dialogs. */
    fun wide(ctx: Context): Boolean = tv(ctx) || ctx.resources.configuration.screenWidthDp >= 820

    // ---------------------------------------------------------------- text & icons

    fun text(
        ctx: Context, s: CharSequence, sp: Float = 15f,
        color: Int = Theme.TEXT, font: Typeface = Theme.REGULAR
    ): TextView = TextView(ctx).apply {
        text = s
        textSize = sp
        setTextColor(color)
        typeface = font
        includeFontPadding = false
        setLineSpacing(0f, 1.12f)
    }

    fun title(ctx: Context, s: CharSequence, sp: Float = 28f): TextView =
        text(ctx, s, sp, Theme.TEXT, Theme.BLACK).apply { letterSpacing = -0.02f }

    /** Small uppercase section label. */
    fun label(ctx: Context, s: CharSequence): TextView =
        text(ctx, s.toString().uppercase(), 11.5f, Theme.MUTED, Theme.MEDIUM).apply { letterSpacing = 0.1f }

    fun icon(ctx: Context, res: Int, tint: Int = Theme.TEXT, sizeDp: Int = 24): ImageView =
        ImageView(ctx).apply {
            setImageResource(res)
            setColorFilter(tint, android.graphics.PorterDuff.Mode.SRC_IN)
            scaleType = ImageView.ScaleType.FIT_CENTER
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            val s = dp(ctx, sizeDp)
            layoutParams = ViewGroup.LayoutParams(s, s)
        }

    fun gradient(
        colors: IntArray, radiusPx: Float,
        orientation: GradientDrawable.Orientation = GradientDrawable.Orientation.TL_BR
    ): GradientDrawable {
        // GradientDrawable needs >= 2 colours.
        val c = if (colors.size >= 2) colors else intArrayOf(colors.firstOrNull() ?: Theme.ACCENT, colors.firstOrNull() ?: Theme.ACCENT)
        return GradientDrawable(orientation, c).apply { cornerRadius = radiusPx }
    }

    // ---------------------------------------------------------------- drawable helpers

    private fun rounded(color: Int, radiusPx: Float, strokeColor: Int = 0, strokePx: Int = 0): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radiusPx
            if (strokePx > 0) setStroke(strokePx, strokeColor)
        }

    private fun oval(color: Int, strokeColor: Int = 0, strokePx: Int = 0): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
            if (strokePx > 0) setStroke(strokePx, strokeColor)
        }

    private fun ripple(content: Drawable?, radiusPx: Float, isOval: Boolean = false, color: Int = 0x33FFFFFF): RippleDrawable {
        val mask = GradientDrawable().apply {
            shape = if (isOval) GradientDrawable.OVAL else GradientDrawable.RECTANGLE
            cornerRadius = radiusPx
            setColor(Color.WHITE)
        }
        return RippleDrawable(ColorStateList.valueOf(color), content, mask)
    }

    /** Glass surface: translucent SURFACE with a hairline stroke. */
    private fun glass(ctx: Context, radiusDp: Float, fill: Int = Theme.alpha(Theme.SURFACE, 0.85f)): GradientDrawable =
        rounded(fill, dpf(ctx, radiusDp), Theme.STROKE, maxOf(1, dp(ctx, 1)))

    /** Transparent at rest, faint white fill while focused (TV). */
    private fun focusFill(radiusPx: Float): StateListDrawable = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), rounded(0x14FFFFFF, radiusPx))
        addState(intArrayOf(), ColorDrawable(Color.TRANSPARENT))
    }

    /** Soft coloured glow under a pill (API 28+ coloured shadows; plain elevation is skipped below). */
    private fun glow(v: View, color: Int, elevDp: Float = 10f) {
        if (Build.VERSION.SDK_INT >= 28) {
            v.elevation = dpf(v.context, elevDp)
            v.outlineSpotShadowColor = color
            v.outlineAmbientShadowColor = Theme.alpha(color, 0.5f)
        }
    }

    // ---------------------------------------------------------------- focus / press / haptics

    /** Focus ring drawn in the view overlay so it works on any background. */
    private class FocusRing(private val radius: Float, private val ringW: Float, private val glowW: Float) : Drawable() {
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = ringW; color = Color.WHITE
        }
        private val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = glowW; color = Theme.ACCENT
        }
        private val rect = RectF()
        private var level = 0f
        private var anim: ValueAnimator? = null

        fun show(on: Boolean, animate: Boolean) {
            anim?.cancel()
            val to = if (on) 1f else 0f
            if (!animate) { level = to; invalidateSelf(); return }
            anim = ValueAnimator.ofFloat(level, to).apply {
                duration = Theme.DUR_FAST
                interpolator = Theme.EASE_OUT
                addUpdateListener { level = it.animatedValue as Float; invalidateSelf() }
                start()
            }
        }

        override fun draw(c: Canvas) {
            if (level <= 0.01f) return
            val b = bounds
            // Soft halo first, crisp ring on top.
            val g = glowW / 2f
            rect.set(b.left - g + ringW, b.top - g + ringW, b.right + g - ringW, b.bottom + g - ringW)
            halo.alpha = (90 * level).toInt()
            c.drawRoundRect(rect, radius + g, radius + g, halo)
            val h = ringW / 2f
            rect.set(b.left - h, b.top - h, b.right + h, b.bottom + h)
            ring.alpha = (255 * level).toInt()
            c.drawRoundRect(rect, radius + h, radius + h, ring)
        }

        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(cf: android.graphics.ColorFilter?) {}
        @Suppress("OVERRIDE_DEPRECATION")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    /**
     * TV focus treatment: focusable, scales up a little and shows a white ring with an accent halo.
     * [radiusDp] should match the view's corner radius.
     */
    fun focusable(v: View, scale: Float = 1.04f, radiusDp: Float = 18f) {
        val ctx = v.context
        v.isFocusable = true
        v.setTag(K_SCALE, scale)
        v.setTag(K_RADIUS, radiusDp)
        (v.getTag(K_RING) as? FocusRing)?.let { v.overlay.remove(it) }
        (v.getTag(K_LAYOUT) as? View.OnLayoutChangeListener)?.let { v.removeOnLayoutChangeListener(it) }
        val ring = FocusRing(dpf(ctx, radiusDp), dpf(ctx, 2f), dpf(ctx, 7f))
        v.overlay.add(ring)
        v.setTag(K_RING, ring)
        if (v.width > 0) ring.setBounds(0, 0, v.width, v.height)
        val ll = View.OnLayoutChangeListener { _, l, t, r, b, _, _, _, _ -> ring.setBounds(0, 0, r - l, b - t) }
        v.addOnLayoutChangeListener(ll)
        v.setTag(K_LAYOUT, ll)
        v.setOnFocusChangeListener { view, has ->
            val on = Anim.on(view.context)
            ring.show(has, on)
            val s = if (has) scale else 1f
            val z = if (has) dpf(view.context, 6f) else 0f
            if (on) {
                view.animate().scaleX(s).scaleY(s).translationZ(z)
                    .setDuration(Theme.DUR_FAST).setInterpolator(Theme.EASE_OUT).start()
            } else {
                view.scaleX = s; view.scaleY = s; view.translationZ = z
            }
        }
    }

    private fun baseScale(v: View): Float =
        if (v.isFocused) (v.getTag(K_SCALE) as? Float ?: 1f) else 1f

    private fun pressTo(v: View, s: Float) {
        if (!Anim.on(v.context)) { v.scaleX = s; v.scaleY = s; return }
        val down = s < 1f
        v.animate().scaleX(s).scaleY(s)
            .setDuration(if (down) 90L else Theme.DUR)
            .setInterpolator(if (down) Theme.EASE_OUT else Theme.SPRING)
            .start()
    }

    /** Spring press feedback (scale 0.97) for touch and D-pad centre. Never consumes the event. */
    @SuppressLint("ClickableViewAccessibility")
    fun pressable(v: View) {
        v.setOnTouchListener { view, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> pressTo(view, baseScale(view) * 0.97f)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> pressTo(view, baseScale(view))
            }
            false
        }
        v.setOnKeyListener { view, code, ev ->
            if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER ||
                code == KeyEvent.KEYCODE_NUMPAD_ENTER || code == KeyEvent.KEYCODE_BUTTON_A
            ) {
                if (ev.action == KeyEvent.ACTION_DOWN && ev.repeatCount == 0) pressTo(view, baseScale(view) * 0.97f)
                else if (ev.action == KeyEvent.ACTION_UP) pressTo(view, baseScale(view))
            }
            false
        }
    }

    fun haptic(v: View) {
        try {
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        } catch (_: Throwable) { }
    }

    private fun interactive(v: View, radiusDp: Float, scale: Float = 1.04f, onClick: () -> Unit) {
        focusable(v, scale, radiusDp)
        pressable(v)
        v.setOnClickListener { haptic(it); onClick() }
    }

    // ---------------------------------------------------------------- buttons

    /** Darkens [c] just enough that white text on it reaches [min]:1 contrast. */
    internal fun forWhiteText(c: Int, min: Double = 4.5): Int {
        fun lin(v: Int): Double { val x = v / 255.0; return if (x <= 0.03928) x / 12.92 else Math.pow((x + 0.055) / 1.055, 2.4) }
        fun contrast(col: Int): Double {
            val l = 0.2126 * lin(Color.red(col)) + 0.7152 * lin(Color.green(col)) + 0.0722 * lin(Color.blue(col))
            return 1.05 / (l + 0.05)
        }
        var col = c
        var n = 0
        while (contrast(col) < min && n < 40) {
            col = Color.rgb((Color.red(col) * 0.94f).toInt(), (Color.green(col) * 0.94f).toInt(), (Color.blue(col) * 0.94f).toInt())
            n++
        }
        return col
    }

    fun primaryButton(
        ctx: Context, label: String, colors: IntArray = Theme.BRAND, icon: Int? = null, onClick: () -> Unit
    ): TextView = TextView(ctx).apply {
        text = label
        textSize = 16f
        typeface = Theme.BOLD
        setTextColor(Color.WHITE)
        isAllCaps = false
        includeFontPadding = false
        gravity = Gravity.CENTER
        minHeight = dp(ctx, if (tv(ctx)) 60 else 54)
        setPadding(dp(ctx, 24), dp(ctx, 12), dp(ctx, 24), dp(ctx, 12))
        val r = dpf(ctx, Theme.RADIUS_BUTTON)
        background = ripple(gradient(IntArray(colors.size) { forWhiteText(colors[it]) }, r), r, color = 0x40FFFFFF)
        if (icon != null) {
            val d = ctx.getDrawable(icon)?.mutate()
            if (d != null) {
                val s = dp(ctx, 20)
                d.setTint(Color.WHITE)
                d.setBounds(0, 0, s, s)
                setCompoundDrawablesRelative(d, null, null, null)
                compoundDrawablePadding = dp(ctx, 10)
            }
        }
        glow(this, Theme.alpha(colors.firstOrNull() ?: Theme.ACCENT, 0.9f))
        interactive(this, Theme.RADIUS_BUTTON, 1.05f, onClick)
    }

    fun secondaryButton(ctx: Context, label: String, icon: Int? = null, onClick: () -> Unit): TextView =
        TextView(ctx).apply {
            text = label
            textSize = 15f
            typeface = Theme.MEDIUM
            setTextColor(Theme.TEXT)
            isAllCaps = false
            includeFontPadding = false
            gravity = Gravity.CENTER
            minHeight = dp(ctx, if (tv(ctx)) 60 else 54)
            setPadding(dp(ctx, 22), dp(ctx, 12), dp(ctx, 22), dp(ctx, 12))
            val r = dpf(ctx, Theme.RADIUS_BUTTON)
            background = ripple(rounded(Theme.SURFACE_2, r, Theme.STROKE_STRONG, maxOf(1, dp(ctx, 1))), r)
            if (icon != null) {
                val d = ctx.getDrawable(icon)?.mutate()
                if (d != null) {
                    val s = dp(ctx, 20)
                    d.setTint(Theme.TEXT)
                    d.setBounds(0, 0, s, s)
                    setCompoundDrawablesRelative(d, null, null, null)
                    compoundDrawablePadding = dp(ctx, 10)
                }
            }
            interactive(this, Theme.RADIUS_BUTTON, 1.05f, onClick)
        }

    fun iconButton(ctx: Context, icon: Int, desc: String, onClick: () -> Unit): ImageView =
        ImageView(ctx).apply {
            setImageResource(icon)
            setColorFilter(Theme.TEXT, android.graphics.PorterDuff.Mode.SRC_IN)
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = desc
            val size = dp(ctx, if (tv(ctx)) 52 else 44)
            val pad = dp(ctx, if (tv(ctx)) 13 else 11)
            setPadding(pad, pad, pad, pad)
            layoutParams = ViewGroup.LayoutParams(size, size)
            background = ripple(oval(Theme.alpha(Theme.SURFACE_2, 0.9f), Theme.STROKE, maxOf(1, dp(ctx, 1))), 0f, true)
            interactive(this, 100f, 1.1f, onClick)
        }

    // ---------------------------------------------------------------- containers

    fun card(ctx: Context, padDp: Int = 18): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        val p = dp(ctx, padDp)
        setPadding(p, p, p, p)
        val r = dpf(ctx, Theme.RADIUS_CARD)
        // Faint top-to-bottom lift gives the glass feel.
        background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(Theme.alpha(Theme.SURFACE_2, 0.88f), Theme.alpha(Theme.SURFACE, 0.85f))
        ).apply {
            cornerRadius = r
            setStroke(maxOf(1, dp(ctx, 1)), Theme.STROKE)
        }
        clipToPadding = false
    }

    /** Gradient card with a periodic light sheen sweeping across it. */
    private class SheenLayout(ctx: Context, private val radiusPx: Float) : LinearLayout(ctx) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val clip = Path()
        private val box = RectF()
        private val m = Matrix()
        private var shader: LinearGradient? = null
        private var band = 0f
        private var progress = -1f
        private var anim: ValueAnimator? = null
        private val loop = Runnable { sweep() }

        override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
            super.onSizeChanged(w, h, ow, oh)
            box.set(0f, 0f, w.toFloat(), h.toFloat())
            clip.reset()
            clip.addRoundRect(box, radiusPx, radiusPx, Path.Direction.CW)
            band = w * 0.5f
            shader = LinearGradient(0f, 0f, band, 0f, intArrayOf(0x00FFFFFF, 0x2EFFFFFF, 0x00FFFFFF), null, Shader.TileMode.CLAMP)
            paint.shader = shader
        }

        private fun sweep() {
            if (!isAttachedToWindow) return
            if (!Anim.on(context) || !isShown || width == 0) {
                postDelayed(loop, 4000)
                return
            }
            anim?.cancel()
            anim = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 1100
                interpolator = Theme.EASE_IN_OUT
                addUpdateListener {
                    progress = it.animatedValue as Float
                    m.setRotate(18f)
                    m.postTranslate(-band + progress * (width + band * 1.6f), 0f)
                    shader?.setLocalMatrix(m)
                    invalidate()
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(a: Animator) {
                        progress = -1f
                        invalidate()
                        if (isAttachedToWindow) postDelayed(loop, 3800)
                    }
                })
                start()
            }
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            postDelayed(loop, 1400)
        }

        override fun onDetachedFromWindow() {
            super.onDetachedFromWindow()
            removeCallbacks(loop)
            anim?.removeAllListeners()
            anim?.cancel()
            anim = null
            progress = -1f
        }

        override fun dispatchDraw(canvas: Canvas) {
            super.dispatchDraw(canvas)
            if (progress < 0f || shader == null) return
            val s = canvas.save()
            canvas.clipPath(clip)
            canvas.drawRect(box, paint)
            canvas.restoreToCount(s)
        }
    }

    fun actionCard(
        ctx: Context, title: String, subtitle: String, icon: Int, colors: IntArray, onClick: () -> Unit
    ): View {
        val r = dpf(ctx, 28f)
        val card = SheenLayout(ctx, r).apply {
            orientation = LinearLayout.VERTICAL
            minimumHeight = dp(ctx, if (tv(ctx)) 190 else 156)
            val p = dp(ctx, 20)
            setPadding(p, p, p, p)
            background = ripple(
                gradient(IntArray(colors.size) { forWhiteText(colors[it]) }, r).apply { setStroke(maxOf(1, dp(ctx, 1)), 0x33FFFFFF) }, r, color = 0x40FFFFFF
            )
        }
        val badge = FrameLayout(ctx).apply {
            background = oval(0x38FFFFFF)
            val s = dp(ctx, 46)
            layoutParams = LinearLayout.LayoutParams(s, s)
        }
        badge.addView(Ui.icon(ctx, icon, Color.WHITE, 24).apply {
            layoutParams = FrameLayout.LayoutParams(dp(ctx, 24), dp(ctx, 24), Gravity.CENTER)
        })
        card.addView(badge)
        card.addView(View(ctx), LinearLayout.LayoutParams(0, 0, 1f))
        card.addView(text(ctx, title, 22f, Color.WHITE, Theme.BLACK).apply { letterSpacing = -0.02f })
        card.addView(text(ctx, subtitle, 13f, 0xE6FFFFFF.toInt(), Theme.MEDIUM).apply {
            setPadding(0, dp(ctx, 4), 0, 0)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        })
        glow(card, Theme.alpha(colors.firstOrNull() ?: Theme.ACCENT, 0.9f), 14f)
        interactive(card, 28f, 1.04f, onClick)
        return card
    }

    fun tile(ctx: Context, label: String, icon: Int, tint: Int, onClick: () -> Unit): View {
        val r = dpf(ctx, 20f)
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            val ph = dp(ctx, 10)
            setPadding(ph, dp(ctx, 14), ph, dp(ctx, 12))
            background = ripple(glass(ctx, 20f), r)
            val badge = FrameLayout(ctx).apply {
                background = rounded(Theme.alpha(tint, 0.16f), dpf(ctx, 14f))
                val s = dp(ctx, 44)
                layoutParams = LinearLayout.LayoutParams(s, s)
            }
            badge.addView(Ui.icon(ctx, icon, tint, 24).apply {
                layoutParams = FrameLayout.LayoutParams(dp(ctx, 24), dp(ctx, 24), Gravity.CENTER)
            })
            addView(badge)
            addView(text(ctx, label, 12.5f, Theme.TEXT, Theme.MEDIUM).apply {
                gravity = Gravity.CENTER
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setPadding(0, dp(ctx, 9), 0, 0)
            })
            interactive(this, 20f, 1.06f, onClick)
        }
    }

    /** Sets the first two TextViews found under [root] (the title and subtitle of a [row]). */
    fun setRowText(root: View, title: String, sub: String) {
        val tvs = ArrayList<android.widget.TextView>()
        fun walk(v: View) {
            if (v is android.widget.TextView) tvs.add(v)
            else if (v is android.view.ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(root)
        if (tvs.size > 0 && tvs[0].text.toString() != title) tvs[0].text = title
        if (tvs.size > 1 && tvs[1].text.toString() != sub) tvs[1].text = sub
    }

    fun row(
        ctx: Context, icon: Int?, iconTint: Int, title: String, subtitle: String?,
        trailing: View? = null, onClick: (() -> Unit)?
    ): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(ctx, if (tv(ctx)) 72 else 64)
        setPadding(dp(ctx, 12), dp(ctx, 10), dp(ctx, 14), dp(ctx, 10))
        if (icon != null) {
            val badge = FrameLayout(ctx).apply {
                background = rounded(Theme.alpha(iconTint, 0.15f), dpf(ctx, 12f))
                val s = dp(ctx, 42)
                layoutParams = LinearLayout.LayoutParams(s, s).apply { marginEnd = dp(ctx, 14) }
            }
            badge.addView(Ui.icon(ctx, icon, iconTint, 22).apply {
                layoutParams = FrameLayout.LayoutParams(dp(ctx, 22), dp(ctx, 22), Gravity.CENTER)
            })
            addView(badge)
        } else {
            setPadding(dp(ctx, 14), dp(ctx, 10), dp(ctx, 14), dp(ctx, 10))
        }
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        col.addView(text(ctx, title, 16f, Theme.TEXT, Theme.MEDIUM).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        if (!subtitle.isNullOrEmpty()) {
            col.addView(text(ctx, subtitle, 13f, Theme.MUTED).apply {
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                setPadding(0, dp(ctx, 3), 0, 0)
            })
        }
        addView(col, LinearLayout.LayoutParams(0, WRAP, 1f))
        if (trailing != null) {
            addView(trailing, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(ctx, 10) })
        }
        if (onClick != null) {
            val r = dpf(ctx, 18f)
            background = ripple(focusFill(r), r)
            interactive(this, 18f, 1.02f, onClick)
        }
    }

    fun chip(ctx: Context, text: String, color: Int = Theme.ACCENT, icon: Int? = null): TextView =
        TextView(ctx).apply {
            this.text = text
            textSize = 12f
            typeface = Theme.BOLD
            setTextColor(color)
            includeFontPadding = false
            maxLines = 1
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(ctx, 10), dp(ctx, 6), dp(ctx, 10), dp(ctx, 6))
            background = rounded(Theme.alpha(color, 0.16f), dpf(ctx, 100f))
            if (icon != null) {
                val d = ctx.getDrawable(icon)?.mutate()
                if (d != null) {
                    val s = dp(ctx, 13)
                    d.setTint(color)
                    d.setBounds(0, 0, s, s)
                    setCompoundDrawablesRelative(d, null, null, null)
                    compoundDrawablePadding = dp(ctx, 5)
                }
            }
        }

    fun dot(ctx: Context, color: Int, sizeDp: Int = 8): View = View(ctx).apply {
        background = oval(color)
        val s = dp(ctx, sizeDp)
        layoutParams = LinearLayout.LayoutParams(s, s)
    }

    /** Pill switch with a sliding knob. */
    private class ToggleSwitch(ctx: Context, checked: Boolean) : View(ctx) {
        var on = checked
            private set
        var onChange: ((Boolean) -> Unit)? = null
        var onStateChanged: (() -> Unit)? = null
        private var pos = if (checked) 1f else 0f
        private var anim: ValueAnimator? = null
        private val track = Paint(Paint.ANTI_ALIAS_FLAG)
        private val knob = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        private val rect = RectF()
        private val w = Theme.dp(ctx, 52)
        private val h = Theme.dp(ctx, 30)
        private val onShader = LinearGradient(0f, 0f, w.toFloat(), 0f, Theme.ACCENT, Theme.ACCENT_2, Shader.TileMode.CLAMP)

        init {
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        override fun onMeasure(wSpec: Int, hSpec: Int) = setMeasuredDimension(w, h)

        fun toggle() = set(!on, true)

        fun set(v: Boolean, animate: Boolean) {
            if (v == on) return
            on = v
            anim?.cancel()
            if (!animate || !Anim.on(context)) {
                pos = if (v) 1f else 0f
                invalidate()
            } else {
                anim = ValueAnimator.ofFloat(pos, if (v) 1f else 0f).apply {
                    duration = Theme.DUR_FAST + 40
                    interpolator = Theme.EASE_OUT
                    addUpdateListener { pos = it.animatedValue as Float; invalidate() }
                    start()
                }
            }
            onChange?.invoke(v)
            onStateChanged?.invoke()
        }

        override fun onDraw(c: Canvas) {
            val hh = height.toFloat()
            rect.set(0f, 0f, width.toFloat(), hh)
            track.shader = null
            track.color = Theme.SURFACE_3
            c.drawRoundRect(rect, hh / 2, hh / 2, track)
            if (pos > 0f) {
                track.shader = onShader
                track.alpha = (255 * pos).toInt()
                c.drawRoundRect(rect, hh / 2, hh / 2, track)
            }
            val r = hh / 2 - Theme.dp(context, 4)
            val x0 = hh / 2
            val x1 = width - hh / 2
            c.drawCircle(x0 + (x1 - x0) * pos, hh / 2, r + pos * Theme.dp(context, 1), knob)
        }

        override fun onDetachedFromWindow() {
            super.onDetachedFromWindow()
            anim?.cancel()
        }
    }

    fun toggleRow(
        ctx: Context, title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit
    ): View {
        val sw = ToggleSwitch(ctx, checked)
        sw.onChange = onChange
        val r = row(ctx, null, Theme.ACCENT, title, subtitle, sw) { sw.toggle() }
        r.setAccessibilityDelegate(object : View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: android.view.accessibility.AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.className = "android.widget.Switch"
                info.isCheckable = true
                info.isChecked = sw.on
                info.text = if (subtitle.isNullOrEmpty()) title else "$title, $subtitle"
            }
        })
        sw.onStateChanged = { r.sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_VIEW_CLICKED) }
        return r
    }

    fun spacer(ctx: Context, heightDp: Int): View = View(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(MATCH, dp(ctx, heightDp))
    }

    fun divider(ctx: Context): View = View(ctx).apply {
        setBackgroundColor(Theme.STROKE)
        layoutParams = LinearLayout.LayoutParams(MATCH, maxOf(1, dp(ctx, 1)))
    }

    fun header(ctx: Context, title: String, onBack: (() -> Unit)?, trailing: View? = null): View =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(ctx, 12), 0, dp(ctx, 12))
            if (onBack != null) {
                val b = iconButton(ctx, R.drawable.ic_back, "Back", onBack)
                addView(b, LinearLayout.LayoutParams(b.layoutParams.width, b.layoutParams.height).apply {
                    marginEnd = dp(ctx, 12)
                })
            }
            addView(Ui.title(ctx, title, 24f).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(0, WRAP, 1f))
            if (trailing != null) {
                addView(trailing, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(ctx, 12) })
            }
        }

    /** Vertical scroll page: side gutter (bigger on TV), system-bar insets, no overscroll glow. */
    fun scrollPage(ctx: Context, content: LinearLayout): ScrollView = ScrollView(ctx).apply {
        overScrollMode = View.OVER_SCROLL_NEVER
        isVerticalScrollBarEnabled = false
        isFillViewport = true
        clipToPadding = false
        clipChildren = false
        val g = dp(ctx, if (tv(ctx)) Theme.TV_GUTTER else Theme.GUTTER)
        setPadding(g, 0, g, dp(ctx, 24))
        addView(content, FrameLayout.LayoutParams(MATCH, WRAP))
        applyInsets(this, true, true)
    }

    @Suppress("DEPRECATION")
    internal fun insetsOf(ins: WindowInsets, ime: Boolean = false): IntArray =
        if (Build.VERSION.SDK_INT >= 30) {
            val i = ins.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            val k = if (ime) ins.getInsets(WindowInsets.Type.ime()).bottom else 0
            intArrayOf(i.left, i.top, i.right, maxOf(i.bottom, k))
        } else {
            intArrayOf(ins.systemWindowInsetLeft, ins.systemWindowInsetTop, ins.systemWindowInsetRight, ins.systemWindowInsetBottom)
        }

    /** Current system-bar insets for a view's window, or zeros if unknown. */
    internal fun currentInsets(v: View, ime: Boolean = false): IntArray {
        if (Build.VERSION.SDK_INT >= 23) {
            v.rootWindowInsets?.let { return insetsOf(it, ime) }
        }
        return intArrayOf(0, 0, 0, 0)
    }

    /**
     * Pads [v] by the system bars (edge-to-edge). Use once per screen root: insets are not consumed,
     * so call it on exactly one view in a branch to avoid padding twice.
     */
    fun applyInsets(v: View, top: Boolean = true, bottom: Boolean = true, extraDp: Int = 0) {
        val l = v.paddingLeft
        val t = v.paddingTop
        val r = v.paddingRight
        val b = v.paddingBottom
        val extra = dp(v.context, extraDp)
        v.setOnApplyWindowInsetsListener { view, ins ->
            val i = insetsOf(ins)
            view.setPadding(
                l + i[0],
                t + if (top) i[1] + extra else 0,
                r + i[2],
                b + if (bottom) i[3] + extra else 0
            )
            ins
        }
        if (v.isAttachedToWindow) v.requestApplyInsets()
        else v.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) { view.requestApplyInsets() }
            override fun onViewDetachedFromWindow(view: View) {}
        })
    }

    // ---------------------------------------------------------------- avatar

    fun avatar(ctx: Context, name: String, type: String, sizeDp: Int, colors: IntArray): View {
        val s = dp(ctx, sizeDp)
        val glyph = when (type.lowercase()) {
            "tv" -> R.drawable.ic_tv
            "laptop", "pc", "computer", "desktop" -> R.drawable.ic_laptop
            else -> R.drawable.ic_phone
        }
        return FrameLayout(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(s, s)
            contentDescription = name
            background = gradient(colors, 0f).apply { shape = GradientDrawable.OVAL; setStroke(maxOf(1, dp(ctx, 1)), 0x33FFFFFF) }
            val g = (s * 0.5f).toInt()
            addView(ImageView(ctx).apply {
                setImageResource(glyph)
                setColorFilter(Color.WHITE, android.graphics.PorterDuff.Mode.SRC_IN)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, FrameLayout.LayoutParams(g, g, Gravity.CENTER))
        }
    }

    // ---------------------------------------------------------------- snackbar

    /** Pill that slides up above the nav bar and hides itself. kind: 0 info, 1 success, 2 error. */
    fun snackbar(root: ViewGroup, msg: String, kind: Int = 0) {
        val ctx = root.context
        // Replace any snackbar that is still showing.
        (root.getTag(K_SNACK) as? View)?.let { old ->
            (old.getTag(K_HIDE) as? Runnable)?.let { root.removeCallbacks(it) }
            old.animate().cancel()
            root.removeView(old)
        }
        val tint = when (kind) { 1 -> Theme.SUCCESS; 2 -> Theme.DANGER; else -> Theme.ACCENT_2 }
        val ic = when (kind) { 1 -> R.drawable.ic_check; 2 -> R.drawable.ic_close; else -> R.drawable.ic_bolt }
        val bar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(ctx, 12), dp(ctx, 10), dp(ctx, 18), dp(ctx, 10))
            background = rounded(Theme.alpha(Theme.SURFACE_3, 0.97f), dpf(ctx, 20f), Theme.STROKE_STRONG, maxOf(1, dp(ctx, 1)))
            elevation = dpf(ctx, 14f)
            translationZ = dpf(ctx, 24f)
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        val badge = FrameLayout(ctx).apply { background = oval(Theme.alpha(tint, 0.2f)) }
        badge.addView(icon(ctx, ic, tint, 14).apply {
            layoutParams = FrameLayout.LayoutParams(dp(ctx, 14), dp(ctx, 14), Gravity.CENTER)
        })
        bar.addView(badge, LinearLayout.LayoutParams(dp(ctx, 28), dp(ctx, 28)).apply { marginEnd = dp(ctx, 12) })
        bar.addView(text(ctx, msg, 14f, Theme.TEXT, Theme.MEDIUM).apply {
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
            maxWidth = dp(ctx, 460)
        })
        val inset = currentInsets(root)
        val lp = FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            val side = dp(ctx, 16)
            leftMargin = side + inset[0]
            rightMargin = side + inset[2]
            bottomMargin = inset[3] + dp(ctx, if (tv(ctx)) 36 else 22)
        }
        root.addView(bar, lp)
        root.setTag(K_SNACK, bar)
        bar.announceForAccessibility(msg)

        val animate = Anim.on(ctx)
        if (animate) {
            bar.alpha = 0f
            bar.translationY = dpf(ctx, 28f)
            bar.animate().alpha(1f).translationY(0f).setDuration(Theme.DUR_SLOW)
                .setInterpolator(Theme.EASE_OUT).start()
        }
        val hide = Runnable {
            fun done() {
                if (root.getTag(K_SNACK) === bar) root.setTag(K_SNACK, null)
                root.removeView(bar)
            }
            if (animate && bar.isAttachedToWindow) {
                bar.animate().alpha(0f).translationY(dpf(ctx, 16f)).setDuration(Theme.DUR)
                    .setInterpolator(Theme.EASE_IN).withEndAction { done() }.start()
            } else done()
        }
        bar.setTag(K_HIDE, hide)
        bar.setOnClickListener { root.removeCallbacks(hide); hide.run() }
        root.postDelayed(hide, if (kind == 2) 4200L else 2800L)
    }

    // ---------------------------------------------------------------- segmented control

    /** Pill segmented control; a gradient indicator slides under the selected tab. */
    private class SegmentedView(
        ctx: Context, options: List<String>, selected: Int, private val onSelect: (Int) -> Unit
    ) : FrameLayout(ctx) {
        private val pad = Theme.dp(ctx, 4)
        private val ind = Ui.gradient(Theme.BRAND, Ui.dpf(ctx, 12f))
        private val tabs = ArrayList<TextView>()
        private val n = maxOf(1, options.size)
        private var sel = selected.coerceIn(0, n - 1)
        private var pos = sel.toFloat()
        private var anim: ValueAnimator? = null

        init {
            setWillNotDraw(false)
            background = Ui.glass(ctx, 16f, Theme.alpha(Theme.SURFACE_2, 0.9f))
            setPadding(pad, pad, pad, pad)
            val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            options.forEachIndexed { i, o ->
                val tv = TextView(ctx).apply {
                    text = o
                    textSize = 14f
                    typeface = Theme.BOLD
                    includeFontPadding = false
                    gravity = Gravity.CENTER
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    setPadding(Theme.dp(ctx, 12), Theme.dp(ctx, 11), Theme.dp(ctx, 12), Theme.dp(ctx, 11))
                    minHeight = Theme.dp(ctx, if (Ui.tv(ctx)) 48 else 40)
                    setOnClickListener { Ui.haptic(it); select(i, true) }
                }
                Ui.focusable(tv, 1.03f, 12f)
                Ui.pressable(tv)
                tabs.add(tv)
                row.addView(tv, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            }
            paintTabs()
        }

        private fun paintTabs() {
            tabs.forEachIndexed { i, t -> t.setTextColor(if (i == sel) Color.WHITE else Theme.MUTED) }
        }

        fun select(i: Int, user: Boolean) {
            val ni = i.coerceIn(0, n - 1)
            if (ni == sel) return
            sel = ni
            paintTabs()
            anim?.cancel()
            if (!Anim.on(context)) {
                pos = ni.toFloat(); invalidate()
            } else {
                anim = ValueAnimator.ofFloat(pos, ni.toFloat()).apply {
                    duration = Theme.DUR
                    interpolator = Theme.EASE_OUT
                    addUpdateListener { pos = it.animatedValue as Float; invalidate() }
                    start()
                }
            }
            if (user) onSelect(ni)
        }

        override fun onDraw(c: Canvas) {
            val iw = (width - 2 * pad) / n.toFloat()
            val l = pad + pos * iw
            ind.setBounds(l.toInt(), pad, (l + iw).toInt(), height - pad)
            ind.draw(c)
        }

        override fun onDetachedFromWindow() {
            super.onDetachedFromWindow()
            anim?.cancel()
        }
    }

    fun segmented(ctx: Context, options: List<String>, selected: Int, onSelect: (Int) -> Unit): View =
        SegmentedView(ctx, options, selected, onSelect)
}
