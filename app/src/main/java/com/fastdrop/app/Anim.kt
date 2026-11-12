package com.fastdrop.app

import android.animation.ObjectAnimator
import android.content.Context
import android.view.View
import android.view.animation.LinearInterpolator

/** Small motion helpers. Every one of them is instant when the user turned animations off. */
object Anim {
    fun on(ctx: Context): Boolean = Theme.animationsOn(ctx)

    private fun dpf(v: View, d: Float) = d * v.resources.displayMetrics.density

    /** Fade + 8 dp rise into place (a row appearing). */
    fun enter(v: View, delayMs: Long = 0) {
        if (!on(v.context)) {
            v.alpha = 1f; v.translationY = 0f
            return
        }
        v.animate().cancel()
        v.alpha = 0f
        v.translationY = dpf(v, 8f)
        v.animate().alpha(1f).translationY(0f)
            .setStartDelay(delayMs)
            .setDuration(Theme.D_STATE)
            .setInterpolator(Theme.EASE_ENTER)
            .start()
    }

    /** Quick left-right shake (input validation only). */
    fun shake(v: View) {
        if (!on(v.context)) return
        val d = dpf(v, 10f)
        ObjectAnimator.ofFloat(v, View.TRANSLATION_X, 0f, -d, d, -d * 0.8f, d * 0.6f, -d * 0.3f, 0f).apply {
            duration = 380
            interpolator = LinearInterpolator()
            start()
        }
    }

    /** Fades [out] away (then hides it) while [into] fades in. */
    fun crossfade(out: View?, into: View) {
        val animate = on(into.context)
        into.animate().cancel()
        into.visibility = View.VISIBLE
        if (animate) {
            into.alpha = 0f
            into.animate().alpha(1f).setStartDelay(0).setDuration(Theme.D_STATE).setInterpolator(Theme.EASE_ENTER).start()
        } else into.alpha = 1f
        if (out != null && out !== into) {
            out.animate().cancel()
            if (animate) {
                out.animate().alpha(0f).setStartDelay(0).setDuration(Theme.D_STATE).setInterpolator(Theme.EASE_EXIT)
                    .withEndAction { out.visibility = View.GONE; out.alpha = 1f }.start()
            } else out.visibility = View.GONE
        }
    }

    /** New screen: 150 ms crossfade with an 8 dp horizontal shift (crossfade only on TV). */
    fun screenIn(v: View, forward: Boolean) {
        v.animate().cancel()
        if (!on(v.context)) {
            v.alpha = 1f; v.translationX = 0f
            return
        }
        v.alpha = 0f
        v.translationX = if (Ui.tv(v.context)) 0f else dpf(v, if (forward) 8f else -8f)
        v.animate().alpha(1f).translationX(0f)
            .setStartDelay(0)
            .setDuration(Theme.D_STATE)
            .setInterpolator(Theme.EASE_ENTER)
            .start()
    }

    /** Old screen fades away, then [end] runs (exactly once, always). */
    fun screenOut(v: View, forward: Boolean, end: () -> Unit) {
        v.animate().cancel()
        if (!on(v.context)) {
            end()
            return
        }
        var called = false
        val once = Runnable { if (!called) { called = true; end() } }
        v.animate().alpha(0f).translationX(if (Ui.tv(v.context)) 0f else dpf(v, if (forward) -8f else 8f))
            .setStartDelay(0)
            .setDuration(Theme.D_STATE)
            .setInterpolator(Theme.EASE_EXIT)
            .withEndAction(once)
            .start()
    }
}
