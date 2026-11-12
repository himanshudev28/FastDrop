package com.fastdrop.app

import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * The picker's Text tab (docs 4.2): a multiline field on SURFACE_2 with a 4 dp pink block on its
 * leading edge, a Paste button, and a character counter. Text is sent on its own, never mixed
 * with files. [onChange] fires on every edit with the current text.
 */
internal class PickerTextPane(
    private val ctx: Context, initial: String, private val bottomInset: () -> Int,
    private val clipboard: () -> String?, private val toast: (String) -> Unit, private val onChange: (String) -> Unit,
) {
    val view: ScrollView
    private val field: EditText
    private val counter: TextView
    private val page = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

    init {
        val g = if (Ui.tv(ctx)) Theme.dp(ctx, 16) else Ui.gutter(ctx)
        page.addView(Ui.text(ctx, ctx.getString(R.string.pick_text_title), Type.TITLE),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = Theme.dp(ctx, 16) })
        page.addView(Ui.text(ctx, ctx.getString(R.string.pick_text_hint), Type.META),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = Theme.dp(ctx, 4); bottomMargin = Theme.dp(ctx, 16) })

        field = EditText(ctx).apply {
            setText(initial)
            setSelection(initial.length)
            hint = ctx.getString(R.string.pick_text_field_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 5
            maxLines = 12
            gravity = Gravity.TOP or Gravity.START
            setTextColor(Theme.INK)
            setHintTextColor(Theme.INK_3)
            textSize = if (Ui.tv(ctx)) Type.BODY.tvSp else Type.BODY.sp
            typeface = Theme.REGULAR
            includeFontPadding = false
            Ui.lineHeight(this, if (Ui.tv(ctx)) Type.BODY.tvLine else Type.BODY.line)
            setPadding(Theme.dp(ctx, 16), Theme.dp(ctx, 14), Theme.dp(ctx, 14), Theme.dp(ctx, 14))
            background = ColorDrawable(Theme.SURFACE_2)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) { updateCounter(); onChange(s?.toString() ?: "") }
            })
        }
        // 4 dp pink leading block: the job colour as a solid strip.
        val box = FrameLayout(ctx)
        box.addView(field, FrameLayout.LayoutParams(-1, -2))
        box.addView(View(ctx).apply { setBackgroundColor(Theme.C_TEXT) }, FrameLayout.LayoutParams(Theme.dp(ctx, 4), -1))
        page.addView(box, LinearLayout.LayoutParams(-1, -2))

        val meta = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        counter = Ui.mono(ctx, "")
        meta.addView(counter, LinearLayout.LayoutParams(0, -2, 1f))
        meta.addView(Ui.secondaryButton(ctx, ctx.getString(R.string.pick_paste), R.drawable.ic_copy, true) { paste() })
        page.addView(meta, LinearLayout.LayoutParams(-1, -2).apply { topMargin = Theme.dp(ctx, 12) })
        updateCounter()

        view = ScrollView(ctx).apply {
            overScrollMode = View.OVER_SCROLL_NEVER
            isVerticalScrollBarEnabled = false
            clipToPadding = false
            isFillViewport = true
            setPadding(g, 0, g, Theme.dp(ctx, 96) + bottomInset())
            addView(page, FrameLayout.LayoutParams(-1, -2))
        }
    }

    fun applyPadding() {
        val g = if (Ui.tv(ctx)) Theme.dp(ctx, 16) else Ui.gutter(ctx)
        view.setPadding(g, 0, g, Theme.dp(ctx, 96) + bottomInset())
    }

    private fun updateCounter() {
        counter.text = ctx.getString(R.string.pick_text_counter, field.text.length, ProtoV2.MAX_TEXT)
    }

    private fun paste() {
        val clip = clipboard()
        if (clip.isNullOrEmpty()) { toast(ctx.getString(R.string.pick_clip_empty)); return }
        field.setText(clip)
        field.setSelection(field.text.length)
    }

    fun focus() { field.requestFocus() }
}
