package com.jono.flick

import android.content.Context
import android.graphics.Rect
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SectionIndexer
import android.widget.TextView

/**
 * The one app picker used everywhere (empty spot in the menu, the settings editor, Map apps):
 * the last 3 apps you used, then a search box and every app A–Z in a grid like the system drawer.
 * Under each app: where it already sits in the menu.
 */
class AppPicker(
    ctx: Context,
    /** When set, the first tile is "New folder"; tapping it calls this. */
    private val onNewFolder: ((View) -> Unit)? = null,
    /** When set, a "Person" tile (call / text someone) follows; tapping it calls this. */
    private val onNewPerson: ((View) -> Unit)? = null,
    private val onPick: (Slot.App, View) -> Unit,
) : LinearLayout(ctx) {
    private val d = resources.displayMetrics.density
    private fun dp(v: Int) = (v * d).toInt()

    /** Menu used for the "where it sits" line under each app. */
    var menu: Slot.Folder = Store.menu(ctx)
        set(v) {
            field = v
            paths = v.appPaths()
            refresh()
        }

    private val all: List<Slot.App> = Store.launchableApps(ctx)
    private var shown = all
    private var paths = menu.appPaths()
    private var recent = listOf<Slot.App>()

    private val recentRow = LinearLayout(ctx).apply { orientation = HORIZONTAL }
    private val accessHint = TextView(ctx)
    val search = EditText(ctx)
    val grid = GridView(ctx)
    private val adapter = Adapter()

    init {
        orientation = VERTICAL
        addView(label("RECENT"))
        addView(recentRow, LayoutParams(-1, -2))
        accessHint.apply {
            text = "Show the apps you used last on your phone, not just in Flick: allow usage access ›"
            textSize = 12f
            setTextColor(ctx.getColor(R.color.accent_text))
            setPadding(dp(4), dp(2), dp(4), dp(6))
            setOnClickListener { RecentApps.openSettings(ctx) }
        }
        addView(accessHint, LayoutParams(-1, -2))
        search.apply {
            hint = "Search apps"
            isSingleLine = true
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(e: Editable?) {
                    val q = e.toString().trim().lowercase()
                    shown = if (q.isEmpty()) all else all.filter { q in it.label.lowercase() }
                    adapter.refresh()
                }
            })
        }
        addView(search, LayoutParams(-1, -2))
        addView(label("ALL APPS"))
        grid.apply {
            numColumns = GridView.AUTO_FIT
            columnWidth = dp(76)
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            verticalSpacing = dp(4)
            isFastScrollEnabled = true
            adapter = this@AppPicker.adapter
            setOnItemClickListener { _, v, pos, _ -> onPick(shown[pos], v) }
        }
        addView(grid, LayoutParams(-1, 0, 1f))
        refresh()
    }

    /** Reloads recents and the "where it sits" lines (call when coming back to the screen). */
    fun refresh() {
        recent = RecentApps.get(context, all)
        accessHint.visibility = if (RecentApps.hasAccess(context)) View.GONE else View.VISIBLE
        recentRow.removeAllViews()
        onNewFolder?.let { make ->
            val cell = makeCell(context)
            (cell.getChildAt(0) as ImageView).setImageResource(R.drawable.ic_new_folder)
            (cell.getChildAt(1) as TextView).text = "New folder"
            (cell.getChildAt(2) as TextView).text = ""
            cell.setOnClickListener { make(cell) }
            recentRow.addView(cell, LayoutParams(0, -2, 1f))
        }
        onNewPerson?.let { make ->
            val cell = makeCell(context)
            (cell.getChildAt(0) as ImageView).setImageResource(R.drawable.ic_person_add)
            (cell.getChildAt(1) as TextView).text = "Person"
            (cell.getChildAt(2) as TextView).text = "call · text"
            cell.setOnClickListener { make(cell) }
            recentRow.addView(cell, LayoutParams(0, -2, 1f))
        }
        recent.forEach { app ->
            val cell = makeCell(context)
            bindCell(cell, app)
            cell.setOnClickListener { onPick(app, cell) }
            recentRow.addView(cell, LayoutParams(0, -2, 1f))
        }
        // Keep the columns evenly spaced even with fewer recents.
        repeat(3 - recent.size) { recentRow.addView(View(context), LayoutParams(0, 1, 1f)) }
        adapter.refresh()
    }

    /**
     * The item under ([x], [y]) in this view's coordinates, and its cell; for hold-to-draw.
     * The "New folder" tile gives a new empty folder.
     */
    fun itemAt(x: Float, y: Float): Pair<Slot, View>? {
        val r = Rect()
        val folderTile = if (onNewFolder != null) 1 else 0
        val first = folderTile + if (onNewPerson != null) 1 else 0
        for (i in 0 until recentRow.childCount) {
            val c = recentRow.getChildAt(i)
            if (i >= first + recent.size) break
            c.getHitRect(r)
            r.offset(recentRow.left, recentRow.top)
            if (r.contains(x.toInt(), y.toInt())) {
                return when {
                    i < folderTile -> Slot.Folder("Folder") to c
                    i < first -> null // the person tile needs the contact picker first; tap it
                    else -> recent[i - first] to c
                }
            }
        }
        val pos = grid.pointToPosition((x - grid.left).toInt(), (y - grid.top).toInt())
        if (pos == GridView.INVALID_POSITION || pos >= shown.size) return null
        val v = grid.getChildAt(pos - grid.firstVisiblePosition) ?: return null
        return shown[pos] to v
    }

    private fun label(t: String) = TextView(context).apply {
        text = t
        textSize = 11f
        letterSpacing = 0.05f
        alpha = 0.7f
        setPadding(dp(4), dp(10), 0, dp(2))
    }

    private fun makeCell(ctx: Context) = LinearLayout(ctx).apply {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(dp(2), dp(8), dp(2), dp(6))
        isClickable = true
        background = ctx.getDrawable(android.R.drawable.list_selector_background)
        addView(ImageView(ctx), LayoutParams(dp(48), dp(48)))
        addView(TextView(ctx).apply {
            textSize = 12f
            gravity = Gravity.CENTER
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(4), 0, 0)
        }, LayoutParams(-1, -2))
        addView(TextView(ctx).apply {
            textSize = 10f
            gravity = Gravity.CENTER
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(ctx.getColor(R.color.accent_text))
        }, LayoutParams(-1, -2))
    }

    private fun bindCell(cell: LinearLayout, app: Slot.App) {
        (cell.getChildAt(0) as ImageView).setImageDrawable(Icons.appDrawable(context, app.pkg))
        (cell.getChildAt(1) as TextView).text = app.label
        val where = paths[app.pkg].orEmpty()
        (cell.getChildAt(2) as TextView).text = when (where.size) {
            0 -> ""
            1 -> menu.describe(where[0])
            else -> "${menu.describe(where[0])} +${where.size - 1}"
        }
    }

    private inner class Adapter : BaseAdapter(), SectionIndexer {
        private var sections = arrayOf<String>()
        private var sectionStart = intArrayOf()

        fun refresh() {
            val letters = shown.map { letter(it) }
            sections = letters.distinct().toTypedArray()
            sectionStart = IntArray(sections.size) { letters.indexOf(sections[it]) }
            notifyDataSetChanged()
        }

        private fun letter(a: Slot.App): String {
            val c = a.label.trim().firstOrNull()?.uppercaseChar() ?: '#'
            return if (c.isLetter()) c.toString() else "#"
        }

        override fun getCount() = shown.size
        override fun getItem(p: Int): Any = shown[p]
        override fun getItemId(p: Int) = p.toLong()
        override fun getSections(): Array<Any> = arrayOf<Any>(*sections)
        override fun getPositionForSection(s: Int) = sectionStart.getOrElse(s) { 0 }
        override fun getSectionForPosition(p: Int): Int {
            val l = shown.getOrNull(p)?.let { letter(it) } ?: return 0
            return sections.indexOf(l).coerceAtLeast(0)
        }

        override fun getView(p: Int, convert: View?, parent: ViewGroup?): View {
            val cell = (convert as? LinearLayout) ?: makeCell(context).apply {
                // Grid cells: the grid handles clicks.
                isClickable = false
                background = null
            }
            bindCell(cell, shown[p])
            return cell
        }
    }
}
