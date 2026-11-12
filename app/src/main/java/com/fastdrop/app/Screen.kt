package com.fastdrop.app

import android.content.Context
import android.view.View

/**
 * One full-screen page inside MainActivity (Home, Send, Receive, Web share, Settings…).
 * MainActivity keeps a back stack of these and animates between them.
 *
 * Lifecycle: build() -> attached -> onShow() ... refresh() (on Hub changes, and every 400 ms
 * when [wantsTick]) ... onHide() -> detached. build() can run again after a configuration
 * change, so keep state in fields, not only in views.
 */
abstract class Screen(val host: MainActivity) {
    val ctx: Context get() = host

    /** Creates this screen's view tree. Must handle window insets (edge-to-edge on Android 15). */
    abstract fun build(): View

    /** Became the visible screen (also after returning from a screen pushed on top). */
    open fun onShow() {}

    /** Stopped being visible (popped, covered by another screen, or the activity stopped). */
    open fun onHide() {}

    /** Called on the main thread when Hub state changes, and every 400 ms if [wantsTick]. */
    open fun refresh() {}

    /** Return true if the screen consumed Back (e.g. closed an inner panel). */
    open fun onBack(): Boolean = false

    /** Gets focus first on TV (D-pad). */
    open fun firstFocus(): View? = null

    open val wantsTick: Boolean get() = false

    /** True while this screen hosts a link that dies if the display sleeps (Receive, QR, web share). */
    open val keepsScreenOn: Boolean get() = false
}
