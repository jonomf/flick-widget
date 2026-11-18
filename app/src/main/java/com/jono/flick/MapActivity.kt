package com.jono.flick

import android.app.Activity
import android.content.Context
import android.graphics.Typeface
import android.os.Bundle
import android.os.SystemClock
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SectionIndexer
import android.widget.TextView
import kotlin.math.hypot

/**
 * App drawer for mapping apps: all apps A–Z like the system drawer. Hold an app, then draw the
 * gesture for where it should go (same strokes as using the menu); it's placed on that spot.
 * Tap an app to pick the spot from the ring instead. Under each app: where it already is.
 */
class MapActivity : Activity(), FlickView.Listener {
    private lateinit var menu: Slot.Folder
    private lateinit var all: List<Slot.App>
    private var shown = listOf<Slot.App>()
    private var paths = mapOf<String, List<List<Int>>>()
    private var undoMenu: Slot.Folder? = null

    private lateinit var grid: GridView
    private lateinit var overlay: FlickView
    private lateinit var status: TextView
    private lateinit var undo: Button
    private val adapter = Adapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        menu = Store.menu(this)
        all = Store.launchableApps(this)
        shown = all
        paths = menu.appPaths()

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(36), dp(12), 0)
        }
        col.addView(TextView(this).apply {
            text = "Map apps"
            textSize = 24f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(4), 0, 0, dp(4))
        })
        col.addView(TextView(this).apply {
            text = "Hold an app, then draw where it goes. Tap an app to pick the spot instead."
            textSize = 14f
            alpha = 0.75f
            setPadding(dp(4), 0, 0, dp(6))
        })
        col.addView(EditText(this).apply {
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
        }, LinearLayout.LayoutParams(-1, -2))
        val statusRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
        }
        status = TextView(this).apply { textSize = 14f; setPadding(dp(4), 0, dp(8), 0) }
        undo = Button(this).apply { text = "Undo"; setOnClickListener { undoLast() } }
        statusRow.addView(status, LinearLayout.LayoutParams(0, -2, 1f))
        statusRow.addView(undo)
        col.addView(statusRow)

        grid = GridView(this).apply {
            numColumns = GridView.AUTO_FIT
            columnWidth = dp(80)
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            verticalSpacing = dp(6)
            isFastScrollEnabled = true
            adapter = this@MapActivity.adapter
            setOnItemClickListener { _, v, pos, _ -> tapPlace(shown[pos], v) }
        }
        col.addView(grid, LinearLayout.LayoutParams(-1, 0, 1f))
        adapter.refresh()

        overlay = FlickView(this, menu, this, assign = true).apply { visibility = View.GONE }
        val frame = Frame(this)
        frame.addView(col, FrameLayout.LayoutParams(-1, -1))
        frame.addView(overlay, FrameLayout.LayoutParams(-1, -1))
        setContentView(frame)
    }

    // ---------------------------------------------------------------- placing

    /** Tap: show the top ring over the app, tap a spot (or flick) to place it. */
    private fun tapPlace(app: Slot.App, cell: View) {
        val at = centreIn(cell)
        overlay.carry = app
        overlay.visibility = View.VISIBLE
        overlay.openRing(emptyList(), at)
    }

    override fun onAssign(path: List<Int>) {
        val app = overlay.carry ?: return
        val folder = menu.at(path.dropLast(1)) ?: return
        val i = path.last()
        val old = folder.slots[i]
        if (old is Slot.Folder) return // folders are replaced only in the editor
        undoMenu = Store.menu(this) // fresh copy of what's saved now
        folder.slots[i] = app
        save()
        val where = menu.describe(path)
        status.text = if (old is Slot.App && old.pkg != app.pkg) "${app.label} → $where (was ${old.label})"
        else "${app.label} → $where"
        (status.parent as View).visibility = View.VISIBLE
        undo.visibility = View.VISIBLE
        overlay.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        onClose()
    }

    override fun onClose() {
        overlay.carry = null
        overlay.visibility = View.GONE
    }

    private fun undoLast() {
        val prev = undoMenu ?: return
        menu = prev
        undoMenu = null
        save()
        status.text = "Undone"
        undo.visibility = View.GONE
    }

    private fun save() {
        Store.saveMenu(this, menu)
        overlay.useMenu(menu)
        paths = menu.appPaths()
        adapter.refresh()
        WidgetRenderer.renderAll(this)
    }

    override fun onBackPressed() {
        if (overlay.visibility == View.VISIBLE) onClose() else super.onBackPressed()
    }

    private fun centreIn(v: View): Recognizer.Pt {
        val a = IntArray(2)
        val b = IntArray(2)
        v.getLocationInWindow(a)
        overlay.getLocationInWindow(b)
        return Recognizer.Pt(a[0] - b[0] + v.width / 2f, a[1] - b[1] + v.height / 2f)
    }

    /**
     * Holding an app still for a moment picks it up; from then on the touch is a menu stroke
     * (handed to the overlay). Moving first scrolls the list as usual.
     */
    private inner class Frame(ctx: Context) : FrameLayout(ctx) {
        private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
        private var downX = 0f
        private var downY = 0f
        private var downPos = -1
        private var tracking = false
        private var picked = false

        private val pickup = Runnable {
            if (!tracking || downPos < 0 || downPos >= shown.size) return@Runnable
            picked = true
            grid.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            overlay.carry = shown[downPos]
            overlay.visibility = View.VISIBLE
            overlay.beginAt(downX, downY, SystemClock.uptimeMillis())
        }

        override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
            if (picked) {
                forward(e)
                return true
            }
            if (overlay.visibility == View.VISIBLE) return false // the ring handles its own touches
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    tracking = true
                    downX = e.x
                    downY = e.y
                    downPos = positionAt(e.x, e.y)
                    removeCallbacks(pickup)
                    if (downPos >= 0) postDelayed(pickup, PICKUP_MS)
                }
                MotionEvent.ACTION_MOVE -> if (hypot(e.x - downX, e.y - downY) > slop) {
                    tracking = false
                    removeCallbacks(pickup)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    tracking = false
                    removeCallbacks(pickup)
                }
            }
            return false
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (!picked) return false
            forward(e)
            return true
        }

        private fun forward(e: MotionEvent) {
            if (e.actionMasked == MotionEvent.ACTION_DOWN) return
            overlay.onTouchEvent(e)
            if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) {
                picked = false
                tracking = false
            }
        }

        private fun positionAt(x: Float, y: Float): Int {
            val g = IntArray(2)
            val me = IntArray(2)
            grid.getLocationInWindow(g)
            getLocationInWindow(me)
            val pos = grid.pointToPosition((x - (g[0] - me[0])).toInt(), (y - (g[1] - me[1])).toInt())
            return if (pos == GridView.INVALID_POSITION) -1 else pos
        }
    }

    // ---------------------------------------------------------------- grid

    private inner class Adapter : BaseAdapter(), SectionIndexer {
        private var sections = arrayOf<String>()
        private var sectionStart = intArrayOf()

        fun refresh() {
            refreshSections()
            notifyDataSetChanged()
        }

        private fun refreshSections() {
            val letters = shown.map { letter(it) }
            sections = letters.distinct().toTypedArray()
            sectionStart = IntArray(sections.size) { letters.indexOf(sections[it]) }
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
            val cell = (convert as? LinearLayout) ?: LinearLayout(this@MapActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(2), dp(8), dp(2), dp(6))
                addView(ImageView(context), LinearLayout.LayoutParams(dp(48), dp(48)))
                addView(TextView(context).apply {
                    textSize = 12f
                    gravity = Gravity.CENTER
                    isSingleLine = true
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setPadding(0, dp(4), 0, 0)
                }, LinearLayout.LayoutParams(-1, -2))
                addView(TextView(context).apply {
                    textSize = 10f
                    gravity = Gravity.CENTER
                    isSingleLine = true
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setTextColor(context.getColor(R.color.accent_text))
                }, LinearLayout.LayoutParams(-1, -2))
            }
            val app = shown[p]
            (cell.getChildAt(0) as ImageView).setImageDrawable(Icons.appDrawable(this@MapActivity, app.pkg))
            (cell.getChildAt(1) as TextView).text = app.label
            val where = paths[app.pkg].orEmpty()
            (cell.getChildAt(2) as TextView).text = when (where.size) {
                0 -> ""
                1 -> menu.describe(where[0])
                else -> "${menu.describe(where[0])} +${where.size - 1}"
            }
            return cell
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val PICKUP_MS = 220L
    }
}
