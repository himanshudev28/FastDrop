package com.fastdrop.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.drawable.Drawable
import android.text.TextPaint
import android.text.TextUtils
import android.view.MotionEvent
import android.view.View
import android.view.animation.OvershootInterpolator

/**
 * Radar: waves expanding from the centre, a rotating sweep, and device "blips" on two orbits.
 * Blip angles come from the id hash so a device keeps its spot. Tap a blip (touch) to select it.
 * Animates only while attached, visible and started; draws without per-frame allocations.
 */
class RadarView(ctx: Context) : View(ctx) {
    class Blip(val id: String, val label: String, val type: String, val color: Int)

    var colors: IntArray = Theme.SEND
        set(value) {
            field = if (value.size >= 2) value else intArrayOf(
                value.firstOrNull() ?: Theme.ACCENT, value.firstOrNull() ?: Theme.ACCENT
            )
            rebuild()
            invalidate()
        }

    /** Text shown inside the centre disc (shrunk to fit). */
    var centerLabel: String? = null
        set(value) {
            field = value
            invalidate()
        }

    var onBlipClick: ((String) -> Unit)? = null

    private class BlipState(val blip: Blip, val orbit: Int, val angle: Float, val label: String, val glow: RadialGradient) {
        val ux = Math.cos(Math.toRadians(angle.toDouble())).toFloat()
        val uy = Math.sin(Math.toRadians(angle.toDouble())).toFloat()
        val phase = (angle % 97f) / 97f
        var t = 0f          // 0..1 pop progress
        var dir = 1         // 1 = appearing/visible, -1 = leaving
        var x = 0f
        var y = 0f
    }

    private val density = ctx.resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    private val states = ArrayList<BlipState>()
    private val pop = OvershootInterpolator(1.7f)

    private val guide = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = dp(1f); color = 0x14FFFFFF
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val wave = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(1.6f) }
    private val sweep = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = dp(1.5f); strokeCap = Paint.Cap.ROUND
    }
    private val disc = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = dp(1.5f); color = 0x40FFFFFF
    }
    private val labelPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Theme.TEXT; textSize = dp(11f); typeface = Theme.MEDIUM; textAlign = Paint.Align.CENTER
    }
    private val centerPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; typeface = Theme.BOLD; textAlign = Paint.Align.CENTER
    }

    private var bgShader: RadialGradient? = null
    private var sweepShader: SweepGradient? = null
    private var discShader: LinearGradient? = null
    private var haloShader: RadialGradient? = null
    private val sweepMatrix = Matrix()

    private val icons = HashMap<String, Drawable?>()

    private var cx = 0f
    private var cy = 0f
    private var radius = 0f
    private var centerR = 0f
    private val blipR = dp(19f)

    private var time = 0f
    private var last = 0L
    private var running = true
    private var active = false

    init {
        isClickable = false
        isFocusable = false     // on TV, focus lives on the device list
    }

    // ------------------------------------------------------------------ public API

    fun start() { running = true; refresh() }
    fun stop() { running = false; refresh() }

    /** Replaces the blips. Existing ids keep their position; new ones pop in, missing ones pop out. */
    fun setBlips(list: List<Blip>) {
        val ids = HashSet<String>()
        for (b in list) ids.add(b.id)
        // Leaving
        for (s in states) if (s.blip.id !in ids) s.dir = -1
        for (b in list) {
            val idx = states.indexOfFirst { it.blip.id == b.id }
            if (idx >= 0) {
                val old = states[idx]
                if (old.dir > 0 && old.blip.label == b.label && old.blip.color == b.color && old.blip.type == b.type) continue
                // Same device, new look: swap in place, keep position and pop state.
                val s = makeState(b, old.orbit, old.angle)
                s.t = old.t; s.dir = 1
                states[idx] = s
                continue
            }
            val (orbit, angle) = place(b.id)
            states.add(makeState(b, orbit, angle))
        }
        if (!Anim.on(context)) {
            for (i in states.indices.reversed()) {
                val s = states[i]
                if (s.dir < 0) states.removeAt(i) else s.t = 1f
            }
        }
        refresh()
        invalidate()
    }

    // ------------------------------------------------------------------ layout of blips

    private fun makeState(b: Blip, orbit: Int, angle: Float): BlipState {
        val text = TextUtils.ellipsize(b.label, labelPaint, dp(78f), TextUtils.TruncateAt.END).toString()
        val g = RadialGradient(
            0f, 0f, blipR * 2.3f,
            intArrayOf(Theme.alpha(b.color, 0.5f), Theme.alpha(b.color, 0f)), null, Shader.TileMode.CLAMP
        )
        return BlipState(b, orbit, angle, text, g)
    }

    /** Stable orbit + angle from the id hash, nudged away from blips already on that orbit. */
    private fun place(id: String): Pair<Int, Float> {
        val h = id.hashCode() * -1640531535
        var orbit = (h ushr 16) and 1
        var angle = (((h ushr 1) % 360) + 360) % 360f
        var tries = 0
        while (tries < 24) {
            val minSep = if (orbit == 0) 62f else 40f
            var clash = false
            for (s in states) {
                if (s.dir < 0 || s.orbit != orbit) continue
                var d = Math.abs(s.angle - angle) % 360f
                if (d > 180f) d = 360f - d
                if (d < minSep) { clash = true; break }
            }
            if (!clash) break
            angle = (angle + 37f) % 360f
            tries++
            if (tries == 12) orbit = 1 - orbit
        }
        return Pair(orbit, angle)
    }

    private fun orbitR(orbit: Int) = radius * if (orbit == 0) 0.46f else 0.80f

    // ------------------------------------------------------------------ sizing + shaders

    override fun onMeasure(wSpec: Int, hSpec: Int) {
        val w = resolveSize(dp(300f).toInt(), wSpec)
        val h = resolveSize(w, hSpec)
        setMeasuredDimension(w, h)
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        cx = w / 2f
        cy = h / 2f
        radius = maxOf(dp(40f), minOf(w, h) / 2f - dp(34f))
        centerR = minOf(dp(32f), radius * 0.26f)
        rebuild()
    }

    private fun rebuild() {
        if (radius <= 0f) return
        val a = colors[0]
        val b = colors[colors.size - 1]
        bgShader = RadialGradient(0f, 0f, radius, intArrayOf(Theme.alpha(a, 0.20f), Theme.alpha(a, 0.05f), Theme.alpha(a, 0f)),
            floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
        // Trail behind the leading edge (at 360 degrees), clear elsewhere.
        sweepShader = SweepGradient(0f, 0f,
            intArrayOf(Theme.alpha(a, 0f), Theme.alpha(a, 0f), Theme.alpha(b, 0.42f)),
            floatArrayOf(0f, 0.62f, 1f))
        discShader = LinearGradient(-centerR, -centerR, centerR, centerR, colors, null, Shader.TileMode.CLAMP)
        haloShader = RadialGradient(0f, 0f, centerR * 2.4f,
            intArrayOf(Theme.alpha(a, 0.55f), Theme.alpha(a, 0f)), null, Shader.TileMode.CLAMP)
        sweep.shader = sweepShader
        wave.color = a
        line.color = Theme.alpha(b, 0.6f)
    }

    // ------------------------------------------------------------------ drawing

    private fun iconFor(type: String): Drawable? = icons.getOrPut(type) {
        val res = when (type.lowercase()) {
            "bolt" -> R.drawable.ic_bolt
            "tv" -> R.drawable.ic_tv
            "laptop", "pc", "computer", "desktop" -> R.drawable.ic_laptop
            else -> R.drawable.ic_phone
        }
        context.getDrawable(res)?.mutate()?.also { it.setTint(Color.WHITE) }
    }

    override fun onDraw(c: Canvas) {
        if (radius <= 0f) return
        val now = System.nanoTime()
        val dt = if (last == 0L) 0f else minOf(0.05f, (now - last) / 1_000_000_000f)
        last = now
        if (active) time += dt

        // Advance blip pop progress.
        var moving = false
        val animate = Anim.on(context)
        for (i in states.indices.reversed()) {
            val s = states[i]
            if (!animate) {
                if (s.dir < 0) { states.removeAt(i); continue }
                s.t = 1f
                continue
            }
            val nt = (s.t + s.dir * dt / 0.45f).coerceIn(0f, 1f)
            s.t = nt
            if (s.dir < 0 && nt <= 0f) { states.removeAt(i); continue }
            if (nt in 0.001f..0.999f || (s.dir > 0 && nt < 1f)) moving = true
        }

        val save = c.save()
        c.translate(cx, cy)

        // Soft disc + orbit guides
        fill.shader = bgShader
        c.drawCircle(0f, 0f, radius, fill)
        c.drawCircle(0f, 0f, radius, guide)
        c.drawCircle(0f, 0f, orbitR(1), guide)
        c.drawCircle(0f, 0f, orbitR(0), guide)

        // Expanding waves
        for (k in 0 until 3) {
            val p = ((time / 3.4f) + k / 3f) % 1f
            val r = centerR + p * (radius - centerR)
            val inv = 1f - p
            wave.alpha = (150 * inv * inv).toInt()
            c.drawCircle(0f, 0f, r, wave)
        }

        // Rotating sweep
        val ang = (time * 115f) % 360f
        sweepMatrix.setRotate(ang)
        sweepShader?.setLocalMatrix(sweepMatrix)
        c.drawCircle(0f, 0f, radius, sweep)
        val s2 = c.save()
        c.rotate(ang)
        c.drawLine(centerR, 0f, radius, 0f, line)
        c.restoreToCount(s2)

        // Blips
        for (s in states) drawBlip(c, s)

        // Centre disc with halo
        val pulse = 1f + 0.05f * Math.sin(time * 2.4).toFloat()
        fill.shader = haloShader
        c.drawCircle(0f, 0f, centerR * 2.4f * pulse, fill)
        disc.shader = discShader
        disc.alpha = 255
        c.drawCircle(0f, 0f, centerR, disc)
        c.drawCircle(0f, 0f, centerR, rim)
        val label = centerLabel
        if (!label.isNullOrEmpty()) {
            var size = dp(15f)
            centerPaint.textSize = size
            val maxW = centerR * 1.55f
            val w = centerPaint.measureText(label)
            if (w > maxW) size = maxOf(dp(9f), size * maxW / w)
            centerPaint.textSize = size
            val shown = if (centerPaint.measureText(label) > maxW)
                TextUtils.ellipsize(label, centerPaint, maxW, TextUtils.TruncateAt.END).toString() else label
            c.drawText(shown, 0f, -(centerPaint.ascent() + centerPaint.descent()) / 2f, centerPaint)
        } else {
            val d = iconFor("bolt")
            val r = (centerR * 0.5f).toInt()
            d?.setBounds(-r, -r, r, r)
            d?.draw(c)
        }
        c.restoreToCount(save)

        if (active || moving) postInvalidateOnAnimation()
    }

    private fun drawBlip(c: Canvas, s: BlipState) {
        val bob = if (active) Math.sin((time * 1.6f + s.phase * 6.28f).toDouble()).toFloat() * dp(2.5f) else 0f
        val orad = orbitR(s.orbit)
        s.x = cx + s.ux * orad
        s.y = cy + s.uy * orad + bob
        val sc = if (s.dir > 0) pop.getInterpolation(s.t) else s.t
        if (sc <= 0.01f) return
        val save = c.save()
        c.translate(s.x - cx, s.y - cy)
        c.scale(sc, sc)
        val a = (255 * s.t.coerceIn(0f, 1f)).toInt()

        fill.shader = s.glow
        fill.alpha = a
        c.drawCircle(0f, 0f, blipR * 2.3f, fill)
        fill.alpha = 255

        // Soft pulse ring
        val p = (time * 0.7f + s.phase) % 1f
        wave.color = s.blip.color
        wave.alpha = (110 * (1f - p) * s.t).toInt()
        c.drawCircle(0f, 0f, blipR * (1f + 0.7f * p), wave)
        wave.color = colors[0]

        disc.shader = null
        disc.color = s.blip.color
        disc.alpha = a
        c.drawCircle(0f, 0f, blipR, disc)
        rim.alpha = (64 * s.t).toInt()
        c.drawCircle(0f, 0f, blipR, rim)
        rim.alpha = 64

        val ic = iconFor(s.blip.type)
        if (ic != null) {
            val r = (blipR * 0.55f).toInt()
            ic.setBounds(-r, -r, r, r)
            ic.alpha = a
            ic.draw(c)
        }
        labelPaint.alpha = a
        c.drawText(s.label, 0f, blipR + dp(15f), labelPaint)
        c.restoreToCount(save)
    }

    // ------------------------------------------------------------------ lifecycle

    private fun refresh() {
        val was = active
        active = running && isAttachedToWindow && isShown && windowVisibility == VISIBLE && Anim.on(context)
        if (active && !was) last = 0L
        if (active) postInvalidateOnAnimation()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        refresh()
    }

    override fun onDetachedFromWindow() {
        active = false
        last = 0L
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        refresh()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        refresh()
    }

    // ------------------------------------------------------------------ touch

    private var downId: String? = null

    private fun hit(x: Float, y: Float): BlipState? {
        val r = maxOf(blipR * 1.5f, dp(30f))
        var best: BlipState? = null
        var bestD = Float.MAX_VALUE
        for (s in states) {
            if (s.dir < 0 || s.t < 0.5f) continue
            val dx = x - s.x
            val dy = y - s.y
            val d = dx * dx + dy * dy
            if (d <= r * r && d < bestD) { best = s; bestD = d }
        }
        return best
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val h = hit(e.x, e.y) ?: return false
                downId = h.blip.id
                return true
            }
            MotionEvent.ACTION_UP -> {
                val id = downId
                downId = null
                if (id != null) {
                    val h = hit(e.x, e.y)
                    if (h != null && h.blip.id == id) {
                        Ui.haptic(this)
                        onBlipClick?.invoke(id)
                    }
                    return true
                }
            }
            MotionEvent.ACTION_CANCEL -> downId = null
        }
        return downId != null
    }
}
