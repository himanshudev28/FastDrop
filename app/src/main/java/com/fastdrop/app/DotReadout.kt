package com.fastdrop.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View

/**
 * The signature: a short numeric string drawn as round dots on a 7 x 11 grid (a seven-segment
 * skeleton rendered in dots, so every digit has the same width and the strokes read cleanly at a
 * glance). Lit dots use the panel's ON colour; unlit dots are drawn everywhere in a very subtle
 * tone so the panel reads as an instrument. Characters: digits, ".", "-" and space.
 *
 * Draws on a Canvas with cached Paints and allocates nothing in onDraw. The view is transparent:
 * it sits on a [TransferPanel] (or the offer panel) that supplies the solid colour. Callers update
 * [setText] at most about 4 times a second; identical text does not redraw.
 *
 * Sizes (dp): phone pitch 6 / dot 4.6, code pitch 5 / dot 3.8, TV pitch 8 / dot 6.
 */
class DotReadout(ctx: Context, pitchDp: Float, dotDp: Float, template: String = "8.88") : View(ctx) {
    private val pitch = pitchDp * ctx.resources.displayMetrics.density
    private val radius = dotDp * ctx.resources.displayMetrics.density / 2f
    private val lit = Paint(Paint.ANTI_ALIAS_FLAG)
    private val unlit = Paint(Paint.ANTI_ALIAS_FLAG)
    private var chars = CharArray(0)
    private var cells = 0
    private var widthPitches = 0

    init {
        lit.color = Theme.INK
        unlit.color = Theme.LINE
        widthPitches = pitchesOf(template)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    /** Fixed layout width for a string like "8.88" or "0000", so the readout never jumps around. */
    fun setTemplate(template: String) {
        widthPitches = pitchesOf(template)
        requestLayout()
    }

    fun setColors(litColor: Int, unlitColor: Int) {
        if (lit.color == litColor && unlit.color == unlitColor) return
        lit.color = litColor
        unlit.color = unlitColor
        invalidate()
    }

    /** Colours for a job panel: lit = the job's ON colour, unlit = a tone just off the panel fill. null = neutral panel. */
    fun setJob(job: Job?) {
        if (job == null) setColors(Theme.INK, Theme.LINE)
        else setColors(job.on, mix(job.fill, job.shade, 0.55f))
    }

    private fun mix(a: Int, b: Int, t: Float): Int {
        fun ch(x: Int, y: Int) = (x + (y - x) * t).toInt()
        return Color.rgb(ch(Color.red(a), Color.red(b)), ch(Color.green(a), Color.green(b)), ch(Color.blue(a), Color.blue(b)))
    }

    fun setText(s: String) {
        if (s.length == cells && same(s)) return
        chars = s.toCharArray()
        cells = chars.size
        val need = pitchesOf(s)
        if (need > widthPitches) { widthPitches = need; requestLayout() }
        invalidate()
    }

    private fun same(s: String): Boolean {
        for (i in chars.indices) if (chars[i] != s[i]) return false
        return true
    }

    private fun pitchesOf(s: String): Int {
        var n = 0
        for (ch in s) n += if (ch == '.') 2 else 8
        return maxOf(0, n - 1)
    }

    override fun onMeasure(wSpec: Int, hSpec: Int) {
        val w = (widthPitches * pitch).toInt()
        val h = (10 * pitch + radius * 2f).toInt()
        setMeasuredDimension(w, h)
    }

    override fun onDraw(c: Canvas) {
        var x = radius
        for (i in 0 until cells) {
            val ch = chars[i]
            if (ch == '.') {
                // One column; only the bottom dot is lit.
                for (row in 0 until 11) c.drawCircle(x, radius + row * pitch, radius, if (row == 10) lit else unlit)
                x += 2 * pitch
                continue
            }
            val mask = when (ch) {
                in '0'..'9' -> MASKS[ch - '0']
                '-' -> 1 shl 6
                else -> 0
            }
            for (row in 0 until 11) {
                val cy = radius + row * pitch
                for (col in 0 until 7) {
                    val seg = SEG[row * 7 + col]
                    val on = seg >= 0 && (mask shr seg) and 1 == 1
                    c.drawCircle(x + col * pitch, cy, radius, if (on) lit else unlit)
                }
            }
            x += 8 * pitch
        }
    }

    companion object {
        // Segment index per grid position: 0 top, 1 top-right, 2 bottom-right, 3 bottom, 4 bottom-left, 5 top-left, 6 middle.
        private val SEG = IntArray(77) { i ->
            val row = i / 7; val col = i % 7
            when {
                row == 0 && col in 1..5 -> 0
                col == 6 && row in 1..4 -> 1
                col == 6 && row in 6..9 -> 2
                row == 10 && col in 1..5 -> 3
                col == 0 && row in 6..9 -> 4
                col == 0 && row in 1..4 -> 5
                row == 5 && col in 1..5 -> 6
                else -> -1
            }
        }
        private fun m(vararg s: Int): Int { var r = 0; for (x in s) r = r or (1 shl x); return r }
        private val MASKS = intArrayOf(
            m(0, 1, 2, 3, 4, 5), m(1, 2), m(0, 1, 6, 4, 3), m(0, 1, 6, 2, 3), m(5, 6, 1, 2),
            m(0, 5, 6, 2, 3), m(0, 5, 6, 4, 3, 2), m(0, 1, 2), m(0, 1, 2, 3, 4, 5, 6), m(0, 1, 2, 3, 5, 6),
        )

        /** Speed as at most 4 cells: 8.42 / 46.2 / 146, with the unit chosen from the value (MB/s or KB/s). */
        fun format(bytesPerSec: Double): Pair<String, Boolean> {
            val mb = bytesPerSec / (1024 * 1024)
            val inMb = mb >= 1.0
            val v = if (inMb) mb else bytesPerSec / 1024
            val s = when {
                v < 10 -> String.format(java.util.Locale.US, "%.2f", v)
                v < 100 -> String.format(java.util.Locale.US, "%.1f", v)
                else -> String.format(java.util.Locale.US, "%.0f", minOf(v, 999.0))
            }
            return s to inMb
        }
    }
}
