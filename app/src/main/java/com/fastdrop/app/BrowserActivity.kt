package com.fastdrop.app

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import java.io.File

/**
 * Built-in storage browser for TVs without a working system file picker.
 * Fully D-pad navigable; also works by touch. Returns selected absolute paths
 * (files and/or whole folders) in the "paths" string-array-list extra.
 */
class BrowserActivity : Activity() {

    private class Entry(val file: File?, val name: String, val isDir: Boolean, val sub: String)

    private class Row(
        val root: LinearLayout, val badge: FrameLayout, val icon: ImageView, val title: TextView,
        val sub: TextView, val check: PickUi.Check,
    )

    private val selected = LinkedHashSet<String>()
    private var entries: List<Entry> = emptyList()
    private var current: File? = null          // null = roots screen
    private var roots: List<Entry> = emptyList()
    private var loadId = 0
    private var bottomInset = 0

    private lateinit var rootView: FrameLayout
    private lateinit var pathText: TextView
    private lateinit var emptyText: TextView
    private lateinit var listView: ListView
    private lateinit var folderBtn: TextView
    private lateinit var clearBtn: TextView
    private lateinit var bar: PickUi.FloatingBar
    private val adapter = RowAdapter()

    private fun dp(v: Int) = Theme.dp(this, v)
    private val gut get() = dp(if (Ui.tv(this)) Theme.TV_GUTTER else Theme.GUTTER)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PickUi.edgeToEdge(this)
        PickUi.registerBack(this) { goUp() }
        setContentView(buildUi())
        showRoots(null)
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() = goUp()

    // TV remote shortcut: jump straight to the action bar from a long list.
    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent): Boolean {
        if (event.repeatCount == 0 && (keyCode == android.view.KeyEvent.KEYCODE_MENU ||
                keyCode == android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE ||
                keyCode == android.view.KeyEvent.KEYCODE_BUTTON_START)) {
            send()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    // ---------- UI ----------

    private fun buildUi(): View {
        rootView = FrameLayout(this).apply {
            setBackgroundColor(Theme.BG)
            setOnApplyWindowInsetsListener { _, ins ->
                val b = Ui.insetsOf(ins)[3]
                if (b != bottomInset) {
                    bottomInset = b
                    listView.setPadding(gut, dp(4), gut, dp(110) + b)
                }
                ins
            }
        }
        rootView.addView(Backdrop(this), FrameLayout.LayoutParams(-1, -1))

        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        Ui.applyInsets(column, top = true, bottom = false)

        val header = Ui.header(this, "Browse storage", { goUp() })
        column.addView(header, LinearLayout.LayoutParams(-1, -2).apply { leftMargin = gut; rightMargin = gut })

        // Path line
        val pathRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = PickUi.rounded(this@BrowserActivity, Theme.alpha(Theme.SURFACE_2, 0.9f), 16, Theme.STROKE)
        }
        pathRow.addView(Ui.icon(this, R.drawable.ic_folder, Theme.WARNING, 18),
            LinearLayout.LayoutParams(dp(18), dp(18)).apply { rightMargin = dp(10) })
        pathText = Ui.text(this, "Storage", 13.5f, Theme.MUTED, Theme.MEDIUM).apply {
            ellipsize = TextUtils.TruncateAt.START
            setSingleLine(true)
        }
        pathRow.addView(pathText, LinearLayout.LayoutParams(0, -2, 1f))
        column.addView(pathRow, LinearLayout.LayoutParams(-1, -2).apply {
            leftMargin = gut; rightMargin = gut; bottomMargin = dp(10)
        })

        // Tools
        val tools = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        folderBtn = Ui.secondaryButton(this, "Select this folder", R.drawable.ic_folder) { toggleCurrentFolder() }
            .apply { compact(this) }
        clearBtn = Ui.secondaryButton(this, "Clear", R.drawable.ic_close) {
            selected.clear(); refreshState(); adapter.notifyDataSetChanged()
        }.apply { compact(this); visibility = View.GONE }
        tools.addView(folderBtn, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(10) })
        tools.addView(clearBtn, LinearLayout.LayoutParams(-2, -2))
        column.addView(tools, LinearLayout.LayoutParams(-1, -2).apply { leftMargin = gut; rightMargin = gut })

        column.addView(Ui.text(this, "Tap a file to select it. Press and hold a folder, or use \"Select this folder\", to pick the whole folder.",
            12.5f, Theme.FAINT).apply { setPadding(dp(4), 0, dp(4), 0) },
            LinearLayout.LayoutParams(-1, -2).apply { leftMargin = gut; rightMargin = gut; topMargin = dp(10); bottomMargin = dp(8) })

        val frame = FrameLayout(this)
        listView = ListView(this).apply {
            divider = ColorDrawable(Color.TRANSPARENT)
            dividerHeight = dp(8)
            itemsCanFocus = false
            selector = PickUi.selector(this@BrowserActivity, 16)
            setDrawSelectorOnTop(true)
            cacheColorHint = Color.TRANSPARENT
            clipToPadding = false
            overScrollMode = View.OVER_SCROLL_NEVER
            isVerticalScrollBarEnabled = false
            setPadding(gut, dp(4), gut, dp(110) + bottomInset)
            adapter = this@BrowserActivity.adapter
            setOnItemClickListener { _, _, pos, _ -> entries.getOrNull(pos)?.let { onClick(it) } }
            setOnItemLongClickListener { _, _, pos, _ -> entries.getOrNull(pos)?.let { onLongClick(it) } ?: false }
        }
        emptyText = Ui.text(this, "", 16f, Theme.MUTED).apply {
            gravity = Gravity.CENTER
            setPadding(dp(32), dp(24), dp(32), dp(80))
            visibility = View.GONE
        }
        frame.addView(listView, FrameLayout.LayoutParams(-1, -1))
        frame.addView(emptyText, FrameLayout.LayoutParams(-1, -1))
        column.addView(frame, LinearLayout.LayoutParams(-1, 0, 1f))
        rootView.addView(column, FrameLayout.LayoutParams(-1, -1))

        bar = PickUi.FloatingBar(this, "Add") { send() }
        rootView.addView(bar, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))

        if (Anim.on(this)) Anim.stagger(listOf(header, pathRow, tools), 0, 50)
        return rootView
    }

    private fun compact(b: TextView) {
        b.minHeight = dp(44)
        b.setPadding(dp(16), dp(8), dp(16), dp(8))
        b.textSize = 14f
    }

    // ---------- Navigation ----------

    private fun goUp() {
        val cur = current
        if (cur == null) {
            setResult(RESULT_CANCELED); finish(); return
        }
        val isRoot = roots.any { it.file?.absolutePath == cur.absolutePath }
        val parent = cur.parentFile
        if (isRoot || parent == null) showRoots(cur) else open(parent, cur)
    }

    private fun showRoots(cameFrom: File?) {
        loadId++
        current = null
        roots = findRoots()
        entries = roots
        pathText.text = "Storage"
        showList(cameFrom?.absolutePath)
    }

    private fun open(dir: File, cameFrom: File? = null) {
        val id = ++loadId
        current = dir
        pathText.text = dir.absolutePath
        entries = emptyList()
        adapter.notifyDataSetChanged()
        showMessage("Loading…")
        refreshState()
        Thread {
            val list = dir.listFiles()
            val result = list?.filter { !it.name.startsWith(".") }
                ?.sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() }))
                ?.map { Entry(it, it.name, it.isDirectory, if (it.isDirectory) "Folder" else Io.bytes(it.length())) }
            runOnUiThread {
                if (id != loadId || isFinishing || isDestroyed) return@runOnUiThread
                if (result == null) {
                    showMessage("Can't read this folder. Allow storage access in Settings, then Apps, then FastDrop, then Permissions.")
                } else {
                    entries = result
                    showList(cameFrom?.absolutePath)
                }
            }
        }.start()
    }

    private fun showMessage(msg: String) {
        emptyText.text = msg
        emptyText.visibility = View.VISIBLE
        listView.visibility = View.INVISIBLE
        if (Anim.on(this)) Anim.enter(emptyText)
        refreshState()
    }

    private fun showList(focusPath: String?) {
        if (entries.isEmpty()) {
            showMessage(if (current == null) "No storage found." else "This folder is empty.")
        } else {
            emptyText.visibility = View.GONE
            listView.visibility = View.VISIBLE
            if (Anim.on(this)) Anim.enter(listView)
        }
        adapter.notifyDataSetChanged()
        refreshState()
        val idx = entries.indexOfFirst { it.file?.absolutePath == focusPath }.coerceAtLeast(0)
        listView.post {
            listView.setSelection(idx)
            if (entries.isNotEmpty()) listView.requestFocus() else folderBtn.requestFocus()
        }
    }

    private fun findRoots(): List<Entry> {
        val out = ArrayList<Entry>()
        val seen = HashSet<String>()
        fun add(f: File, label: String) {
            if (f.isDirectory && f.canRead() && seen.add(f.absolutePath))
                out.add(Entry(f, label, true, f.absolutePath))
        }
        @Suppress("DEPRECATION")
        add(Environment.getExternalStorageDirectory(), "Internal storage")
        File("/storage").listFiles()?.sortedBy { it.name }?.forEach {
            if (it.name != "emulated" && it.name != "self") add(it, "USB / SD card (${it.name})")
        }
        if (Build.VERSION.SDK_INT <= 29) {
            System.getenv("SECONDARY_STORAGE")?.split(':')?.filter { it.isNotBlank() }?.forEach {
                val f = File(it); add(f, "USB / SD card (${f.name})")
            }
        }
        return out
    }

    // ---------- Selection ----------

    private fun onClick(e: Entry) {
        val f = e.file ?: return
        if (e.isDir) open(f) else toggle(f)
    }

    private fun onLongClick(e: Entry): Boolean {
        val f = e.file ?: return false
        if (!e.isDir || current == null) return false
        toggle(f)
        return true
    }

    private fun toggleCurrentFolder() {
        current?.let { toggle(it) }
    }

    private fun toggle(f: File) {
        val p = f.absolutePath
        if (!selected.remove(p)) selected.add(p)
        adapter.notifyDataSetChanged()
        refreshState()
    }

    private fun refreshState() {
        val n = selected.size
        bar.update(n, "", if (n == 1) "item" else "items")
        clearBtn.visibility = if (n > 0) View.VISIBLE else View.GONE
        val cur = current
        folderBtn.isEnabled = cur != null
        folderBtn.alpha = if (cur != null) 1f else 0.4f
        folderBtn.text = if (cur != null && cur.absolutePath in selected) "Folder selected" else "Select this folder"
    }

    private fun send() {
        if (selected.isEmpty()) return
        setResult(RESULT_OK, Intent().putStringArrayListExtra("paths", ArrayList(selected)))
        finish()
    }

    // ---------- Adapter ----------

    private fun newRow(): Row {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(if (Ui.tv(this@BrowserActivity)) 72 else 64)
            setPadding(dp(10), dp(8), dp(14), dp(8))
        }
        val badge = FrameLayout(this)
        val icon = Ui.icon(this, R.drawable.ic_file, Theme.CAT_FILES, 22)
        badge.addView(icon, FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
        root.addView(badge, LinearLayout.LayoutParams(dp(44), dp(44)).apply { rightMargin = dp(14) })
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val title = Ui.text(this, "", 15.5f, Theme.TEXT, Theme.MEDIUM).apply {
            setSingleLine(true); ellipsize = TextUtils.TruncateAt.MIDDLE
        }
        val sub = Ui.text(this, "", 12.5f, Theme.MUTED).apply {
            setSingleLine(true); ellipsize = TextUtils.TruncateAt.START
        }
        col.addView(title)
        col.addView(sub, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(3) })
        root.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        val check = PickUi.Check(this, 26, false)
        root.addView(check, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(12) })
        return Row(root, badge, icon, title, sub, check).also { root.tag = it }
    }

    private inner class RowAdapter : BaseAdapter() {
        override fun getCount() = entries.size
        override fun getItem(position: Int) = entries[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val r = (convertView?.tag as? Row) ?: newRow()
            val e = entries[position]
            val f = e.file
            val isSel = f != null && f.absolutePath in selected
            val tint = if (e.isDir) Theme.WARNING else Thumbs.tintFor(e.name)
            r.icon.setImageResource(if (e.isDir) R.drawable.ic_folder else Thumbs.iconFor(e.name))
            r.icon.setColorFilter(tint, android.graphics.PorterDuff.Mode.SRC_IN)
            r.badge.background = PickUi.rounded(this@BrowserActivity, Theme.alpha(tint, 0.15f), 13)
            r.title.text = e.name
            r.title.setTextColor(if (isSel) Theme.ACCENT else Theme.TEXT)
            r.sub.text = e.sub
            r.root.background = if (isSel)
                PickUi.rounded(this@BrowserActivity, Theme.alpha(Theme.ACCENT, 0.14f), 16, Theme.alpha(Theme.ACCENT, 0.55f))
            else PickUi.rounded(this@BrowserActivity, Theme.alpha(Theme.SURFACE, 0.75f), 16, Theme.STROKE)
            r.check.set(isSel, false)
            // Touch users can tick a folder without opening it.
            if (f != null && e.isDir && current != null) {
                r.check.visibility = View.VISIBLE
                r.check.setOnClickListener {
                    Ui.haptic(it)
                    val p = f.absolutePath
                    if (!selected.remove(p)) selected.add(p)
                    r.check.set(p in selected, true)
                    r.root.background = if (p in selected)
                        PickUi.rounded(this@BrowserActivity, Theme.alpha(Theme.ACCENT, 0.14f), 16, Theme.alpha(Theme.ACCENT, 0.55f))
                    else PickUi.rounded(this@BrowserActivity, Theme.alpha(Theme.SURFACE, 0.75f), 16, Theme.STROKE)
                    r.title.setTextColor(if (p in selected) Theme.ACCENT else Theme.TEXT)
                    refreshState()
                }
            } else {
                r.check.setOnClickListener(null)
                r.check.isClickable = false
                // Roots can't be selected; files tick through the row click.
                r.check.visibility = if (e.isDir) View.GONE else View.VISIBLE
            }
            r.root.layoutParams = AbsListView.LayoutParams(-1, -2)
            return r.root
        }
    }
}
