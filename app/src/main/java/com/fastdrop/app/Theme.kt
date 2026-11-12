package com.fastdrop.app

import android.animation.TimeInterpolator
import android.app.Activity
import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.provider.Settings
import android.view.View
import android.view.Window
import android.view.WindowInsetsController
import android.view.animation.PathInterpolator

/**
 * The six jobs of the "Signal" colour code (docs/DESIGN.md section 2.3). Fills are identical in
 * both themes, like painted keys on hardware. Components take a [Job], never a raw colour.
 *  - [fill]: the solid block colour. [on]: text and icons on that fill (pre-verified AA).
 *  - [shade]: unlit-dot shade, and the pressed fill.
 *  - [text]: the tone to use as coloured text on the page background (theme aware).
 */
enum class Job(val fill: Int, val on: Int, val shade: Int, private val textLight: Int, private val textDark: Int) {
    SEND(0xFFFF5A1F.toInt(), 0xFF161513.toInt(), 0xFFDA4F1D.toInt(), 0xFFC53D0B.toInt(), 0xFFFF5A1F.toInt()),
    RECEIVE(0xFF2E3BFF.toInt(), 0xFFFFFFFF.toInt(), 0xFF4F5AFF.toInt(), 0xFF2E3BFF.toInt(), 0xFF8E97FF.toInt()),
    WEB(0xFFFFC21A.toInt(), 0xFF161513.toInt(), 0xFFDAA619.toInt(), 0xFF161513.toInt(), 0xFFECEAE4.toInt()),
    TEXT(0xFFFF5FA2.toInt(), 0xFF161513.toInt(), 0xFFDA538B.toInt(), 0xFF161513.toInt(), 0xFFECEAE4.toInt()),
    OK(0xFF1FAE52.toInt(), 0xFF161513.toInt(), 0xFF1E9648.toInt(), 0xFF13753A.toInt(), 0xFF1FAE52.toInt()),
    DANGER(0xFFD7192F.toInt(), 0xFFFFFFFF.toInt(), 0xFFDD3E50.toInt(), 0xFFD7192F.toInt(), 0xFFFF6B6B.toInt());

    /** Coloured text on the page background. Yellow and pink are never text: they fall back to ink. */
    val text: Int get() = if (Theme.dark) textDark else textLight
}

/** A solid plate colour pair (fill + glyph colour) for a device type or content kind. */
class Plate(val fill: Int, val on: Int)

/**
 * Design tokens shared by every screen. Neutrals are theme-aware vars set by [resolve]; the six
 * job colours live in [Job]. Everything reads these, nothing hard-codes its own colour.
 */
object Theme {
    // ---- neutral body (set by resolve) ----
    @JvmField var dark = false
    @JvmField var BG = 0xFFF3F1EC.toInt()
    @JvmField var SURFACE = 0xFFFBFAF7.toInt()
    @JvmField var SURFACE_2 = 0xFFE7E4DD.toInt()
    @JvmField var INK = 0xFF161513.toInt()
    @JvmField var INK_2 = 0xFF5B5852.toInt()
    @JvmField var INK_3 = 0xFF8C887F.toInt()
    @JvmField var LINE = 0xFFD9D5CC.toInt()
    @JvmField var LINE_STRONG = 0xFF8C887F.toInt()
    @JvmField var SCRIM = 0x99000000.toInt()
    const val QR_PLATE = 0xFFFFFFFF.toInt()
    const val QR_INK = 0xFF000000.toInt()

    // ---- the six fills (same in both themes), kept as plain ints for convenience ----
    const val C_SEND = 0xFFFF5A1F.toInt()
    const val C_RECEIVE = 0xFF2E3BFF.toInt()
    const val C_WEB = 0xFFFFC21A.toInt()
    const val C_TEXT = 0xFFFF5FA2.toInt()
    const val C_OK = 0xFF1FAE52.toInt()
    const val C_DANGER = 0xFFD7192F.toInt()
    const val ON_DARK = 0xFF161513.toInt()      // near-black text on a fill
    const val ON_LIGHT = 0xFFFFFFFF.toInt()     // white text on a fill

    // ---- coloured text tones (theme aware) ----
    val TXT_SEND: Int get() = Job.SEND.text
    val TXT_RECEIVE: Int get() = Job.RECEIVE.text
    val TXT_OK: Int get() = Job.OK.text
    val TXT_DANGER: Int get() = Job.DANGER.text

    // ---- shape (dp) ----
    const val R_CTRL = 6f
    const val R_SHEET = 12f
    const val GUTTER = 16
    const val TV_GUTTER = 48
    const val TV_SAFE_V = 27
    const val S1 = 4
    const val S2 = 8
    const val S3 = 12
    const val S4 = 16
    const val S5 = 24
    const val S6 = 32
    const val S7 = 48

    // ---- motion ----
    const val D_TAP = 90L
    const val D_STATE = 150L
    const val D_SHEET = 220L
    const val D_SHEET_OUT = 180L
    val EASE_ENTER: TimeInterpolator = PathInterpolator(0f, 0f, 0f, 1f)
    val EASE_EXIT: TimeInterpolator = PathInterpolator(0.3f, 0f, 1f, 1f)
    val EASE_MOVE: TimeInterpolator = PathInterpolator(0.2f, 0f, 0f, 1f)

    // ---- type ----
    val REGULAR: Typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    val MEDIUM: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    val MONO: Typeface = Typeface.MONOSPACE

    // ---- temporary aliases so screens that are rebuilt in later stages still compile ----
    val TEXT: Int get() = INK
    val MUTED: Int get() = INK_2
    val FAINT: Int get() = INK_3
    val STROKE: Int get() = LINE
    val STROKE_STRONG: Int get() = LINE_STRONG
    val ACCENT: Int get() = Job.SEND.text
    val ACCENT_2: Int get() = Job.RECEIVE.text
    val SUCCESS: Int get() = Job.OK.text
    val DANGER: Int get() = Job.DANGER.text
    val WARNING: Int get() = INK_2
    val SEND_A = C_SEND
    val SEND_B = C_SEND
    val RECV_A = C_RECEIVE
    val RECV_B = C_RECEIVE
    val WEB_A = C_WEB
    val WEB_B = C_WEB
    val CAT_APPS = C_OK
    val CAT_PHOTOS = C_WEB
    val CAT_VIDEOS = C_RECEIVE
    val CAT_MUSIC = C_TEXT
    val CAT_FILES: Int get() = INK_2
    val SURFACE_3: Int get() = SURFACE_2
    val BOLD: Typeface = MEDIUM
    val BLACK: Typeface = MEDIUM
    const val DUR_FAST = D_STATE
    const val DUR = D_STATE
    const val DUR_SLOW = D_SHEET
    val EASE_OUT: TimeInterpolator = EASE_ENTER
    val EASE_IN: TimeInterpolator = EASE_EXIT
    val EASE_IN_OUT: TimeInterpolator = EASE_MOVE

    fun dp(ctx: Context, v: Float): Int = (v * ctx.resources.displayMetrics.density + 0.5f).toInt()
    fun dp(ctx: Context, v: Int): Int = dp(ctx, v.toFloat())

    /** Hairline rule thickness in px: 1 px up to xxhdpi, 2 px at xxxhdpi. */
    fun rule(ctx: Context): Int = maxOf(1, (ctx.resources.displayMetrics.density / 2f).toInt())

    /** False when the user turned animations off (Developer options / accessibility). */
    fun animationsOn(ctx: Context): Boolean = try {
        Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
    } catch (_: Exception) { true }

    /** Only for the scrim and for disabled (40 %) content. Never on a job colour. */
    fun alpha(color: Int, a: Float): Int =
        Color.argb((a.coerceIn(0f, 1f) * 255).toInt(), Color.red(color), Color.green(color), Color.blue(color))

    // ---------------------------------------------------------------- plates

    /** Solid plate colours for a device type or content kind (identity table, section 2.3). */
    fun plateFor(kind: String): Plate = when (kind.lowercase()) {
        "phone" -> Plate(C_RECEIVE, ON_LIGHT)
        "tv" -> Plate(C_WEB, ON_DARK)
        "laptop", "pc", "computer", "desktop", "mac" -> Plate(C_OK, ON_DARK)
        "browser", "photos", "photo", "image" -> Plate(C_WEB, ON_DARK)
        "videos", "video" -> Plate(C_RECEIVE, ON_LIGHT)
        "apps", "app" -> Plate(C_OK, ON_DARK)
        "music", "audio", "text", "link" -> Plate(C_TEXT, ON_DARK)
        else -> Plate(SURFACE_2, INK)          // files, folders, unknown
    }

    // ---------------------------------------------------------------- appearance

    const val APPEARANCE_SYSTEM = 0
    const val APPEARANCE_LIGHT = 1
    const val APPEARANCE_DARK = 2
    private const val PREFS = "fastdrop"
    private const val KEY_APPEARANCE = "appearance"

    /** Settings > Appearance: System default / Light / Dark. Ignored on TV (always dark). */
    fun appearance(ctx: Context): Int =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_APPEARANCE, APPEARANCE_SYSTEM).coerceIn(0, 2)

    fun setAppearance(ctx: Context, v: Int) {
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putInt(KEY_APPEARANCE, v.coerceIn(0, 2)).apply()
        resolve(ctx)
    }

    private fun tvDevice(ctx: Context): Boolean =
        (ctx.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager)?.currentModeType ==
            Configuration.UI_MODE_TYPE_TELEVISION ||
            ctx.packageManager.hasSystemFeature("android.software.leanback")

    /** Works out light or dark and sets every neutral. Call before building any view. */
    fun resolve(ctx: Context) {
        val d = when {
            tvDevice(ctx) -> true
            else -> when (appearance(ctx)) {
                APPEARANCE_LIGHT -> false
                APPEARANCE_DARK -> true
                // Below API 29 there is no system dark mode, so system default means Light.
                else -> Build.VERSION.SDK_INT >= 29 &&
                    (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            }
        }
        dark = d
        if (d) {
            BG = 0xFF0F0F0E.toInt(); SURFACE = 0xFF191917.toInt(); SURFACE_2 = 0xFF232321.toInt()
            INK = 0xFFECEAE4.toInt(); INK_2 = 0xFFA29F98.toInt(); INK_3 = 0xFF6F6C66.toInt()
            LINE = 0xFF2B2A27.toInt(); LINE_STRONG = 0xFF6F6C66.toInt(); SCRIM = 0xB3000000.toInt()
        } else {
            BG = 0xFFF3F1EC.toInt(); SURFACE = 0xFFFBFAF7.toInt(); SURFACE_2 = 0xFFE7E4DD.toInt()
            INK = 0xFF161513.toInt(); INK_2 = 0xFF5B5852.toInt(); INK_3 = 0xFF8C887F.toInt()
            LINE = 0xFFD9D5CC.toInt(); LINE_STRONG = 0xFF8C887F.toInt(); SCRIM = 0x99000000.toInt()
        }
    }

    /**
     * Window background plus the system bar icon colours for the resolved theme: dark icons on
     * the paper theme (API 23+ status bar, API 26+ navigation bar), light icons on off-black.
     */
    @Suppress("DEPRECATION")
    fun applyWindow(a: Activity) {
        val w: Window = a.window
        w.setBackgroundDrawable(ColorDrawable(BG))
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                val c = w.insetsController
                val mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                    WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
                c?.setSystemBarsAppearance(if (dark) 0 else mask, mask)
            } else {
                var f = w.decorView.systemUiVisibility
                if (Build.VERSION.SDK_INT >= 23) {
                    f = if (dark) f and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv() else f or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                }
                if (Build.VERSION.SDK_INT >= 26) {
                    f = if (dark) f and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv() else f or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
                }
                w.decorView.systemUiVisibility = f
            }
            // Below API 23 the status bar icons stay white: a scrim keeps them readable on paper.
            w.statusBarColor = if (Build.VERSION.SDK_INT < 23 && !dark) 0x99000000.toInt() else Color.TRANSPARENT
        } catch (_: Exception) { }
    }
}
