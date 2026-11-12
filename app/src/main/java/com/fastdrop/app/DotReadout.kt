package com.fastdrop.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View

/**
 * The signature: a short numeric string drawn as 5 x 7 dots (docs/DESIGN.md 2.9), lit dots in the
 * panel's ON colour, unlit dots in its shade. Characters: digits, ".", "-" and space.
 *
 * Draws on a Canvas with cached Paints and allocates nothing in onDraw. The view is transparent:
 * it sits on a [TransferPanel] (or the offer panel) that supplies the solid colour. Callers update
 * [setText] at most about 4 times a second; identical text does not redraw.
 *
 * Sizes (dp): phone pitch 9 / dot 7, code pitch 8 / dot 6, TV pitch 12 / dot 9.
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

    /** Colours for a job panel: lit = the job's ON colour, unlit = its shade. null = neutral panel. */
    fun setJob(job: Job?) {
        if (job == null) setColors(Theme.INK, Theme.LINE) else setColors(job.on, job.shade)
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
        for (ch in s) n += if (ch == '.') 2 else 6
        return maxOf(0, n - 1)
    }

    override fun onMeasure(wSpec: Int, hSpec: Int) {
        val w = (widthPitches * pitch).toInt()
        val h = (6 * pitch + radius * 2f).toInt()
        setMeasuredDimension(w, h)
    }

    override fun onDraw(c: Canvas) {
        var x = radius
        for (i in 0 until cells) {
            val ch = chars[i]
            if (ch == '.') {
                // One column, dot on the bottom row; the other rows stay unlit.
                for (row in 0 until 7) {
                    c.drawCircle(x, radius + row * pitch, radius, if (row == 6) lit else unlit)
                }
                x += 2 * pitch
                continue
            }
            val g = glyphIndex(ch)
            for (row in 0 until 7) {
                val bits = if (g < 0) 0 else GLYPHS[g * 7 + row]
                val cy = radius + row * pitch
                for (col in 0 until 5) {
                    val on = (bits shr (4 - col)) and 1 == 1
                    c.drawCircle(x + col * pitch, cy, radius, if (on) lit else unlit)
                }
            }
            x += 6 * pitch
        }
    }

    private fun glyphIndex(ch: Char): Int = when (ch) {
        in '0'..'9' -> ch - '0'
        '-' -> 10
        else -> -1
    }

    companion object {
        /** 0-9 then "-", seven 5-bit rows each (MSB = left column). */
        private val GLYPHS = intArrayOf(
            0b01110, 0b10001, 0b10011, 0b10101, 0b11001, 0b10001, 0b01110,   // 0 (slashed)
            0b00100, 0b01100, 0b00100, 0b00100, 0b00100, 0b00100, 0b01110,   // 1
            0b01110, 0b10001, 0b00001, 0b00010, 0b00100, 0b01000, 0b11111,   // 2
            0b11111, 0b00010, 0b00100, 0b00010, 0b00001, 0b10001, 0b01110,   // 3
            0b00010, 0b00110, 0b01010, 0b10010, 0b11111, 0b00010, 0b00010,   // 4
            0b11111, 0b10000, 0b11110, 0b00001, 0b00001, 0b10001, 0b01110,   // 5
            0b00110, 0b01000, 0b10000, 0b11110, 0b10001, 0b10001, 0b01110,   // 6
            0b11111, 0b00001, 0b00010, 0b00100, 0b01000, 0b01000, 0b01000,   // 7
            0b01110, 0b10001, 0b10001, 0b01110, 0b10001, 0b10001, 0b01110,   // 8
            0b01110, 0b10001, 0b10001, 0b01111, 0b00001, 0b00010, 0b01100,   // 9
            0b00000, 0b00000, 0b00000, 0b01111, 0b00000, 0b00000, 0b00000,   // -
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
