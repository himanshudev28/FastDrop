package com.fastdrop.app

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import java.io.File

/**
 * Built-in storage browser for TVs without a working system file picker (docs/DESIGN.md 5.5).
 * Fully D-pad navigable; also works by touch. Returns selected absolute paths (files and/or whole
 * folders) in the "paths" string-array-list extra. Rows are the Signal list rows with a type plate
 * (photos yellow, videos ultramarine, music pink, apps green, files and folders neutral); a selected
 * row fills orange, the same as a selected port. A docked bar shows the count and the Add button.
 */
class BrowserActivity : Activity() {

    private class Entry(val file: File?, val name: String, val isDir: Boolean, val sub: String)

    private class Row(
        val root: LinearLayout, val plate: FrameLayout, val icon: ImageView, val title: TextView,
        val sub: TextView, val mark: ImageView,
    )

    private val selected = LinkedHashSet<String>()
    private var entries: List<Entry> = emptyList()
    private var current: File? = null          // null = roots screen
    private var roots: List<Entry> = emptyList()
    private var loadId = 0

    private lateinit var rootView: LinearLayout
    private lateinit var pathText: TextView
    private lateinit var emptyText: TextView
    private lateinit var listView: ListView
    private lateinit var folderBtn: TextView
    private lateinit var clearBtn: TextView
    private lateinit var countText: TextView
    private lateinit var addBtn: TextView
    private val adapter = RowAdapter()
    private val send = Job.SEND

    private fun dp(v: Int) = Theme.dp(this, v)
    private val gut get() = Ui.gutter(this)
    private val tvMode get() = Ui.tv(this)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Theme.resolve(this)
        edgeToEdge()
        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT, OnBackInvokedCallback { goUp() })
        }
        setContentView(buildUi())
        Theme.applyWindow(this)
        showRoots(null)
    }

    private fun edgeToEdge() {
        val w = window
        w.setBackgroundDrawable(ColorDrawable(Theme.BG))
        w.statusBarColor = Color.TRANSPARENT
        w.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= 29) {
            w.isNavigationBarContrastEnforced = false
            w.isStatusBarContrastEnforced = false
        }
        if (Build.VERSION.SDK_INT >= 30) {
            w.setDecorFitsSystemWindows(false)
        } else {
            @Suppress("DEPRECATION")
            w.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() = goUp()

    // TV remote shortcut: jump straight to adding from a long list.
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
        val top = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        if (tvMode) top.setPadding(0, dp(Theme.TV_SAFE_V), 0, 0)
        Ui.applyInsets(top, top = true, bottom = false)

        top.addView(Ui.header(this, getString(R.string.brw_title), { goUp() }), LinearLayout.LayoutParams(-1, -2))

        // Breadcrumb: the current path in mono, with the type plate for a folder
        val pathRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(gut, dp(10), gut, dp(10))
        }
        val fp = Theme.plateFor("files")
        pathRow.addView(Ui.plate(this, R.drawable.ic_folder, fp.fill, fp.on, 32),
            LinearLayout.LayoutParams(dp(32), dp(32)).apply { marginEnd = dp(12) })
        pathText = Ui.mono(this, getString(R.string.brw_storage)).apply {
            ellipsize = TextUtils.TruncateAt.START
            setSingleLine(true)
            if (tvMode) textSize = 14f
        }
        pathRow.addView(pathText, LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(Ui.rule(this))
        top.addView(pathRow, LinearLayout.LayoutParams(-1, -2))

        // Tools
        val tools = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(gut, 0, gut, dp(8))
        }
        folderBtn = Ui.secondaryButton(this, getString(R.string.brw_select_folder), R.drawable.ic_folder, small = !tvMode) { toggleCurrentFolder() }
        clearBtn = Ui.quietButton(this, getString(R.string.brw_clear)) {
            selected.clear(); refreshState(); adapter.notifyDataSetChanged()
        }.apply { visibility = View.GONE }
        tools.addView(folderBtn, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) })
        tools.addView(clearBtn, LinearLayout.LayoutParams(-2, -2))
        top.addView(tools, LinearLayout.LayoutParams(-1, -2))
        top.addView(Ui.text(this, getString(R.string.brw_hint), Type.META).apply { setPadding(gut, 0, gut, dp(10)) },
            LinearLayout.LayoutParams(-1, -2))
        top.addView(Ui.rule(this))

        val frame = FrameLayout(this)
        listView = ListView(this).apply {
            divider = ColorDrawable(Theme.LINE)
            dividerHeight = Theme.rule(this@BrowserActivity)
            itemsCanFocus = true
            selector = ColorDrawable(0)
            cacheColorHint = Color.TRANSPARENT
            overScrollMode = View.OVER_SCROLL_NEVER
            isVerticalScrollBarEnabled = false
            adapter = this@BrowserActivity.adapter
        }
        emptyText = Ui.text(this, "", Type.BODY).apply {
            gravity = Gravity.CENTER
            setPadding(dp(32), dp(24), dp(32), dp(24))
            visibility = View.GONE
        }
        frame.addView(listView, FrameLayout.LayoutParams(-1, -1))
        frame.addView(emptyText, FrameLayout.LayoutParams(-1, -1))
        top.addView(frame, LinearLayout.LayoutParams(-1, 0, 1f))

        // Dock: count on the left, Add on the right
        val dock = Ui.dock(this)
        countText = Ui.text(this, "", Type.ROW)
        dock.addView(countText, LinearLayout.LayoutParams(0, -2, 1f))
        addBtn = Ui.jobButton(this, getString(R.string.brw_add), send, R.drawable.ic_check) { send() }
        dock.addView(addBtn, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(12) })

        rootView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Theme.BG)
        }
        rootView.addView(top, LinearLayout.LayoutParams(-1, 0, 1f))
        rootView.addView(dock, LinearLayout.LayoutParams(-1, -2))
        return rootView
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
        pathText.text = getString(R.string.brw_storage)
        showList(cameFrom?.absolutePath)
    }

    private fun open(dir: File, cameFrom: File? = null) {
        val id = ++loadId
        current = dir
        pathText.text = dir.absolutePath
        entries = emptyList()
        adapter.notifyDataSetChanged()
        showMessage(getString(R.string.brw_loading))
        refreshState()
        val folder = getString(R.string.brw_folder)
        Thread {
            val list = dir.listFiles()
            val result = list?.filter { !it.name.startsWith(".") }
                ?.sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() }))
                ?.map { Entry(it, it.name, it.isDirectory, if (it.isDirectory) folder else Io.bytes(it.length())) }
            runOnUiThread {
                if (id != loadId || isFinishing || isDestroyed) return@runOnUiThread
                if (result == null) {
                    showMessage(getString(R.string.brw_unreadable))
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
        refreshState()
    }

    private fun showList(focusPath: String?) {
        if (entries.isEmpty()) {
            showMessage(getString(if (current == null) R.string.brw_no_storage else R.string.brw_empty))
        } else {
            emptyText.visibility = View.GONE
            listView.visibility = View.VISIBLE
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
        add(Environment.getExternalStorageDirectory(), getString(R.string.brw_internal))
        File("/storage").listFiles()?.sortedBy { it.name }?.forEach {
            if (it.name != "emulated" && it.name != "self") add(it, getString(R.string.brw_usb, it.name))
        }
        if (Build.VERSION.SDK_INT <= 29) {
            System.getenv("SECONDARY_STORAGE")?.split(':')?.filter { it.isNotBlank() }?.forEach {
                val f = File(it); add(f, getString(R.string.brw_usb, f.name))
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
        Ui.haptic(listView, Haptic.TICK)
        adapter.notifyDataSetChanged()
        refreshState()
    }

    private fun refreshState() {
        val n = selected.size
        countText.text = if (n == 0) getString(R.string.brw_none) else resources.getQuantityString(R.plurals.brw_selected, n, n)
        addBtn.isEnabled = n > 0
        clearBtn.visibility = if (n > 0) View.VISIBLE else View.GONE
        val cur = current
        folderBtn.isEnabled = cur != null
        folderBtn.alpha = if (cur != null) 1f else 0.4f
        folderBtn.text = getString(if (cur != null && cur.absolutePath in selected) R.string.brw_folder_selected else R.string.brw_select_folder)
    }

    private fun send() {
        if (selected.isEmpty()) return
        setResult(RESULT_OK, Intent().putStringArrayListExtra("paths", ArrayList(selected)))
        finish()
    }

    // ---------- Adapter ----------

    private fun newRow(): Row {
        val tvM = tvMode
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(if (tvM) 64 else 56)
            setPadding(gut, dp(8), gut, dp(8))
        }
        val ps = if (tvM) 48 else 40
        val plate = FrameLayout(this).apply { isDuplicateParentStateEnabled = true }
        val icon = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            isDuplicateParentStateEnabled = true
        }
        plate.addView(icon, FrameLayout.LayoutParams(dp(if (tvM) 24 else 20), dp(if (tvM) 24 else 20), Gravity.CENTER))
        root.addView(plate, LinearLayout.LayoutParams(dp(ps), dp(ps)).apply { marginEnd = dp(if (tvM) 20 else 16) })
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; isDuplicateParentStateEnabled = true }
        val title = Ui.text(this, "", Type.ROW).apply {
            setSingleLine(true); ellipsize = TextUtils.TruncateAt.MIDDLE
            setTextColor(Ui.states(Theme.INK, send.on, true)); isDuplicateParentStateEnabled = true
        }
        val sub = Ui.text(this, "", Type.META).apply {
            setSingleLine(true); ellipsize = TextUtils.TruncateAt.START
            setTextColor(Ui.states(Theme.INK_2, send.on, true)); isDuplicateParentStateEnabled = true
        }
        col.addView(title)
        col.addView(sub, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(2) })
        root.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        val mark = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            val pad = dp(12)
            setPadding(pad, pad, pad, pad)
            imageTintList = Ui.states(Theme.INK_2, send.on, true)
            isDuplicateParentStateEnabled = true
        }
        root.addView(mark, LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginStart = dp(8) })
        Ui.focusFill(root, send, 1.03f, 0f, null)
        return Row(root, plate, icon, title, sub, mark).also { root.tag = it }
    }

    /** Which identity plate a file gets (docs/DESIGN.md 2.3). */
    private fun kindOf(name: String, isDir: Boolean): String {
        if (isDir) return "files"
        return when (Thumbs.iconFor(name)) {
            R.drawable.ic_photo -> "photos"
            R.drawable.ic_video -> "videos"
            R.drawable.ic_music -> "music"
            R.drawable.ic_apps -> "apps"
            else -> "files"
        }
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
            val p = Theme.plateFor(kindOf(e.name, e.isDir))
            val rad = Theme.dp(this@BrowserActivity, Theme.R_CTRL.toInt()).toFloat()
            r.plate.background = StateListDrawable().apply {
                // Plate inverts when the row is filled: ON colour plate, fill colour glyph.
                for (st in intArrayOf(android.R.attr.state_pressed, android.R.attr.state_focused, android.R.attr.state_activated)) {
                    addState(intArrayOf(st), GradientDrawable().apply { setColor(send.on); cornerRadius = rad })
                }
                addState(intArrayOf(), GradientDrawable().apply { setColor(p.fill); cornerRadius = rad })
            }
            r.icon.setImageResource(if (e.isDir) R.drawable.ic_folder else Thumbs.iconFor(e.name))
            r.icon.imageTintList = Ui.states(p.on, send.fill, true)
            r.title.text = e.name
            r.sub.text = e.sub
            r.root.isActivated = isSel
            r.root.setOnClickListener { Ui.haptic(it); onClick(e) }
            r.root.setOnLongClickListener { onLongClick(e) }

            // Folders (not the storage roots) can be ticked without opening them; files tick by row.
            val canTickFolder = f != null && e.isDir && current != null
            when {
                isSel -> {
                    r.mark.visibility = View.VISIBLE
                    r.mark.setImageResource(R.drawable.ic_check)
                    r.mark.contentDescription = getString(R.string.brw_cd_selected)
                }
                canTickFolder -> {
                    r.mark.visibility = View.VISIBLE
                    r.mark.setImageResource(R.drawable.ic_plus)
                    r.mark.contentDescription = getString(R.string.brw_cd_select_folder, e.name)
                }
                else -> r.mark.visibility = View.INVISIBLE
            }
            if (canTickFolder) {
                r.mark.setOnClickListener { f?.let { toggle(it) } }
                r.mark.isClickable = true
            } else {
                r.mark.setOnClickListener(null)
                r.mark.isClickable = false
            }
            r.root.layoutParams = AbsListView.LayoutParams(-1, -2)
            return r.root
        }
    }
}
