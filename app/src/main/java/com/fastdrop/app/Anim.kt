package com.fastdrop.app

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.content.Context
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.TextView

/** Small motion helpers. Every one of them is instant when the user turned animations off. */
object Anim {
    private const val K_COUNT = 0x7f0fff11

    fun on(ctx: Context): Boolean = Theme.animationsOn(ctx)

    private fun dpf(v: View, d: Float) = d * v.resources.displayMetrics.density

    /** Fade + rise into place. */
    fun enter(v: View, delayMs: Long = 0) {
        if (!on(v.context)) {
            v.alpha = 1f; v.translationY = 0f
            return
        }
        v.animate().cancel()
        v.alpha = 0f
        v.translationY = dpf(v, 16f)
        v.animate().alpha(1f).translationY(0f)
            .setStartDelay(delayMs)
            .setDuration(Theme.DUR_SLOW)
            .setInterpolator(Theme.EASE_OUT)
            .start()
    }

    /** [enter] for a list of views, each a little after the previous one. */
    fun stagger(views: List<View>, startDelay: Long = 0, step: Long = 45) {
        views.forEachIndexed { i, v -> enter(v, startDelay + minOf(i, 14) * step) }
    }

    /** Scale-in with a springy overshoot. */
    fun pop(v: View) {
        if (!on(v.context)) {
            v.scaleX = 1f; v.scaleY = 1f; v.alpha = 1f
            return
        }
        v.animate().cancel()
        v.scaleX = 0.6f
        v.scaleY = 0.6f
        v.alpha = 0f
        v.animate().scaleX(1f).scaleY(1f).alpha(1f)
            .setStartDelay(0)
            .setDuration(Theme.DUR_SLOW)
            .setInterpolator(Theme.SPRING)
            .start()
    }

    /**
     * Gentle looping scale pulse. Starts immediately and is returned so the caller can cancel it;
     * it also stops by itself when the view detaches. Returns a finished no-op when animations are off.
     */
    fun pulse(v: View): Animator {
        if (!on(v.context)) return ValueAnimator.ofFloat(0f, 0f).apply { duration = 0 }
        val a = ObjectAnimator.ofPropertyValuesHolder(
            v,
            PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.08f),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.08f)
        ).apply {
            duration = 900
            interpolator = Theme.EASE_IN_OUT
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
        }
        val detach = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(x: View) {}
            override fun onViewDetachedFromWindow(x: View) { a.cancel() }
        }
        a.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationCancel(an: Animator) {
                v.scaleX = 1f; v.scaleY = 1f
                v.removeOnAttachStateChangeListener(detach)
            }
        })
        v.addOnAttachStateChangeListener(detach)
        a.start()
        return a
    }

    /** Quick left-right shake (errors). */
    fun shake(v: View) {
        if (!on(v.context)) return
        val d = dpf(v, 10f)
        ObjectAnimator.ofFloat(v, View.TRANSLATION_X, 0f, -d, d, -d * 0.8f, d * 0.6f, -d * 0.3f, 0f).apply {
            duration = 380
            interpolator = LinearInterpolator()
            start()
        }
    }

    /** Counts a number up/down, formatting each step with [fmt]. */
    fun countTo(tv: TextView, from: Long, to: Long, dur: Long = 600, fmt: (Long) -> String) {
        (tv.getTag(K_COUNT) as? ValueAnimator)?.cancel()
        if (!on(tv.context) || from == to || dur <= 0) {
            tv.text = fmt(to)
            return
        }
        var last = Long.MIN_VALUE
        val a = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = dur
            interpolator = Theme.EASE_OUT
            addUpdateListener {
                val value = from + ((to - from) * (it.animatedValue as Float)).toLong()
                if (value != last) {
                    last = value
                    tv.text = fmt(value)
                }
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(an: Animator) {
                    tv.text = fmt(to)
                    if (tv.getTag(K_COUNT) === an) tv.setTag(K_COUNT, null)
                }
            })
        }
        tv.setTag(K_COUNT, a)
        a.start()
    }

    /** Fades [out] away (then hides it) while [into] fades in. */
    fun crossfade(out: View?, into: View) {
        val animate = on(into.context)
        into.animate().cancel()
        into.visibility = View.VISIBLE
        if (animate) {
            into.alpha = 0f
            into.animate().alpha(1f).setStartDelay(0).setDuration(Theme.DUR).setInterpolator(Theme.EASE_OUT).start()
        } else into.alpha = 1f
        if (out != null && out !== into) {
            out.animate().cancel()
            if (animate) {
                out.animate().alpha(0f).setStartDelay(0).setDuration(Theme.DUR_FAST).setInterpolator(Theme.EASE_IN)
                    .withEndAction { out.visibility = View.GONE; out.alpha = 1f }.start()
            } else out.visibility = View.GONE
        }
    }

    /** New screen slides in from the right (forward) or the left (back) while fading in. */
    fun screenIn(v: View, forward: Boolean) {
        v.animate().cancel()
        if (!on(v.context)) {
            v.alpha = 1f; v.translationX = 0f
            return
        }
        v.alpha = 0f
        v.translationX = dpf(v, if (forward) 36f else -36f)
        v.animate().alpha(1f).translationX(0f)
            .setStartDelay(0)
            .setDuration(Theme.DUR_SLOW)
            .setInterpolator(Theme.EASE_OUT)
            .start()
    }

    /** Old screen slides away and fades out, then [end] runs (exactly once, always). */
    fun screenOut(v: View, forward: Boolean, end: () -> Unit) {
        v.animate().cancel()
        if (!on(v.context)) {
            end()
            return
        }
        var called = false
        val once = Runnable { if (!called) { called = true; end() } }
        v.animate().alpha(0f).translationX(dpf(v, if (forward) -24f else 24f))
            .setStartDelay(0)
            .setDuration(Theme.DUR)
            .setInterpolator(Theme.EASE_IN)
            .withEndAction(once)
            .start()
    }
}
