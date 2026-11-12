package com.fastdrop.app

import android.animation.TimeInterpolator
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.provider.Settings
import android.view.animation.OvershootInterpolator
import android.view.animation.PathInterpolator

/**
 * Design tokens shared by every screen. Dark "ink" surfaces with violet/blue (send) and
 * teal/cyan (receive) gradients. Values here are the single source of truth: components in
 * Ui.kt and the screens read them, never hard-code their own colors.
 */
object Theme {
    // ---- surfaces (deep ink with a faint blue tint) ----
    const val BG = 0xFF07080D.toInt()
    const val BG_2 = 0xFF0B0D15.toInt()
    const val SURFACE = 0xFF11131C.toInt()
    const val SURFACE_2 = 0xFF181B27.toInt()
    const val SURFACE_3 = 0xFF222636.toInt()
    const val STROKE = 0x1FFFFFFF            // 12% white hairline
    const val STROKE_STRONG = 0x38FFFFFF     // 22% white
    const val SCRIM = 0xB3000000.toInt()     // 70% black behind sheets

    // ---- text ----
    const val TEXT = 0xFFF3F5FA.toInt()
    const val MUTED = 0xFF8E95AB.toInt()
    const val FAINT = 0xFF8089A0.toInt()

    // ---- brand + flows ----
    const val ACCENT = 0xFF7C6CFF.toInt()     // violet
    const val ACCENT_2 = 0xFF4FA3FF.toInt()   // blue
    const val SEND_A = 0xFF8B5CF6.toInt()
    const val SEND_B = 0xFF3B82F6.toInt()
    const val RECV_A = 0xFF10D9A0.toInt()
    const val RECV_B = 0xFF0EA5E9.toInt()
    const val WEB_A = 0xFFF59E0B.toInt()
    const val WEB_B = 0xFFEF4466.toInt()

    // ---- status ----
    const val SUCCESS = 0xFF34D399.toInt()
    const val DANGER = 0xFFFF5C7A.toInt()
    const val WARNING = 0xFFFFB547.toInt()

    // ---- content categories ----
    const val CAT_APPS = 0xFF22C55E.toInt()
    const val CAT_PHOTOS = 0xFFF472B6.toInt()
    const val CAT_VIDEOS = 0xFFFB923C.toInt()
    const val CAT_MUSIC = 0xFFA78BFA.toInt()
    const val CAT_FILES = 0xFF38BDF8.toInt()

    val BRAND = intArrayOf(ACCENT, ACCENT_2)
    val SEND = intArrayOf(SEND_A, SEND_B)
    val RECEIVE = intArrayOf(RECV_A, RECV_B)
    val WEB = intArrayOf(WEB_A, WEB_B)

    // ---- shape (dp) ----
    const val RADIUS_CARD = 24f
    const val RADIUS_BUTTON = 18f
    const val RADIUS_SMALL = 12f
    const val GUTTER = 20               // screen side padding
    const val TV_GUTTER = 48            // TV overscan-safe side padding

    // ---- motion ----
    const val DUR_FAST = 160L
    const val DUR = 260L
    const val DUR_SLOW = 420L
    val EASE_OUT: TimeInterpolator = PathInterpolator(0.2f, 0f, 0f, 1f)
    val EASE_IN_OUT: TimeInterpolator = PathInterpolator(0.4f, 0f, 0.2f, 1f)
    val EASE_IN: TimeInterpolator = PathInterpolator(0.4f, 0f, 1f, 1f)
    val SPRING: TimeInterpolator = OvershootInterpolator(1.3f)

    // ---- type ----
    val REGULAR: Typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    val MEDIUM: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    val BOLD: Typeface = Typeface.create("sans-serif", Typeface.BOLD)
    val BLACK: Typeface = Typeface.create("sans-serif-black", Typeface.NORMAL)

    fun dp(ctx: Context, v: Float): Int = (v * ctx.resources.displayMetrics.density + 0.5f).toInt()
    fun dp(ctx: Context, v: Int): Int = dp(ctx, v.toFloat())

    /** False when the user turned animations off (Developer options / accessibility). */
    fun animationsOn(ctx: Context): Boolean = try {
        Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
    } catch (_: Exception) { true }

    fun alpha(color: Int, a: Float): Int =
        Color.argb((a.coerceIn(0f, 1f) * 255).toInt(), Color.red(color), Color.green(color), Color.blue(color))
}
