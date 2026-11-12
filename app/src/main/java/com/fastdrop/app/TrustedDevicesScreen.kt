package com.fastdrop.app

import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView

/** Settings > Trusted devices (docs 4.8): the list from [Trust.list], each with Remove (confirmed) and an empty state. */
class TrustedDevicesScreen(host: MainActivity) : Screen(host) {

    private lateinit var box: LinearLayout
    private var sig: String? = null

    private fun dp(v: Int) = Theme.dp(ctx, v)
    private fun s(id: Int, vararg a: Any): String = ctx.getString(id, *a)

    override fun build(): View {
        val c = ctx
        sig = null
        box = Ui.column(c)
        val page = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            addView(bigHeader(host, s(R.string.trust_title)))
        }
        val scroll = ScrollView(c).apply {
            overScrollMode = View.OVER_SCROLL_NEVER
            isVerticalScrollBarEnabled = false
            clipToPadding = false
            setPadding(0, 0, 0, dp(24))
            addView(box, android.widget.FrameLayout.LayoutParams(-1, -2))
        }
        Ui.applyInsets(scroll, false, true)
        if (Ui.tv(c)) {
            // A single centred 600 dp column, like Settings.
            val wrap = LinearLayout(c).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(0, dp(Theme.TV_SAFE_V), 0, dp(Theme.TV_SAFE_V))
            }
            page.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
            wrap.addView(page, LinearLayout.LayoutParams(dp(600), -1))
            render()
            return wrap
        }
        page.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        Ui.applyInsets(page, true, false)
        render()
        return page
    }

    private fun render() {
        if (!::box.isInitialized) return
        val c = ctx
        val list = Trust.list()
        val newSig = list.joinToString("|") { it.dev + it.name }
        if (newSig == sig) return
        sig = newSig
        box.removeAllViews()
        if (list.isEmpty()) {
            val p = Theme.plateFor("files")
            box.addView(LinearLayout(c).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                val g = Ui.gutter(c)
                setPadding(g, dp(48), g, dp(24))
                addView(Ui.plate(c, R.drawable.ic_shield, p.fill, p.on, 56), LinearLayout.LayoutParams(dp(56), dp(56)))
                addView(Ui.text(c, s(R.string.trust_empty_title), Type.TITLE).apply { gravity = Gravity.CENTER; setPadding(0, dp(16), 0, 0) },
                    LinearLayout.LayoutParams(-1, -2))
                addView(Ui.text(c, s(R.string.trust_empty_body), Type.BODY, Theme.INK_2).apply { gravity = Gravity.CENTER; setPadding(0, dp(6), 0, 0) },
                    LinearLayout.LayoutParams(-1, -2))
            })
            return
        }
        box.addView(Ui.text(c, s(R.string.trust_intro), Type.BODY, Theme.INK_2).apply {
            setPadding(Ui.gutter(c), dp(4), Ui.gutter(c), dp(12))
        })
        val fmt = android.text.format.DateFormat.getMediumDateFormat(c)
        list.forEach { e ->
            box.addView(Ui.rule(c))
            val remove = Ui.secondaryButton(c, s(R.string.trust_remove), null, true) {
                confirmDanger(host, s(R.string.trust_confirm_title, e.name), s(R.string.trust_confirm_body),
                    s(R.string.trust_remove), s(R.string.trust_cancel)) {
                    Trust.remove(e.dev)
                    render()
                    Hub.changed()
                }
            }
            val p = Theme.plateFor("phone")
            box.addView(Ui.row(c, null, e.name, s(R.string.trust_added, fmt.format(java.util.Date(e.added))), remove,
                lead = Ui.plate(c, R.drawable.ic_shield, p.fill, p.on, if (Ui.tv(c)) 48 else 40)), LinearLayout.LayoutParams(-1, -2))
        }
        box.addView(Ui.rule(c))
    }

    override fun onShow() { sig = null; render() }
    override fun refresh() = render()
    override fun firstFocus(): View? =
        (0 until (if (::box.isInitialized) box.childCount else 0)).map { box.getChildAt(it) }.firstOrNull { it.isFocusable }
            ?: (box.parent?.parent as? View)
}
