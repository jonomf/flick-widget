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
    private var undoMenu: Slot.Folder? = null

    private lateinit var picker: AppPicker
    private lateinit var flick: FlickView
    private lateinit var status: TextView
    private lateinit var undo: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        menu = Store.menu(this)

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

        picker = AppPicker(
            this,
            onNewFolder = { cell -> tapPlace(Slot.Folder("Folder"), cell) },
            onNewPerson = { cell -> PickContactActivity.start(this) { p -> if (p != null) tapPlace(p, cell) } },
            onNewLink = { cell -> Dialogs.link(this) { l -> tapPlace(l, cell) } },
        ) { app, cell -> tapPlace(app, cell) }
            .apply { menu = this@MapActivity.menu }
        col.addView(picker, LinearLayout.LayoutParams(-1, 0, 1f))

        flick = FlickView(this, menu, this, assign = true).apply { visibility = View.GONE }
        val frame = Frame(this)
        frame.addView(col, FrameLayout.LayoutParams(-1, -1))
        frame.addView(flick, FrameLayout.LayoutParams(-1, -1))
        setContentView(frame)
    }

    override fun onResume() {
        super.onResume()
        picker.refresh() // recents, and usage access may have just been allowed
    }

    // ---------------------------------------------------------------- placing

    /** Tap: show the top ring over the app, tap a spot (or flick) to place it. */
    private fun tapPlace(app: Slot, cell: View) {
        val at = centreIn(cell)
        flick.carry = app
        flick.visibility = View.VISIBLE
        flick.openRing(emptyList(), at)
    }

    override fun onAssign(path: List<Int>) {
        val item = flick.carry ?: return
        val folder = menu.at(path.dropLast(1)) ?: return
        val i = path.last()
        val old = folder.slots[i]
        if (old is Slot.Folder) return // folders are replaced only in the editor
        undoMenu = Store.menu(this) // fresh copy of what's saved now
        folder.slots[i] = item
        save()
        val where = menu.describe(path)
        fun report() {
            val replaced = old is Slot.App && (item !is Slot.App || old.pkg != item.pkg)
            status.text = "${item.label} → $where" + if (replaced) " (was ${old?.label})" else ""
            (status.parent as View).visibility = View.VISIBLE
            undo.visibility = View.VISIBLE
        }
        report()
        flick.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        onClose()
        if (item is Slot.Folder) {
            Dialogs.folderName(this, item.name) { name ->
                item.name = name
                save()
                report()
            }
        }
    }

    override fun onClose() {
        flick.carry = null
        flick.visibility = View.GONE
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
        flick.useMenu(menu)
        picker.menu = menu
        WidgetRenderer.renderAll(this)
    }

    override fun onBackPressed() {
        if (flick.visibility == View.VISIBLE) onClose() else super.onBackPressed()
    }

    private fun centreIn(v: View): Recognizer.Pt {
        val a = IntArray(2)
        val b = IntArray(2)
        v.getLocationInWindow(a)
        flick.getLocationInWindow(b)
        return Recognizer.Pt(a[0] - b[0] + v.width / 2f, a[1] - b[1] + v.height / 2f)
    }

    /**
     * Holding an app still for a moment picks it up; from then on the touch is a menu stroke
     * (handed to the flick). Moving first scrolls the list as usual.
     */
    private inner class Frame(ctx: Context) : FrameLayout(ctx) {
        private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
        private var downX = 0f
        private var downY = 0f
        private var downApp: Slot? = null
        private var tracking = false
        private var picked = false

        private val pickup = Runnable {
            val app = downApp
            if (!tracking || app == null) return@Runnable
            picked = true
            picker.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            flick.carry = app
            flick.visibility = View.VISIBLE
            flick.beginAt(downX, downY, SystemClock.uptimeMillis())
        }

        override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
            if (picked) {
                forward(e)
                return true
            }
            if (flick.visibility == View.VISIBLE) return false // the ring handles its own touches
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    tracking = true
                    downX = e.x
                    downY = e.y
                    downApp = appAt(e.x, e.y)
                    removeCallbacks(pickup)
                    if (downApp != null) postDelayed(pickup, PICKUP_MS)
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
            flick.onTouchEvent(e)
            if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) {
                picked = false
                tracking = false
            }
        }

        private fun appAt(x: Float, y: Float): Slot? {
            val p = IntArray(2)
            val me = IntArray(2)
            picker.getLocationInWindow(p)
            getLocationInWindow(me)
            return picker.itemAt(x - (p[0] - me[0]), y - (p[1] - me[1]))?.first
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val PICKUP_MS = 220L
    }
}
