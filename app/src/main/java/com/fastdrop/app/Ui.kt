package com.fastdrop.app

import android.animation.ValueAnimator
import android.app.UiModeManager
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.text.TextUtils
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Haptic kinds (docs/DESIGN.md 2.8). Phones only; the system setting is respected. */
enum class Haptic { TAP, TICK, CONFIRM, REJECT }

/** The type scale (docs/DESIGN.md 2.4): phone sp / line, TV sp / line, face, tabular figures, colour role. */
enum class Type(
    val sp: Float, val line: Float, val tvSp: Float, val tvLine: Float,
    val medium: Boolean, val tnum: Boolean, val mono: Boolean, val muted: Boolean
) {
    HERO(24f, 30f, 32f, 40f, true, false, false, false),
    TITLE(20f, 26f, 26f, 32f, true, false, false, false),
    ROW(16f, 22f, 18f, 24f, true, false, false, false),
    BODY(15f, 21f, 16f, 22f, false, false, false, false),
    META(13f, 18f, 14f, 20f, false, true, false, true),
    SECTION(13f, 18f, 15f, 20f, true, false, false, true),
    MONO(12f, 16f, 14f, 18f, false, false, true, true),
    BUTTON(15f, 20f, 17f, 22f, true, false, false, false),
    FIGURE(28f, 32f, 36f, 40f, false, true, false, false),
}

/** LED states for [Ui.led]. */
enum class Led { OK, RECEIVE, DANGER, OFF }

/**
 * The FastDrop design system ("Signal"): factory functions for every reusable component.
 * Everything reads [Theme] tokens and [Job] colours, checks [Theme.animationsOn], and is usable
 * with a TV remote (every interactive view is focusable and fills with its job colour on focus).
 */
object Ui {
    private const val K_SNACK = 0x7f0fff04
    private const val K_HIDE = 0x7f0fff05

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

    /** Side gutter in px (16 dp phone, 48 dp TV). */
    fun gutter(ctx: Context): Int = dp(ctx, if (tv(ctx)) Theme.TV_GUTTER else Theme.GUTTER)

    // ---------------------------------------------------------------- text

    /** Fixed line height: API 28+ has setLineHeight, older versions use extra line spacing. */
    fun lineHeight(t: TextView, sp: Float) {
        val px = android.util.TypedValue.applyDimension(
            android.util.TypedValue.COMPLEX_UNIT_SP, sp, t.resources.displayMetrics)
        if (Build.VERSION.SDK_INT >= 28) t.lineHeight = px.toInt()
        else t.setLineSpacing(px - t.paint.getFontMetricsInt(null), 1f)
    }

    fun text(
        ctx: Context, s: CharSequence, sp: Float = 15f,
        color: Int = Theme.INK, font: Typeface = Theme.REGULAR,
        lineSp: Float = 0f, tnum: Boolean = false
    ): TextView = TextView(ctx).apply {
        text = s
        textSize = sp
        setTextColor(color)
        typeface = font
        includeFontPadding = false
        if (tnum) fontFeatureSettings = "tnum"
        lineHeight(this, if (lineSp > 0f) lineSp else sp * 1.38f)
    }

    /** Text in a named style from the type scale. [color] defaults to INK (INK_2 for muted styles). */
    fun text(ctx: Context, s: CharSequence, style: Type, color: Int? = null): TextView {
        val tv = tv(ctx)
        val c = color ?: if (style.muted) Theme.INK_2 else Theme.INK
        val font = when { style.mono -> Theme.MONO; style.medium -> Theme.MEDIUM; else -> Theme.REGULAR }
        return text(ctx, s, if (tv) style.tvSp else style.sp, c, font, if (tv) style.tvLine else style.line, style.tnum)
    }

    fun title(ctx: Context, s: CharSequence, sp: Float = 20f): TextView =
        text(ctx, s, sp, Theme.INK, Theme.MEDIUM, sp * 1.3f)

    /** A numeric / technical string in the monospace face. */
    fun mono(ctx: Context, s: CharSequence, color: Int = Theme.INK_2): TextView = text(ctx, s, Type.MONO, color)

    fun icon(ctx: Context, res: Int, tint: Int = Theme.INK, sizeDp: Int = 24): ImageView =
        ImageView(ctx).apply {
            setImageResource(res)
            setColorFilter(tint, android.graphics.PorterDuff.Mode.SRC_IN)
            scaleType = ImageView.ScaleType.FIT_CENTER
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            val s = dp(ctx, sizeDp)
            layoutParams = ViewGroup.LayoutParams(s, s)
        }

    /** An icon that flips colour with its parent's focus / press / selected state. */
    private fun stateIcon(ctx: Context, res: Int, rest: Int, active: Int, sizeDp: Int): ImageView =
        ImageView(ctx).apply {
            setImageResource(res)
            imageTintList = states(rest, active)
            scaleType = ImageView.ScaleType.FIT_CENTER
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            isDuplicateParentStateEnabled = true
            val s = dp(ctx, sizeDp)
            layoutParams = ViewGroup.LayoutParams(s, s)
        }

    // ---------------------------------------------------------------- drawables

    private fun rect(color: Int, radiusPx: Float, strokeColor: Int = 0, strokePx: Int = 0): GradientDrawable =
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

    /** A 6 x 24 dp "key block" at the leading edge: the job colour shown at rest on TV (5.3). */
    private class KeyBlock(private val color: Int, private val w: Float, private val h: Float) : Drawable() {
        private val p = Paint().apply { this.color = this@KeyBlock.color }
        override fun draw(c: Canvas) {
            val b = bounds
            val rtl = false
            val top = b.exactCenterY() - h / 2f
            c.drawRect(if (rtl) b.right - w else b.left.toFloat(), top, if (rtl) b.right.toFloat() else b.left + w, top + h, p)
        }
        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(cf: android.graphics.ColorFilter?) {}
        @Suppress("OVERRIDE_DEPRECATION")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    /** Colours that flip to [active] while the owning view is focused, selected (activated) or pressed. */
    internal fun states(rest: Int, active: Int, pressFlips: Boolean = true): ColorStateList {
        val s = ArrayList<IntArray>()
        val c = ArrayList<Int>()
        if (pressFlips) { s.add(intArrayOf(android.R.attr.state_pressed)); c.add(active) }
        s.add(intArrayOf(android.R.attr.state_focused)); c.add(active)
        s.add(intArrayOf(android.R.attr.state_activated)); c.add(active)
        s.add(intArrayOf()); c.add(rest)
        return ColorStateList(s.toTypedArray(), c.toIntArray())
    }

    /** The content colour on a job's fill (or on the neutral INK fill). */
    private fun onFill(job: Job?): Int = job?.on ?: Theme.BG

    /** Does a press flip the content colour? Job fills always; neutral fills only on TV. */
    private fun pressFlips(ctx: Context, job: Job?) = job != null || tv(ctx)

    /** A TextView whose colour follows its parent's state. */
    private fun stateText(ctx: Context, s: CharSequence, style: Type, rest: Int?, job: Job?): TextView =
        text(ctx, s, style, rest).apply {
            setTextColor(states(rest ?: if (style.muted) Theme.INK_2 else Theme.INK, onFill(job), pressFlips(ctx, job)))
            isDuplicateParentStateEnabled = true
        }

    // ---------------------------------------------------------------- focus / press / haptics

    /**
     * Makes [v] focusable and gives it the Signal states: pressed (phone: the job colour, TV: its
     * shade; neutral: SURFACE_2 / INK_2), selected (activated) and focused: the job colour fill
     * (neutral INK when [job] is null). On TV it also scales to [scale] on focus (instant fill,
     * 100 ms scale). The existing background is kept as the rest state.
     */
    fun focusFill(
        v: View, job: Job? = null, scale: Float = 1f, radiusDp: Float = 0f,
        rest: Drawable? = v.background, pressFill: Int? = null, ring: Boolean = false
    ) {
        val ctx = v.context
        val tvMode = tv(ctx)
        val r = dpf(ctx, radiusDp)
        val fill = job?.fill ?: Theme.INK
        val press = pressFill ?: when {
            job != null -> if (tvMode) job.shade else job.fill
            else -> if (tvMode) Theme.INK_2 else Theme.SURFACE_2
        }
        v.isFocusable = true
        v.background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), rect(press, r))
            addState(intArrayOf(android.R.attr.state_activated), rect(fill, r))
            // [ring]: the rest state is already this fill, so focus adds an INK outline to stay visible.
            addState(intArrayOf(android.R.attr.state_focused),
                if (ring) rect(fill, r, Theme.INK, dp(ctx, 3)) else rect(fill, r))
            addState(intArrayOf(), rest ?: ColorDrawable(0))
        }
        v.setOnFocusChangeListener { view, has ->
            if (!tvMode) return@setOnFocusChangeListener
            val s = if (has) scale else 1f
            if (Anim.on(view.context)) {
                view.animate().scaleX(s).scaleY(s).setDuration(100).setInterpolator(Theme.EASE_ENTER).start()
            } else { view.scaleX = s; view.scaleY = s }
        }
    }

    fun haptic(v: View, kind: Haptic = Haptic.TAP) {
        try {
            val c = when (kind) {
                Haptic.TAP -> HapticFeedbackConstants.VIRTUAL_KEY
                Haptic.TICK -> HapticFeedbackConstants.CLOCK_TICK
                Haptic.CONFIRM -> if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY
                Haptic.REJECT -> if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS
            }
            v.performHapticFeedback(c)
        } catch (_: Throwable) { }
    }

    // ---------------------------------------------------------------- buttons

    private fun withGlyph(t: TextView, icon: Int?, tint: ColorStateList, padDp: Int = 10) {
        if (icon == null) return
        val d = t.context.getDrawable(icon)?.mutate() ?: return
        val s = dp(t.context, 20)
        d.setTintList(tint)
        d.setBounds(0, 0, s, s)
        t.setCompoundDrawablesRelative(d, null, null, null)
        t.compoundDrawablePadding = dp(t.context, padDp)
    }

    /** Solid job-colour button: [job].fill, [job].on text, 6 dp, 52 dp tall (TV 56), pressed: shade. */
    fun jobButton(ctx: Context, label: String, job: Job, icon: Int? = null, onClick: () -> Unit): TextView =
        TextView(ctx).apply {
            val tvMode = tv(ctx)
            text = label
            textSize = if (tvMode) Type.BUTTON.tvSp else Type.BUTTON.sp
            typeface = Theme.MEDIUM
            includeFontPadding = false
            isAllCaps = false
            gravity = Gravity.CENTER
            minHeight = dp(ctx, if (tvMode) 56 else 52)
            setPadding(dp(ctx, 20), dp(ctx, 12), dp(ctx, 20), dp(ctx, 12))
            val r = dpf(ctx, Theme.R_CTRL)
            val disabled = rect(Theme.SURFACE_2, r)
            val rest: Drawable
            val textColors: ColorStateList
            if (tvMode) {
                // At rest on TV the key is lit only by a 6 x 24 dp block; focus lights the whole key.
                rest = LayerDrawable(arrayOf(rect(Theme.SURFACE_2, r), KeyBlock(job.fill, dpf(ctx, 6f), dpf(ctx, 24f))))
                textColors = ColorStateList(
                    arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf(android.R.attr.state_focused),
                        intArrayOf(android.R.attr.state_pressed), intArrayOf()),
                    intArrayOf(Theme.INK_3, job.on, job.on, Theme.INK))
            } else {
                rest = rect(job.fill, r)
                textColors = ColorStateList(
                    arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
                    intArrayOf(Theme.INK_3, job.on))
            }
            setTextColor(textColors)
            withGlyph(this, icon, textColors)
            focusFill(this, job, 1.06f, Theme.R_CTRL, rest, pressFill = job.shade, ring = !tvMode)
            // Disabled look sits in front of everything.
            (background as StateListDrawable).let { sl ->
                val all = StateListDrawable()
                all.addState(intArrayOf(-android.R.attr.state_enabled), disabled)
                all.addState(intArrayOf(), sl)
                background = all
            }
            setOnClickListener { haptic(it); onClick() }
        }

    /** Outlined neutral button: transparent, 1 px LINE_STRONG outline, INK text, 6 dp, 52 dp tall. */
    fun secondaryButton(ctx: Context, label: String, icon: Int? = null, small: Boolean = false, onClick: () -> Unit): TextView =
        TextView(ctx).apply {
            val tvMode = tv(ctx)
            text = label
            textSize = if (tvMode) Type.BUTTON.tvSp else Type.BUTTON.sp
            typeface = Theme.MEDIUM
            includeFontPadding = false
            isAllCaps = false
            gravity = Gravity.CENTER
            minHeight = dp(ctx, if (small) 36 else if (tvMode) 56 else 52)
            val ph = dp(ctx, if (small) 12 else 20)
            setPadding(ph, dp(ctx, if (small) 6 else 12), ph, dp(ctx, if (small) 6 else 12))
            val textColors = states(Theme.INK, Theme.BG, tvMode)
            setTextColor(textColors)
            withGlyph(this, icon, textColors)
            val r = dpf(ctx, Theme.R_CTRL)
            focusFill(this, null, 1.06f, Theme.R_CTRL, rect(0, r, Theme.LINE_STRONG, Theme.rule(ctx)))
            setOnClickListener { haptic(it); onClick() }
        }

    /** Text-only button: 48 dp touch target, no outline. [danger] uses the red text tone. */
    fun quietButton(ctx: Context, label: String, danger: Boolean = false, onClick: () -> Unit): TextView =
        TextView(ctx).apply {
            val tvMode = tv(ctx)
            text = label
            textSize = if (tvMode) Type.BUTTON.tvSp else Type.BUTTON.sp
            typeface = Theme.MEDIUM
            includeFontPadding = false
            isAllCaps = false
            gravity = Gravity.CENTER
            minHeight = dp(ctx, if (tvMode) 56 else 48)
            minWidth = dp(ctx, 48)
            setPadding(dp(ctx, 12), dp(ctx, 8), dp(ctx, 12), dp(ctx, 8))
            setTextColor(states(if (danger) Theme.TXT_DANGER else Theme.INK, Theme.BG, tvMode))
            focusFill(this, null, 1.06f, Theme.R_CTRL, null)
            setOnClickListener { haptic(it); onClick() }
        }

    /** 48 dp (TV 56) icon target, no background; focus fills INK. */
    fun iconButton(ctx: Context, icon: Int, desc: String, onClick: () -> Unit): ImageView =
        ImageView(ctx).apply {
            val tvMode = tv(ctx)
            setImageResource(icon)
            imageTintList = states(Theme.INK, Theme.BG, tvMode)
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = desc
            val size = dp(ctx, if (tvMode) 56 else 48)
            val pad = (size - dp(ctx, 24)) / 2
            setPadding(pad, pad, pad, pad)
            layoutParams = ViewGroup.LayoutParams(size, size)
            focusFill(this, null, 1.06f, Theme.R_CTRL, null)
            setOnClickListener { haptic(it); onClick() }
        }

    // ---------------------------------------------------------------- containers

    /** A plain vertical group with no background (replaces the old card; use [section] + rows). */
    fun column(ctx: Context, padDp: Int = 0): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        val p = dp(ctx, padDp)
        setPadding(p, p, p, p)
    }

    /** SURFACE fill, 1 px LINE top edge, optional rounded top corners (sheets). */
    fun panel(ctx: Context, topRadiusDp: Float = 0f): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        background = panelBackground(ctx, topRadiusDp)
    }

    internal fun panelBackground(ctx: Context, topRadiusDp: Float, allCorners: Boolean = false): Drawable {
        val r = dpf(ctx, topRadiusDp)
        val radii = if (allCorners) floatArrayOf(r, r, r, r, r, r, r, r) else floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
        val line = GradientDrawable().apply { setColor(Theme.LINE); cornerRadii = radii }
        val body = GradientDrawable().apply { setColor(Theme.SURFACE); cornerRadii = radii }
        return LayerDrawable(arrayOf(line, body)).apply {
            setLayerInset(1, 0, Theme.rule(ctx), 0, 0)
        }
    }

    /** Full-bleed hairline rule. */
    fun rule(ctx: Context): View = View(ctx).apply {
        setBackgroundColor(Theme.LINE)
        layoutParams = LinearLayout.LayoutParams(MATCH, Theme.rule(ctx))
    }

    /** A rule, then a sentence-case section label with an optional trailing quiet button. */
    fun section(ctx: Context, label: String, trailing: View? = null): View =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(rule(ctx))
            val bar = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                val g = gutter(ctx)
                setPadding(g, 0, if (trailing != null) dp(ctx, 4) else g, 0)
                minimumHeight = dp(ctx, if (trailing != null) 48 else 0)
            }
            val l = text(ctx, label, if (tv(ctx)) 20f else 17f, Theme.INK, Theme.MEDIUM, if (tv(ctx)) 26f else 22f)
            l.setPadding(0, dp(ctx, if (tv(ctx)) 16 else 12), 0, dp(ctx, 8))
            bar.addView(l, LinearLayout.LayoutParams(0, WRAP, 1f))
            if (trailing != null) bar.addView(trailing, LinearLayout.LayoutParams(WRAP, WRAP))
            addView(bar, LinearLayout.LayoutParams(MATCH, WRAP))
        }

    /** Sets the first two TextViews found under [root] (the title and subtitle of a [row]). */
    fun setRowText(root: View, title: String, sub: String) {
        val tvs = ArrayList<TextView>()
        fun walk(v: View) {
            if (v is TextView) tvs.add(v)
            else if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(root)
        if (tvs.size > 0 && tvs[0].text.toString() != title) tvs[0].text = title
        if (tvs.size > 1 && tvs[1].text.toString() != sub) tvs[1].text = sub
    }

    /** A chevron that flips colour with its row. */
    fun chevron(ctx: Context, job: Job? = null): ImageView =
        stateIcon(ctx, R.drawable.ic_chevron, Theme.INK_2, onFill(job), 16)

    /**
     * A list row: min height 56 (two lines 64, TV 64), gutter padding, bare 20 dp [icon] in INK_2
     * or a [lead] view (plate, thumbnail), title T_ROW, subtitle T_META, optional [trailing].
     * Clickable rows fill INK on focus (neutral); text and icons flip with the fill.
     */
    fun row(
        ctx: Context, icon: Int?, title: String, subtitle: String?,
        trailing: View? = null, lead: View? = null, job: Job? = null, onClick: (() -> Unit)? = null
    ): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val g = gutter(ctx)
        minimumHeight = dp(ctx, if (tv(ctx)) 64 else if (!subtitle.isNullOrEmpty()) 64 else 56)
        setPadding(g, dp(ctx, 8), g, dp(ctx, 8))
        if (lead != null) {
            addView(lead, LinearLayout.LayoutParams(lead.layoutParams?.width ?: WRAP, lead.layoutParams?.height ?: WRAP)
                .apply { marginEnd = dp(ctx, if (tv(ctx)) 20 else 16) })
        } else if (icon != null) {
            addView(stateIcon(ctx, icon, Theme.INK_2, onFill(job), 20),
                LinearLayout.LayoutParams(dp(ctx, 20), dp(ctx, 20)).apply { marginEnd = dp(ctx, 16); marginStart = dp(ctx, 2) })
        }
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            isDuplicateParentStateEnabled = true
        }
        col.addView(stateText(ctx, title, Type.ROW, Theme.INK, job).apply {
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        })
        if (!subtitle.isNullOrEmpty()) {
            col.addView(stateText(ctx, subtitle, Type.META, Theme.INK_2, job).apply {
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                setPadding(0, dp(ctx, 2), 0, 0)
            })
        }
        addView(col, LinearLayout.LayoutParams(0, WRAP, 1f))
        if (trailing != null) {
            addView(trailing, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(ctx, 12) })
        }
        if (onClick != null) {
            focusFill(this, job, 1.03f, 0f, null)
            setOnClickListener { haptic(it); onClick() }
        }
    }

    /**
     * A device "port" (docs 4.1): `[01] [plate] title / meta   tags   chevron`. Rest: neutral row with
     * a colour plate. Pressed, focused or [setSelected]: the whole row fills C_SEND, content turns ink and
     * the plate inverts. [tags] are neutral outlined labels ("Wi-Fi", "Trusted").
     */
    fun portRow(
        ctx: Context, number: Int, d: Device, meta: String, tags: List<String>,
        trailing: View? = null, onClick: () -> Unit
    ): LinearLayout {
        val tvMode = tv(ctx)
        val g = gutter(ctx)
        val send = Job.SEND
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(ctx, 64)
            setPadding(g, dp(ctx, 8), g, dp(ctx, 8))
        }
        row.addView(stateText(ctx, String.format("%02d", number), Type.MONO, Theme.INK_2, send).apply {
            minWidth = dp(ctx, 20)
        }, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginEnd = dp(ctx, 12) })
        val p = Theme.plateFor(d.type)
        row.addView(plate(ctx, glyphFor(d.type), p.fill, p.on, if (tvMode) 48 else 40, send),
            LinearLayout.LayoutParams(dp(ctx, if (tvMode) 48 else 40), dp(ctx, if (tvMode) 48 else 40))
                .apply { marginEnd = dp(ctx, 16) })
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            isDuplicateParentStateEnabled = true
        }
        col.addView(stateText(ctx, d.name, Type.ROW, Theme.INK, send).apply {
            maxLines = 2; ellipsize = TextUtils.TruncateAt.END
        })
        col.addView(stateText(ctx, meta, Type.META, Theme.INK_2, send).apply {
            maxLines = 2; ellipsize = TextUtils.TruncateAt.END; setPadding(0, dp(ctx, 2), 0, 0)
        })
        row.addView(col, LinearLayout.LayoutParams(0, WRAP, 1f))
        for (t in tags) {
            row.addView(tag(ctx, t, send), LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(ctx, 8) })
        }
        row.addView(trailing ?: chevron(ctx, send),
            LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(ctx, 12) })
        focusFill(row, send, 1.03f, 0f, null, pressFill = send.fill)
        row.setOnClickListener { haptic(it); onClick() }
        return row
    }

    /** The device glyph for a device type. */
    fun glyphFor(type: String): Int = when (type.lowercase()) {
        "tv" -> R.drawable.ic_tv
        "laptop", "pc", "computer", "desktop", "mac" -> R.drawable.ic_laptop
        else -> R.drawable.ic_phone
    }

    /**
     * Solid square plate (6 dp) with a glyph, replacing avatars and icon badges. When the owning row
     * is filled with [invert]'s job colour, the plate flips: plate becomes the job's ON colour and the
     * glyph the job's fill.
     */
    fun plate(ctx: Context, glyph: Int, fill: Int, on: Int, sizeDp: Int = 40, invert: Job? = null): FrameLayout =
        FrameLayout(ctx).apply {
            val r = dpf(ctx, Theme.R_CTRL)
            layoutParams = LinearLayout.LayoutParams(dp(ctx, sizeDp), dp(ctx, sizeDp))
            val restBg = rect(fill, r)
            background = if (invert == null) restBg else StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_pressed), rect(invert.on, r))
                addState(intArrayOf(android.R.attr.state_focused), rect(invert.on, r))
                addState(intArrayOf(android.R.attr.state_activated), rect(invert.on, r))
                addState(intArrayOf(), restBg)
            }
            isDuplicateParentStateEnabled = true
            val gs = dp(ctx, if (tv(ctx) || sizeDp >= 48) 24 else if (sizeDp >= 36) 20 else 16)
            val glyphView = if (invert == null) icon(ctx, glyph, on, 0)
            else stateIcon(ctx, glyph, on, invert.fill, 0)
            addView(glyphView, FrameLayout.LayoutParams(gs, gs, Gravity.CENTER))
        }

    /** Neutral outlined tag in the monospace face: `Wi-Fi`, `Trusted`. On a filled row it turns to that fill's content colour. */
    fun tag(ctx: Context, text: String, job: Job? = null): TextView = TextView(ctx).apply {
        this.text = text
        textSize = Type.MONO.sp
        typeface = Theme.MONO
        includeFontPadding = false
        maxLines = 1
        setTextColor(states(Theme.INK_2, onFill(job), true))
        setPadding(dp(ctx, 8), dp(ctx, 4), dp(ctx, 8), dp(ctx, 4))
        val r = dpf(ctx, Theme.R_CTRL)
        val w = Theme.rule(ctx)
        background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), rect(0, r, onFill(job), w))
            addState(intArrayOf(android.R.attr.state_focused), rect(0, r, onFill(job), w))
            addState(intArrayOf(android.R.attr.state_activated), rect(0, r, onFill(job), w))
            addState(intArrayOf(), rect(0, r, Theme.LINE_STRONG, w))
        }
        isDuplicateParentStateEnabled = true
    }

    /** 8 dp status LED. */
    fun led(ctx: Context, state: Led): View = View(ctx).apply {
        val s = dp(ctx, 8)
        background = when (state) {
            Led.OK -> oval(Theme.C_OK)
            Led.RECEIVE -> oval(Theme.C_RECEIVE)
            Led.DANGER -> oval(Theme.C_DANGER)
            Led.OFF -> oval(0, Theme.LINE_STRONG, maxOf(1, Theme.rule(ctx)))
        }
        layoutParams = LinearLayout.LayoutParams(s, s)
    }

    /**
     * A full-bleed 72 dp colour block (Receive screen, web share status, failures). [job] null is a
     * neutral SURFACE_2 block.
     */
    class StatusBlock(ctx: Context, private var job: Job?, title: String, meta: String?) : LinearLayout(ctx) {
        private val t = TextView(ctx)
        private val m = TextView(ctx)

        init {
            orientation = VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(ctx, 72)
            val g = gutter(ctx)
            setPadding(g, dp(ctx, 12), g, dp(ctx, 12))
            addView(t, LayoutParams(MATCH, WRAP))
            addView(m, LayoutParams(MATCH, WRAP).apply { topMargin = dp(ctx, 2) })
            set(job, title, meta)
        }

        fun set(job: Job?, title: String, meta: String?) {
            this.job = job
            setBackgroundColor(job?.fill ?: Theme.SURFACE_2)
            val on = job?.on ?: Theme.INK
            style(t, title, Type.ROW, on)
            style(m, meta ?: "", Type.META, on)
            m.visibility = if (meta.isNullOrEmpty()) GONE else VISIBLE
        }

        private fun style(v: TextView, s: String, st: Type, color: Int) {
            val tvMode = tv(context)
            v.text = s
            v.textSize = if (tvMode) st.tvSp else st.sp
            v.typeface = if (st.medium) Theme.MEDIUM else Theme.REGULAR
            v.setTextColor(color)
            v.includeFontPadding = false
            lineHeight(v, if (tvMode) st.tvLine else st.line)
            if (st.tnum) v.fontFeatureSettings = "tnum"
        }
    }

    fun statusBlock(ctx: Context, job: Job?, title: String, meta: String? = null): StatusBlock =
        StatusBlock(ctx, job, title, meta)

    // ---------------------------------------------------------------- toggles, checks, radios, stepper

    /** 40 x 22 dp, 6 dp radius switch: off = outline + INK_3 knob, on = INK fill + BG knob. */
    private class ToggleSwitch(ctx: Context, checked: Boolean) : View(ctx) {
        var on = checked
            private set
        var onChange: ((Boolean) -> Unit)? = null
        var onStateChanged: (() -> Unit)? = null
        private var pos = if (checked) 1f else 0f
        private var anim: ValueAnimator? = null
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val knob = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rect = RectF()
        private val w = Theme.dp(ctx, 40)
        private val h = Theme.dp(ctx, 22)
        private val stroke = Theme.rule(ctx).toFloat()

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
                    duration = Theme.D_TAP
                    interpolator = Theme.EASE_MOVE
                    addUpdateListener { pos = it.animatedValue as Float; invalidate() }
                    start()
                }
            }
            onChange?.invoke(v)
            onStateChanged?.invoke()
        }

        override fun onDraw(c: Canvas) {
            val r = Ui.dpf(context, Theme.R_CTRL)
            rect.set(0f, 0f, width.toFloat(), height.toFloat())
            if (pos >= 0.5f) {
                fill.style = Paint.Style.FILL; fill.color = Theme.INK
                c.drawRoundRect(rect, r, r, fill)
            } else {
                fill.style = Paint.Style.STROKE; fill.strokeWidth = stroke; fill.color = Theme.LINE_STRONG
                rect.inset(stroke / 2, stroke / 2)
                c.drawRoundRect(rect, r, r, fill)
            }
            val kr = Theme.dp(context, 8).toFloat()
            val pad = (height - 2 * kr) / 2f
            val x0 = pad + kr
            val x1 = width - pad - kr
            knob.color = if (pos >= 0.5f) Theme.BG else Theme.INK_3
            c.drawCircle(x0 + (x1 - x0) * pos, height / 2f, kr, knob)
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
        val r = row(ctx, null, title, subtitle, sw) { sw.toggle() }
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

    /** 22 dp square check box drawn on a Canvas; checked = job fill + job ON tick. */
    private class CheckBox(ctx: Context, private val job: Job, checked: Boolean) : View(ctx) {
        var on = checked
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rect = RectF()
        private val size = Theme.dp(ctx, 22)
        private val stroke = Theme.rule(ctx).toFloat()

        init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

        override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(size, size)

        override fun onDraw(c: Canvas) {
            val r = Ui.dpf(context, Theme.R_CTRL)
            rect.set(0f, 0f, width.toFloat(), height.toFloat())
            if (on) {
                p.style = Paint.Style.FILL; p.color = job.fill
                c.drawRoundRect(rect, r, r, p)
                p.style = Paint.Style.STROKE; p.strokeWidth = Theme.dp(context, 2).toFloat() * 0.9f
                p.strokeCap = Paint.Cap.SQUARE; p.color = job.on
                val w = width.toFloat(); val h = height.toFloat()
                c.drawLine(w * 0.24f, h * 0.52f, w * 0.43f, h * 0.70f, p)
                c.drawLine(w * 0.43f, h * 0.70f, w * 0.77f, h * 0.30f, p)
            } else {
                p.style = Paint.Style.STROKE; p.strokeWidth = stroke; p.color = Theme.LINE_STRONG
                rect.inset(stroke / 2, stroke / 2)
                c.drawRoundRect(rect, r, r, p)
            }
        }
    }

    /** A checkbox row ("Always accept from Pixel 8"); checked uses [job]'s fill. */
    class CheckRow internal constructor(ctx: Context) : LinearLayout(ctx) {
        internal var box: View? = null
        var checked: Boolean = false
            internal set
    }

    fun checkRow(ctx: Context, job: Job, label: String, checked: Boolean = false, onChange: (Boolean) -> Unit): CheckRow {
        val cb = CheckBox(ctx, job, checked)
        val g = gutter(ctx)
        val row = CheckRow(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(ctx, 56)
            setPadding(g, dp(ctx, 8), g, dp(ctx, 8))
            box = cb
        }
        row.addView(cb, LinearLayout.LayoutParams(dp(ctx, 22), dp(ctx, 22)).apply { marginEnd = dp(ctx, 16) })
        row.addView(stateText(ctx, label, Type.BODY, Theme.INK, null).apply {
            maxLines = 2; ellipsize = TextUtils.TruncateAt.END
            setTextColor(states(Theme.INK, Theme.BG, tv(ctx)))
        }, LinearLayout.LayoutParams(0, WRAP, 1f))
        focusFill(row, null, 1.03f, 0f, null)
        row.setOnClickListener {
            cb.on = !cb.on
            cb.invalidate()
            haptic(it, Haptic.TICK)
            onChange(cb.on)
        }
        row.setAccessibilityDelegate(object : View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: android.view.accessibility.AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.className = "android.widget.CheckBox"
                info.isCheckable = true
                info.isChecked = cb.on
                info.text = label
            }
        })
        return row
    }

    /** Radio row: 22 dp ring, INK dot when selected. */
    fun radioRow(ctx: Context, title: String, subtitle: String?, selected: Boolean, onClick: () -> Unit): View {
        val ring = object : View(ctx) {
            private val p = Paint(Paint.ANTI_ALIAS_FLAG)
            override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(dp(ctx, 22), dp(ctx, 22))
            override fun onDraw(c: Canvas) {
                val cx = width / 2f; val cy = height / 2f
                p.style = Paint.Style.STROKE; p.strokeWidth = Theme.rule(ctx).toFloat() * 1.5f
                p.color = if (isActivated) Theme.INK else Theme.LINE_STRONG
                c.drawCircle(cx, cy, cx - p.strokeWidth, p)
                if (isActivated) { p.style = Paint.Style.FILL; p.color = Theme.INK; c.drawCircle(cx, cy, cx * 0.5f, p) }
            }
        }
        ring.isActivated = selected
        ring.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        // The ring uses the "selected" flag of its own, so it must not follow the row's activated state.
        val r = row(ctx, null, title, subtitle, null, lead = ring, onClick = onClick)
        r.setAccessibilityDelegate(object : View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: android.view.accessibility.AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.className = "android.widget.RadioButton"
                info.isCheckable = true
                info.isChecked = ring.isActivated
            }
        })
        return r
    }

    /** `[-]  4  [+]`: two 40 dp outlined square buttons around a tabular value. */
    fun stepper(ctx: Context, value: Int, min: Int, max: Int, onChange: (Int) -> Unit): View {
        var v = value.coerceIn(min, max)
        val num = text(ctx, v.toString(), Type.ROW).apply {
            gravity = Gravity.CENTER; fontFeatureSettings = "tnum"; minWidth = dp(ctx, 32)
        }
        fun btn(glyph: Int, desc: String, step: Int): View = FrameLayout(ctx).apply {
            val r = dpf(ctx, Theme.R_CTRL)
            background = rect(0, r, Theme.LINE_STRONG, Theme.rule(ctx))
            contentDescription = desc
            addView(stateIcon(ctx, glyph, Theme.INK, Theme.BG, 20), FrameLayout.LayoutParams(dp(ctx, 20), dp(ctx, 20), Gravity.CENTER))
            focusFill(this, null, 1.06f, Theme.R_CTRL, background)
            setOnClickListener {
                val n = (v + step).coerceIn(min, max)
                if (n != v) { v = n; num.text = v.toString(); haptic(it, Haptic.TICK); onChange(v) }
            }
        }
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(btn(R.drawable.ic_minus, ctx.getString(R.string.ui_decrease), -1), LinearLayout.LayoutParams(dp(ctx, 40), dp(ctx, 40)))
            addView(num, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(ctx, 4); marginEnd = dp(ctx, 4) })
            addView(btn(R.drawable.ic_plus, ctx.getString(R.string.ui_increase), 1), LinearLayout.LayoutParams(dp(ctx, 40), dp(ctx, 40)))
        }
    }

    // ---------------------------------------------------------------- layout helpers

    fun spacer(ctx: Context, heightDp: Int): View = View(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(MATCH, dp(ctx, heightDp))
    }

    /** 56 dp header: back (bare icon button), T_TITLE, optional trailing view. */
    fun header(ctx: Context, title: String, onBack: (() -> Unit)?, trailing: View? = null): View =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(ctx, if (tv(ctx)) 64 else 56)
            val g = gutter(ctx)
            if (onBack != null) {
                setPadding(dp(ctx, 4), 0, g, 0)
                val b = iconButton(ctx, R.drawable.ic_back, ctx.getString(R.string.cd_back), onBack)
                addView(b, LinearLayout.LayoutParams(b.layoutParams.width, b.layoutParams.height))
            } else setPadding(g, 0, g, 0)
            addView(text(ctx, title, Type.TITLE).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                if (onBack != null) setPadding(dp(ctx, 4), 0, 0, 0)
            }, LinearLayout.LayoutParams(0, WRAP, 1f))
            if (trailing != null) {
                addView(trailing, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(ctx, 12) })
            }
        }

    /**
     * Vertical scroll page. Full-bleed: rules run edge to edge and rows / sections apply the gutter
     * themselves. [gutter] pads the content for screens that are not rebuilt yet. TV: 27 dp top safe.
     */
    fun scrollPage(ctx: Context, content: LinearLayout, gutter: Boolean = false, bottomDp: Int = 24): ScrollView =
        ScrollView(ctx).apply {
            isFocusable = false   // a focus stop with no visible focus is a trap
            overScrollMode = View.OVER_SCROLL_NEVER
            isVerticalScrollBarEnabled = false
            isFillViewport = true
            clipToPadding = false
            clipChildren = false
            val g = if (gutter) gutter(ctx) else 0
            setPadding(g, if (tv(ctx)) dp(ctx, Theme.TV_SAFE_V) else 0, g, dp(ctx, if (tv(ctx)) maxOf(bottomDp, Theme.TV_SAFE_V) else bottomDp))
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

    /** The docked bottom bar: SURFACE, 1 px LINE top edge, buttons laid out by the caller, bottom inset applied. */
    fun dock(ctx: Context): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = panelBackground(ctx, 0f)
        val g = gutter(ctx)
        setPadding(g, dp(ctx, 12) + Theme.rule(ctx), g, dp(ctx, 12))
        applyInsets(this, false, true)
    }

    // ---------------------------------------------------------------- snackbar

    /**
     * INK block (BG text) that slides 8 dp up and hides itself. kind: 0 info, 1 success, 2 error.
     * An optional [action] is a small solid block in [actionJob]'s colour.
     */
    fun snackbar(
        root: ViewGroup, msg: String, kind: Int = 0,
        action: String? = null, actionJob: Job = Job.SEND, onAction: (() -> Unit)? = null
    ) {
        val ctx = root.context
        (root.getTag(K_SNACK) as? View)?.let { old ->
            (old.getTag(K_HIDE) as? Runnable)?.let { root.removeCallbacks(it) }
            old.animate().cancel()
            root.removeView(old)
        }
        val bar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(ctx, 48)
            setPadding(dp(ctx, 16), dp(ctx, 8), dp(ctx, if (action != null) 8 else 16), dp(ctx, 8))
            background = rect(Theme.INK, dpf(ctx, Theme.R_CTRL))
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        val glyph = when (kind) { 1 -> R.drawable.ic_check; 2 -> R.drawable.ic_close; else -> null }
        if (glyph != null) {
            bar.addView(icon(ctx, glyph, Theme.BG, 18),
                LinearLayout.LayoutParams(dp(ctx, 18), dp(ctx, 18)).apply { marginEnd = dp(ctx, 12) })
        }
        bar.addView(text(ctx, msg, Type.BODY, Theme.BG).apply {
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
            maxWidth = dp(ctx, 460)
        }, LinearLayout.LayoutParams(WRAP, WRAP))
        if (action != null && onAction != null) {
            val b = TextView(ctx).apply {
                text = action
                textSize = Type.BUTTON.sp
                typeface = Theme.MEDIUM
                includeFontPadding = false
                setTextColor(actionJob.on)
                gravity = Gravity.CENTER
                minHeight = dp(ctx, 36)
                setPadding(dp(ctx, 12), dp(ctx, 6), dp(ctx, 12), dp(ctx, 6))
                background = rect(actionJob.fill, dpf(ctx, Theme.R_CTRL))
                isFocusable = true
                setOnClickListener { onAction(); root.removeCallbacks(bar.getTag(K_HIDE) as? Runnable ?: Runnable {}); (bar.getTag(K_HIDE) as? Runnable)?.run() }
            }
            bar.addView(b, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(ctx, 16) })
        }
        val inset = currentInsets(root)
        val lp = FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            val side = dp(ctx, 16)
            leftMargin = side + inset[0]
            rightMargin = side + inset[2]
            bottomMargin = inset[3] + dp(ctx, if (tv(ctx)) 36 else 24)
        }
        root.addView(bar, lp)
        root.setTag(K_SNACK, bar)
        bar.announceForAccessibility(msg)

        val animate = Anim.on(ctx)
        if (animate) {
            bar.alpha = 0f
            bar.translationY = dpf(ctx, 8f)
            bar.animate().alpha(1f).translationY(0f).setDuration(Theme.D_STATE)
                .setInterpolator(Theme.EASE_ENTER).start()
        }
        val hide = Runnable {
            fun done() {
                if (root.getTag(K_SNACK) === bar) root.setTag(K_SNACK, null)
                root.removeView(bar)
            }
            if (animate && bar.isAttachedToWindow) {
                bar.animate().alpha(0f).translationY(dpf(ctx, 8f)).setDuration(Theme.D_STATE)
                    .setInterpolator(Theme.EASE_EXIT).withEndAction { done() }.start()
            } else done()
        }
        bar.setTag(K_HIDE, hide)
        bar.setOnClickListener { root.removeCallbacks(hide); hide.run() }
        root.postDelayed(hide, if (kind == 2 || action != null) 4200L else 2800L)
    }

    // ---------------------------------------------------------------- segmented control + tabs

    /** 1 px LINE_STRONG outline, 6 dp outer radius; the selected segment is an INK fill with BG text. Instant swap. */
    private class SegmentedView(
        ctx: Context, options: List<String>, selected: Int, private val onSelect: (Int) -> Unit
    ) : LinearLayout(ctx) {
        private val labels = ArrayList<TextView>()
        private val n = maxOf(1, options.size)
        private var sel = selected.coerceIn(0, n - 1)

        init {
            orientation = HORIZONTAL
            val r = Ui.dpf(ctx, Theme.R_CTRL)
            background = Ui.rect(0, r, Theme.LINE_STRONG, Theme.rule(ctx))
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, o: Outline) { o.setRoundRect(0, 0, v.width, v.height, r) }
            }
            options.forEachIndexed { i, o ->
                val t = TextView(ctx).apply {
                    text = o
                    textSize = Type.BUTTON.sp
                    typeface = Theme.MEDIUM
                    includeFontPadding = false
                    gravity = Gravity.CENTER
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    setPadding(Theme.dp(ctx, 8), Theme.dp(ctx, 11), Theme.dp(ctx, 8), Theme.dp(ctx, 11))
                    minHeight = Theme.dp(ctx, if (Ui.tv(ctx)) 56 else 48)
                    setOnClickListener { Ui.haptic(it, Haptic.TICK); select(i, true) }
                }
                Ui.focusFill(t, null, 1.03f, 0f, null)
                labels.add(t)
                addView(t, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            }
            paint()
        }

        private fun paint() {
            labels.forEachIndexed { i, t ->
                if (i == sel) {
                    t.setTextColor(Theme.BG)
                    // The selected segment is a plain INK block; focus adds an inset BG ring so it stays visible.
                    t.background = StateListDrawable().apply {
                        addState(intArrayOf(android.R.attr.state_focused), Ui.rect(Theme.INK, 0f, Theme.BG, Theme.dp(t.context, 3)))
                        addState(intArrayOf(), ColorDrawable(Theme.INK))
                    }
                    t.isFocusable = true
                } else {
                    // Focus fills INK, so the text flips to BG with it.
                    t.setTextColor(states(Theme.INK, Theme.BG, false))
                    Ui.focusFill(t, null, 1.03f, 0f, null)
                }
            }
        }

        fun select(i: Int, user: Boolean) {
            val ni = i.coerceIn(0, n - 1)
            if (ni == sel) return
            sel = ni
            paint()
            if (user) onSelect(ni)
        }
    }

    fun segmented(ctx: Context, options: List<String>, selected: Int, onSelect: (Int) -> Unit): View =
        SegmentedView(ctx, options, selected, onSelect)

    /**
     * Tabs: the selected tab is a solid 6 dp block in its [Job] colour (INK when the job is null) with
     * the job's ON text; the others are INK_2 text. It moves instantly.
     */
    class Tabs internal constructor(ctx: Context) : HorizontalScrollView(ctx) {
        internal val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        internal val views = ArrayList<TextView>()
        internal var items: List<Pair<String, Job?>> = emptyList()
        var selected = 0
            private set
        var onSelect: ((Int) -> Unit)? = null

        init {
            isFocusable = false
            isHorizontalScrollBarEnabled = false
            overScrollMode = OVER_SCROLL_NEVER
            isFillViewport = true
            addView(row, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        fun select(i: Int, user: Boolean = false) {
            if (i !in views.indices) return
            selected = i
            paint()
            if (user) onSelect?.invoke(i)
        }

        internal fun paint() {
            val ctx = context
            val r = Ui.dpf(ctx, Theme.R_CTRL)
            views.forEachIndexed { i, t ->
                val job = items[i].second
                val sel = i == selected
                val fill = job?.fill ?: Theme.INK
                val on = job?.on ?: Theme.BG
                t.setTextColor(if (sel) on else Theme.INK_2)
                t.isSelected = sel
                Ui.focusFill(t, job, 1.06f, Theme.R_CTRL, if (sel) Ui.rect(fill, r) else null)
                if (sel) t.setTextColor(on)
                else t.setTextColor(Ui.states(Theme.INK_2, Ui.onFill(job), Ui.tv(ctx)))
            }
        }
    }

    fun tabs(ctx: Context, items: List<Pair<String, Job?>>, selected: Int = 0, onSelect: (Int) -> Unit): Tabs =
        Tabs(ctx).apply {
            this.items = items
            this.onSelect = onSelect
            val g = Ui.gutter(ctx)
            row.setPadding(g - dp(ctx, 4), dp(ctx, 4), g, dp(ctx, 4))
            items.forEachIndexed { i, it ->
                val t = TextView(ctx).apply {
                    text = it.first
                    textSize = if (tv(ctx)) Type.ROW.tvSp else Type.BUTTON.sp
                    typeface = Theme.MEDIUM
                    includeFontPadding = false
                    gravity = Gravity.CENTER
                    maxLines = 1
                    minHeight = dp(ctx, if (tv(ctx)) 48 else 40)
                    setPadding(dp(ctx, 12), dp(ctx, 8), dp(ctx, 12), dp(ctx, 8))
                    setOnClickListener { Ui.haptic(it, Haptic.TICK); select(i, true) }
                }
                views.add(t)
                row.addView(t, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(ctx, 4) })
            }
            select(selected)
        }
}
