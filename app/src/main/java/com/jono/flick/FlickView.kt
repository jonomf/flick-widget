package com.jono.flick

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.text.TextUtils
import android.text.TextPaint
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Full-screen, see-through view that draws the radial menu and turns touches into choices.
 *
 * States:
 * - ARMED: just opened from the widget. Nothing shown but a faint marker on the widget. A flick
 *   anywhere starts a gesture; a plain tap shows the menu ring.
 * - GESTURE: finger down. Flicks draw only the trail and the item being pointed at; holding still
 *   shows the ring.
 * - RING: a ring is on screen waiting for a tap (after a tap, or lifting on a folder). Tap an item,
 *   or flick from anywhere; tap outside to close; tap the centre to go back up a level.
 * - RESULT: practice mode only, shows what was picked for a moment.
 */
@SuppressLint("ViewConstructor")
class FlickView(
    context: Context,
    private var root: Slot.Folder,
    private val listener: Listener,
    /** Assign mode (the app drawer): a stroke picks a spot to put [carry] in, instead of launching. */
    private val assign: Boolean = false,
) : View(context) {

    interface Listener {
        fun onLaunch(app: Slot, route: List<String>, x: Float, y: Float) {}
        fun onClose()
        /** Assign mode: the spot chosen (folder indexes, then the slot index). */
        fun onAssign(path: List<Int>) {}
        /** Normal mode: a stroke ended on an empty spot. Return true to handle it (offer to map it). */
        fun onEmptySpot(path: List<Int>): Boolean = false
        /** The ring appeared or went away (to blank the widget under it). */
        fun onRingShown(shown: Boolean) {}
        /** A picker is about to cover the overlay: don't close when it does. */
        fun keepOpen(on: Boolean) {}
    }

    /** Assign mode: the app being placed, drawn under the finger. */
    var carry: Slot? = null

    /** Stop the auto-close and hold timers (something else is on screen now). */
    fun idle() {
        removeCallbacks(autoRing)
        removeCallbacks(timeout)
        removeCallbacks(ticker)
    }

    fun useMenu(m: Slot.Folder) {
        root = m
        Icons.clearRing()
        warmIcons()
        invalidate()
    }

    /** Assign mode: start a stroke at ([x], [y]) without a touch-down (the drawer saw the down). */
    fun beginAt(x: Float, y: Float, t: Long) {
        removeCallbacks(timeout)
        removeCallbacks(autoRing)
        mode = Mode.ARMED
        startGesture(x, y, t)
    }

    /**
     * Touch-spot mode (the floating spot): the stroke starts on the spot, so there's no armed
     * state. A plain tap shows the ring there; a stroke that goes nowhere closes.
     */
    var spotMode = false

    /** Touch spot: a finger just landed on the spot at [spot] (its centre); start drawing now. */
    fun beginFromSpot(spot: Recognizer.Pt, x: Float, y: Float, t: Long) {
        anchor = spot
        anchorSet = true
        revealed = true
        alpha = 1f
        beginAt(x, y, t)
        invalidate()
    }

    private enum class Mode { ARMED, GESTURE, RING, RESULT, DRAG }

    /** Something is still on screen that wants the layer (a ring to tap, a drag, a message). */
    val busy: Boolean get() = mode == Mode.RING || mode == Mode.DRAG || mode == Mode.RESULT

    // DRAG state (ring open by tap: press an item and move it to rearrange).
    private var pressItem = -1            // slot index pressed in the ring, before it moves
    private var pressAt = Recognizer.Pt(0f, 0f)
    private var dragFrom = listOf<Int>()  // full path of the item being moved
    private var dragItem: Slot? = null
    private var dragAt = Recognizer.Pt(0f, 0f)
    private var dragHover = NONE          // slot index under the finger, HUB, or NONE
    private var dragHoverSince = 0L
    private val slop by lazy { dp(12f) }
    private var lastRingShown = false

    private val d = resources.displayMetrics.density
    private fun dp(v: Float) = v * d

    private val geo = Recognizer.Geometry(
        minStroke = dp(36f),
        minAfterTurn = dp(22f),
        still = dp(9f),
        dwellMs = Store.dwellMs(context),
    )
    private val haptics = Store.haptics(context)
    private val dotStyle = Store.dotStyle(context)

    private val ringR = dp(96f)
    private val bubbleR = dp(28f)
    private val iconSize = dp(40f)
    private val hubR = dp(30f)

    private var mode = Mode.ARMED
    private var anchor = Recognizer.Pt(0f, 0f)

    // RING state: which folder is shown, where, and how we got to it.
    private var ringFolder: Slot.Folder = root
    private var ringAt = Recognizer.Pt(0f, 0f)
    private var ringPath = listOf<Int>()

    // GESTURE state.
    private var rec: Recognizer? = null
    private var gestureBase = listOf<Int>()
    private var gestureFrom = Mode.ARMED
    private var startedOutside = false
    private var lastHover = -1
    private var lastLevels = 1

    private var resultText = ""

    // ---------------------------------------------------------------- paints

    private val accent = context.getColor(R.color.accent_text)
    private val surface = context.getColor(R.color.widget_bg)
    private val onSurface = context.getColor(R.color.widget_text)
    private val highlight = context.getColor(R.color.btn_bg)

    private val trailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(5f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = accent
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2.5f)
    }
    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = dp(12f)
        textAlign = Paint.Align.CENTER
        color = onSurface
    }
    private val bigText = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = dp(18f)
        textAlign = Paint.Align.CENTER
        color = onSurface
        isFakeBoldText = true
    }

    // ---------------------------------------------------------------- setup

    /** Where the widget is (centre, in this view's coordinates). */
    fun arm(at: Recognizer.Pt) {
        anchor = at
        anchorSet = true
        if (revealWanted) post { reveal() }
        mode = Mode.ARMED
        rec = null
        invalidate()
        scheduleTimeout(4500)
        // No flick yet after a moment: show the ring on the widget.
        removeCallbacks(autoRing)
        removeCallbacks(preBlank)
        val delay = Store.ringDelayMs(context)
        if (!assign && delay > 0) {
            postDelayed(autoRing, delay)
            // Start fading the widget's buttons a little early, so they're gone as the ring appears.
            postDelayed(preBlank, max(0L, delay - 250L))
        }
    }

    private var preBlanked = false
    private val preBlank = Runnable {
        if (mode == Mode.ARMED) {
            preBlanked = true
            invalidate()
        }
    }

    private val autoRing = Runnable { if (mode == Mode.ARMED) showRing(root, anchor, emptyList()) }

    /** Opens straight into a folder's ring (a folder tapped on the widget). */
    fun openRing(path: List<Int>, at: Recognizer.Pt) {
        val f = root.at(path) ?: root
        anchor = at
        anchorSet = true
        if (revealWanted) post { reveal() }
        showRing(f, at, if (f === root) emptyList() else path)
    }

    /** Where each level's ring was shown, so going back up returns it to the same place. */
    private val ringPos = HashMap<List<Int>, Recognizer.Pt>()

    /** Up one level, with the ring back where that level was. */
    private fun goUp() {
        val up = ringPath.dropLast(1)
        showRing(root.at(up) ?: root, ringPos[up] ?: if (up.isEmpty()) anchor else ringAt, up)
    }

    private fun showRing(f: Slot.Folder, at: Recognizer.Pt, path: List<Int>) {
        removeCallbacks(autoRing)
        removeCallbacks(preBlank)
        preBlanked = false
        ringFolder = f
        ringPath = path
        ringAt = clamp(at)
        ringPos[path] = ringAt
        if (f !== root) warmIcons(f) // deeper levels: their icons too
        mode = Mode.RING
        rec = null
        invalidate()
        scheduleTimeout(10_000)
    }

    private fun clamp(p: Recognizer.Pt): Recognizer.Pt {
        val m = ringR + bubbleR + dp(20f)
        val w = width.toFloat().takeIf { it > 0 } ?: resources.displayMetrics.widthPixels.toFloat()
        val h = height.toFloat().takeIf { it > 0 } ?: resources.displayMetrics.heightPixels.toFloat()
        return Recognizer.Pt(p.x.coerceIn(m, max(m, w - m)), p.y.coerceIn(m + dp(24f), max(m, h - m - dp(24f))))
    }

    private val timeout = Runnable { listener.onClose() }

    private fun scheduleTimeout(ms: Long) {
        removeCallbacks(timeout)
        postDelayed(timeout, ms)
    }

    // ---------------------------------------------------------------- touch

    private val ticker = object : Runnable {
        override fun run() {
            val r = rec ?: return
            if (mode != Mode.GESTURE) return
            if (r.tick(SystemClock.uptimeMillis())) {
                buzz(if (r.levels.size > lastLevels) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.CLOCK_TICK)
                lastLevels = r.levels.size
                lastHover = r.hover
                invalidate()
            }
            postDelayed(this, 30)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                reveal()
                removeCallbacks(timeout)
                removeCallbacks(autoRing)
                if (mode == Mode.RESULT) return true
                removeCallbacks(preBlank)
                preBlanked = false
                // Undo after removing something: tap the message.
                val hr = hintRect
                if (mode == Mode.RING && undoRemove != null && hr != null &&
                    RectF(hr).apply { inset(-dp(14f), -dp(14f)) }.contains(e.x, e.y)
                ) {
                    undoLastRemove()
                    return true
                }
                // Ring open by tap: pressing an item may become a drag to move it.
                pressItem = if (mode == Mode.RING && !assign) itemAt(e.x, e.y) else -1
                pressAt = Recognizer.Pt(e.x, e.y)
                startGesture(e.x, e.y, e.eventTime)
            }
            MotionEvent.ACTION_MOVE -> {
                if (mode == Mode.DRAG) {
                    dragMove(e.x, e.y, e.eventTime)
                    return true
                }
                if (pressItem >= 0 && Recognizer.dist(pressAt, Recognizer.Pt(e.x, e.y)) > slop) {
                    startDrag(pressItem, e.x, e.y, e.eventTime)
                    return true
                }
                val r = rec ?: return true
                for (h in 0 until e.historySize) {
                    feed(r, e.getHistoricalX(h), e.getHistoricalY(h), e.getHistoricalEventTime(h))
                }
                feed(r, e.x, e.y, e.eventTime)
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                if (mode == Mode.DRAG) {
                    drop()
                    return true
                }
                val r = rec ?: return true
                removeCallbacks(ticker)
                finish(r, r.up(e.x, e.y, e.eventTime))
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(ticker)
                removeCallbacks(dragTick)
                if (mode == Mode.DRAG) { mode = Mode.RING; showRing(ringFolder, ringAt, ringPath) } else back()
            }
        }
        return true
    }

    /** The current stroke started on the centre of an open ring. */
    private var centreStart = false

    private fun startGesture(x: Float, y: Float, t: Long) {
        gestureFrom = mode
        val p = Recognizer.Pt(x, y)
        val r: Recognizer
        centreStart = false
        if (mode == Mode.RING) {
            val d = Recognizer.dist(p, ringAt)
            val inside = d <= ringR + bubbleR * 1.6f
            startedOutside = !inside
            if (d <= hubR * 1.5f && !assign) {
                // From the centre of an open ring: a full gesture (flicks, zigzags into folders),
                // measured from the ring's centre.
                centreStart = true
                r = Recognizer(ringFolder, geo)
                r.down(x, y, t, ringAt)
            } else {
                r = Recognizer(ringFolder, geo, startShown = inside)
                r.down(x, y, t, if (inside) ringAt else null)
            }
            gestureBase = ringPath
        } else {
            startedOutside = false
            r = Recognizer(root, geo)
            r.down(x, y, t)
            gestureBase = emptyList()
        }
        rec = r
        lastHover = r.hover
        lastLevels = 1
        mode = Mode.GESTURE
        removeCallbacks(ticker)
        postDelayed(ticker, 30)
        invalidate()
    }

    private fun feed(r: Recognizer, x: Float, y: Float, t: Long) {
        val entered = r.move(x, y, t)
        if (entered) {
            buzz(HapticFeedbackConstants.CONFIRM)
        } else if (r.hover != lastHover && r.hover >= 0 && r.folder.slots[r.hover] != null) {
            buzz(HapticFeedbackConstants.CLOCK_TICK)
        }
        lastHover = r.hover
        lastLevels = r.levels.size
    }

    private fun finish(r: Recognizer, res: Recognizer.Result) {
        val baseNames = root.names(gestureBase)
        when (res) {
            is Recognizer.Result.Launch -> if (assign) {
                listener.onAssign(gestureBase + res.path)
            } else {
                val route = baseNames + res.names.dropLast(1)
                if (Store.practice(context)) {
                    resultText = (route + res.app.label).joinToString("  ›  ")
                    mode = Mode.RESULT
                    invalidate()
                    postDelayed({ if (spotMode) listener.onClose() else arm(anchor) }, 1100)
                } else {
                    listener.onLaunch(res.app, route, r.point.x, r.point.y)
                }
            }
            is Recognizer.Result.Open -> {
                val centreTap = res.folder === ringFolder && gestureFrom == Mode.RING && r.levels.size == 1
                if (centreTap) {
                    // Centre of the ring: up a level, or close at the top.
                    if (ringPath.isEmpty()) listener.onClose()
                    else goUp()
                } else {
                    buzz(HapticFeedbackConstants.CONFIRM)
                    showRing(res.folder, res.at, gestureBase + res.path)
                }
            }
            is Recognizer.Result.Tap -> when {
                startedOutside -> listener.onClose()
                // Just armed: a tap (not a flick) closes. In the drawer, holding still then
                // letting go shows the ring to tap a spot on.
                gestureFrom == Mode.ARMED -> when {
                    spotMode -> showRing(root, anchor, emptyList()) // tap on the touch spot
                    assign -> showRing(root, res.at, emptyList())
                    else -> listener.onClose()
                }
                // Tap on the centre: up a level, or close at the top (as before).
                centreStart -> if (ringPath.isEmpty()) listener.onClose()
                    else goUp()
                else -> back()
            }
            is Recognizer.Result.Spot -> if (assign) {
                listener.onAssign(gestureBase + res.path)
            } else if (listener.onEmptySpot(gestureBase + res.path)) {
                idle()
            } else {
                buzz(HapticFeedbackConstants.REJECT)
                back()
            }
            Recognizer.Result.Cancel -> {
                buzz(HapticFeedbackConstants.REJECT)
                back()
            }
        }
    }

    /** Back to where the gesture started from (armed, or the ring that was open). */
    private fun back() {
        if ((assign || spotMode) && gestureFrom != Mode.RING) listener.onClose()
        else if (gestureFrom == Mode.RING) showRing(ringFolder, ringAt, ringPath) else arm(anchor)
    }

    private fun buzz(kind: Int) {
        if (haptics) performHapticFeedback(kind)
    }

    // ---------------------------------------------------------------- drawing

    // ---------------------------------------------------------------- drag to rearrange

    /** Ring slot index at ([x], [y]) if an item's bubble is there, else -1. */
    private fun itemAt(x: Float, y: Float): Int {
        for (i in 0 until Slot.SLOTS) {
            if (ringFolder.slots[i] == null) continue
            val p = slotPos(ringAt, i)
            if (Recognizer.dist(p, Recognizer.Pt(x, y)) <= bubbleR * 1.25f) return i
        }
        return -1
    }

    private fun slotPos(o: Recognizer.Pt, i: Int): Recognizer.Pt {
        val a = Math.toRadians(i * 45.0)
        return Recognizer.Pt(o.x + (ringR * sin(a)).toFloat(), o.y - (ringR * cos(a)).toFloat())
    }

    private fun startDrag(i: Int, x: Float, y: Float, t: Long) {
        removeCallbacks(ticker)
        removeCallbacks(timeout)
        rec = null
        dragFrom = ringPath + i
        dragItem = ringFolder.slots[i]
        pressItem = -1
        mode = Mode.DRAG
        buzz(HapticFeedbackConstants.LONG_PRESS)
        dragMove(x, y, t)
        postDelayed(dragTick, 60)
    }

    private fun dragMove(x: Float, y: Float, t: Long) {
        dragAt = Recognizer.Pt(x, y)
        val d = Recognizer.dist(ringAt, dragAt)
        val h = when {
            Recognizer.dist(deletePos(), dragAt) < bubbleR * 1.3f -> DELETE
            editable(dragItem) && Recognizer.dist(editPos(), dragAt) < bubbleR * 1.3f -> EDIT
            d < hubR -> HUB
            d > ringR + bubbleR * 1.8f -> NONE
            d < geo.deadZone -> NONE
            else -> Recognizer.sector(Recognizer.angle(ringAt, dragAt))
        }
        if (h != dragHover) {
            dragHover = h
            dragHoverSince = t
            if (h >= 0) buzz(HapticFeedbackConstants.CLOCK_TICK)
        }
        invalidate()
    }

    /** Holding over a folder opens it; holding over the centre goes back up a level. */
    private val dragTick = object : Runnable {
        override fun run() {
            if (mode != Mode.DRAG) return
            if (SystemClock.uptimeMillis() - dragHoverSince >= DRAG_OPEN_MS) {
                val target = if (dragHover >= 0) ringFolder.slots[dragHover] else null
                val into = ringPath + dragHover
                if (target is Slot.Folder && target !== dragItem && !isInside(into, dragFrom)) {
                    openDuringDrag(target, into)
                } else if (dragHover == HUB && ringPath.isNotEmpty()) {
                    openDuringDrag(root.at(ringPath.dropLast(1)) ?: root, ringPath.dropLast(1))
                }
            }
            postDelayed(this, 60)
        }
    }

    private fun openDuringDrag(f: Slot.Folder, path: List<Int>) {
        ringFolder = f
        ringPath = path
        dragHover = NONE
        dragHoverSince = SystemClock.uptimeMillis()
        buzz(HapticFeedbackConstants.CONFIRM)
        invalidate()
    }

    /** True when [path] is [ancestor] or somewhere inside it. */
    private fun isInside(path: List<Int>, ancestor: List<Int>) =
        path.size >= ancestor.size && path.subList(0, ancestor.size) == ancestor

    /**
     * Let go: on an empty spot, move it there; on a folder, into that folder (same direction
     * if free, else its first free spot); on another item, swap them. Anywhere else: no change.
     */
    /** The remove target: above the ring (below it when there's no room above). */
    private fun deletePos(): Recognizer.Pt {
        val above = ringAt.y - ringR - bubbleR - dp(52f)
        return Recognizer.Pt(ringAt.x, if (above > dp(44f)) above else ringAt.y + ringR + bubbleR + dp(52f))
    }

    /** The edit target: below the ring (or below the remove target when that's below). */
    private fun editPos(): Recognizer.Pt {
        val del = deletePos()
        val y = if (del.y > ringAt.y) del.y + bubbleR * 2 + dp(40f) else ringAt.y + ringR + bubbleR + dp(52f)
        return Recognizer.Pt(ringAt.x, y)
    }

    /** Folders get renamed, links edited, people a new picture. Apps have nothing to edit. */
    private fun editable(s: Slot?) = s is Slot.Folder || s is Slot.Link || s is Slot.Contact

    private fun editLabel(s: Slot?) = when (s) {
        is Slot.Folder -> "Rename"
        is Slot.Link -> "Edit"
        is Slot.Contact -> "Picture"
        else -> ""
    }

    /** Dropped on the edit target: rename / edit / new picture, then back to the ring. */
    private fun editItem(parentPath: List<Int>, idx: Int, item: Slot) {
        val parent = root.at(parentPath) ?: return
        removeCallbacks(timeout)
        fun saved(msg: String) {
            Icons.clear() // pictures may have changed
            menuChanged()
            WidgetRenderer.renderAll(context)
            buzz(HapticFeedbackConstants.CONFIRM)
            showHint(msg, 1400)
            showRing(ringFolder, ringAt, ringPath)
        }
        when (item) {
            is Slot.Folder -> Dialogs.folderName(context, item.name) { name ->
                item.name = name
                saved("Renamed to $name")
            }
            is Slot.Link -> {
                listener.keepOpen(true)
                Dialogs.link(context, item.url, item.name, item.photo, onCancel = { listener.keepOpen(false) }) { l ->
                    listener.keepOpen(false)
                    if (parent.slots[idx] === item) parent.slots[idx] = l
                    saved("Saved ${l.name}")
                }
            }
            is Slot.Contact -> {
                listener.keepOpen(true)
                PickContactActivity.pickPhoto(context) { p ->
                    listener.keepOpen(false)
                    if (p != null && parent.slots[idx] === item) {
                        parent.slots[idx] = Slot.Contact(item.name, item.number, item.action, p)
                        saved("New picture for ${item.name}")
                    }
                }
            }
            is Slot.App -> {}
        }
    }

    private var undoRemove: Triple<List<Int>, Int, Slot>? = null
    private var hintRect: RectF? = null

    private fun remove(parentPath: List<Int>, idx: Int, item: Slot) {
        val parent = root.at(parentPath) ?: return
        if (parent.slots[idx] !== item) return
        parent.slots[idx] = null
        undoRemove = Triple(parentPath, idx, item)
        menuChanged()
        WidgetRenderer.renderAll(context)
        buzz(HapticFeedbackConstants.CONFIRM)
        showHint("Removed ${item.label} · tap here to undo", 4000)
        showRing(ringFolder, ringAt, ringPath)
    }

    private fun undoLastRemove() {
        val (parentPath, idx, item) = undoRemove ?: return
        undoRemove = null
        val parent = root.at(parentPath)
        if (parent != null && parent.slots[idx] == null) {
            parent.slots[idx] = item
            menuChanged()
            WidgetRenderer.renderAll(context)
            buzz(HapticFeedbackConstants.CONFIRM)
            showHint("Put back ${item.label}", 1400)
        }
        showRing(ringFolder, ringAt, ringPath)
    }

    private val clearHint = Runnable {
        resultHint = null
        undoRemove = null
        invalidate()
    }

    private fun showHint(msg: String, ms: Long) {
        resultHint = msg
        removeCallbacks(clearHint)
        postDelayed(clearHint, ms)
        invalidate()
    }

    private fun drop() {
        removeCallbacks(dragTick)
        val item = dragItem
        val srcParent = root.at(dragFrom.dropLast(1))
        val srcIdx = dragFrom.last()
        val h = dragHover
        mode = Mode.RING
        if (h == EDIT && item != null && srcParent != null) {
            dragItem = null
            showRing(ringFolder, ringAt, ringPath)
            editItem(dragFrom.dropLast(1), srcIdx, item)
            return
        }
        if (h == DELETE && item != null && srcParent != null) {
            dragItem = null
            val parentPath = dragFrom.dropLast(1)
            if (item is Slot.Folder && item.slots.any { it != null }) {
                showRing(ringFolder, ringAt, ringPath)
                removeCallbacks(timeout)
                android.app.AlertDialog.Builder(context)
                    .setMessage("Remove the folder \"${item.name}\" and everything in it?")
                    .setPositiveButton("Remove") { _, _ -> remove(parentPath, srcIdx, item) }
                    .setNegativeButton("Cancel") { _, _ -> showRing(ringFolder, ringAt, ringPath) }
                    .setOnCancelListener { showRing(ringFolder, ringAt, ringPath) }
                    .create()
                    .also { Dialogs.overOtherApps(context, it) }
                    .show()
            } else {
                remove(parentPath, srcIdx, item)
            }
            return
        }
        var msg: String? = null
        if (item != null && srcParent != null && h >= 0 && srcParent.slots[srcIdx] === item) {
            val target = ringFolder.slots[h]
            val here = ringPath + h
            when {
                here == dragFrom -> {}
                target == null && !isInside(here, dragFrom) -> {
                    srcParent.slots[srcIdx] = null
                    ringFolder.slots[h] = item
                    msg = "Moved ${item.label}"
                }
                target is Slot.Folder && target !== item && !isInside(here, dragFrom) -> {
                    val spot = if (target.slots[h] == null) h else target.slots.indexOfFirst { it == null }
                    if (spot < 0) {
                        msg = "${target.name} is full"
                    } else {
                        srcParent.slots[srcIdx] = null
                        target.slots[spot] = item
                        msg = "${item.label} → ${target.name}"
                    }
                }
                target != null && target !is Slot.Folder && !isInside(here, dragFrom) -> {
                    // Swap places.
                    srcParent.slots[srcIdx] = target
                    ringFolder.slots[h] = item
                    msg = "Swapped ${item.label} and ${target.label}"
                }
            }
        }
        dragItem = null
        if (msg != null) {
            menuChanged()
            WidgetRenderer.renderAll(context)
            buzz(HapticFeedbackConstants.CONFIRM)
            undoRemove = null
            showHint(msg, 1400)
        }
        showRing(ringFolder, ringAt, ringPath)
    }

    private var resultHint: String? = null

    /**
     * Nothing is drawn until the launcher's own opening animation has finished (it zooms the
     * window out of the widget, which made the marker fly across the screen). Then everything
     * pops in quickly, in place: a tiny scale and fade around the widget.
     */
    private var revealed = false

    private var revealWanted = false

    fun reveal() {
        if (revealed) return
        if (!anchorSet) {
            revealWanted = true // as soon as we know where the widget is
            return
        }
        revealed = true
        pivotX = anchor.x
        pivotY = anchor.y
        alpha = 0f
        scaleX = 0.94f
        scaleY = 0.94f
        animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(REVEAL_MS).start()
        invalidate()
    }

    /** Draws an item's icon: the ready-made bitmap when there is one, else straight from the icon. */
    private fun drawIcon(c: Canvas, s: Slot, x: Float, y: Float, size: Float) {
        val b = Icons.ringBitmap(s, size.toInt())
        if (b != null) {
            iconRect.set(x, y, x + size, y + size)
            c.drawBitmap(b, null, iconRect, bitmapPaint)
        } else {
            Icons.draw(context, c, s, x, y, size)
        }
    }

    private val iconRect = RectF()
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    /** Prepares the ring's icon bitmaps in the background, then redraws. */
    fun warmIcons(from: Slot.Folder = root) {
        val ctx = context.applicationContext
        val m = from
        val extra = carry
        val px = iconSize.toInt()
        Thread {
            Icons.warmRing(ctx, m, px, extra) { post { invalidate() } }
        }.apply { priority = Thread.NORM_PRIORITY - 1 }.start()
    }

    /** Saves a change made in the ring, updates the widget and redraws the changed icons. */
    private fun menuChanged() {
        Store.saveMenu(context, root)
        Icons.clearRing()
        warmIcons()
    }

    override fun onDraw(c: Canvas) {
        if (!revealed) return
        val r = rec
        val ringVisible = mode == Mode.RING || mode == Mode.DRAG || (mode == Mode.GESTURE && r?.shown == true) ||
            (mode == Mode.GESTURE && gestureFrom == Mode.RING && centreStart) ||
            (mode == Mode.ARMED && preBlanked)
        if (ringVisible != lastRingShown) {
            lastRingShown = ringVisible
            post { listener.onRingShown(ringVisible) }
        }
        c.drawColor(Color.argb(if (ringVisible) 190 else if (mode == Mode.GESTURE) 60 else 18, 0, 0, 0))

        carry?.let { item ->
            // The app being placed: above the ring, or under the finger while drawing.
            val centre = if (mode == Mode.GESTURE && r != null) r.origin else ringAt
            val p = if (mode == Mode.GESTURE && r != null && !r.shown) r.point
            else Recognizer.Pt(centre.x, (centre.y - ringR - bubbleR - dp(44f)).coerceAtLeast(dp(56f)))
            if (mode == Mode.RING || mode == Mode.GESTURE) {
                val size = iconSize * 1.2f
                if (mode == Mode.RING || r?.shown == true) drawChip(c, p.x, p.y - size / 2 - dp(14f), "Place ${item.label}", text)
                drawIcon(c, item, p.x - size / 2, p.y - size / 2, size)
            }
        }
        when (mode) {
            Mode.ARMED -> drawAnchor(c)
            Mode.RING -> {
                drawRing(c, ringFolder, ringAt, -1, ringPath.isNotEmpty())
                hintRect = resultHint?.let { drawChip(c, ringAt.x, hintY(), it, text, 260f) }
            }
            Mode.DRAG -> {
                val skip = if (ringPath == dragFrom.dropLast(1)) dragFrom.last() else -1
                drawRing(c, ringFolder, ringAt, dragHover, ringPath.isNotEmpty(), skip)
                drawDeleteTarget(c, dragHover == DELETE)
                if (editable(dragItem)) drawEditTarget(c, dragHover == EDIT, editLabel(dragItem))
                if (dragHover == HUB && ringPath.isNotEmpty()) {
                    outline.color = accent
                    outline.alpha = 255
                    c.drawCircle(ringAt.x, ringAt.y, hubR, outline)
                }
                dragItem?.let {
                    val size = iconSize * 1.25f
                    drawIcon(c, it, dragAt.x - size / 2, dragAt.y - size / 2 - dp(36f), size)
                }
            }
            Mode.GESTURE -> if (r != null) {
                // Flicking from the centre of an open ring: keep the ring faintly behind the trail.
                if (gestureFrom == Mode.RING && centreStart && !r.shown) {
                    val save = c.saveLayerAlpha(null, 90)
                    drawRing(c, ringFolder, ringAt, if (r.levels.size == 1) r.hover else -1, ringPath.isNotEmpty())
                    c.restoreToCount(save)
                }
                drawGesture(c, r)
            }
            Mode.RESULT -> drawChip(c, width / 2f, height / 2f, resultText, bigText)
        }
    }

    /** Set once we know where the widget is; until then nothing is drawn (not at 0,0). */
    private var anchorSet = false

    private fun drawAnchor(c: Canvas) {
        if (!anchorSet) return
        outline.color = accent
        outline.alpha = 200
        c.drawCircle(anchor.x, anchor.y, hubR, outline)
        fill.color = accent
        c.drawCircle(anchor.x, anchor.y, dp(5f), fill)
    }

    private fun drawGesture(c: Canvas, r: Recognizer) {
        // Trail.
        val path = Path()
        r.trail.forEachIndexed { i, p -> if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y) }
        trailPaint.alpha = if (r.shown) 120 else 230
        c.drawPath(path, trailPaint)

        // Each folder entered: a dot at the corner and its name.
        r.levels.drop(1).forEach { lv ->
            fill.color = accent
            c.drawCircle(lv.origin.x, lv.origin.y, dp(6f), fill)
            if (!r.shown || lv !== r.current) drawChip(c, lv.origin.x, lv.origin.y - dp(22f), lv.folder.name, text)
        }

        if (r.shown) {
            drawRing(c, r.folder, r.origin, r.hover, r.levels.size > 1 || gestureBase.isNotEmpty())
        } else {
            // Flicking: show only what letting go would pick, next to the finger. Pointing at a
            // folder shows what's in that same direction inside it (folders it passes through first).
            val (s, via) = r.flickTarget()
            if (r.hover >= 0) {
                val p = r.point
                val y = p.y - dp(64f)
                if (s != null) {
                    fill.color = surface
                    c.drawCircle(p.x, y, bubbleR, fill)
                    drawIcon(c, s, p.x - iconSize / 2, y - iconSize / 2, iconSize)
                    drawChip(c, p.x, y + bubbleR + dp(14f), (via + s.label).joinToString(" › "), text, 240f)
                } else if (via.isNotEmpty()) {
                    outline.color = onSurface
                    outline.alpha = 90
                    c.drawCircle(p.x, y, bubbleR * 0.6f, outline)
                    drawChip(c, p.x, y + bubbleR + dp(14f), (via + "empty").joinToString(" › "), text, 240f)
                } else {
                    outline.color = onSurface
                    outline.alpha = 90
                    c.drawCircle(p.x, y, bubbleR * 0.6f, outline)
                }
            }
        }
    }

    private val hubIcon = context.getDrawable(R.drawable.ic_hub)

    private fun drawHubLabel(c: Canvas, f: Slot.Folder, o: Recognizer.Pt, nested: Boolean) {
        fill.color = surface
        c.drawCircle(o.x, o.y, hubR, fill)
        val hubLabel = if (nested && (mode == Mode.RING || mode == Mode.DRAG)) "‹ ${f.name}" else f.name
        val t = TextUtils.ellipsize(hubLabel, text, hubR * 2.6f, TextUtils.TruncateAt.END).toString()
        c.drawText(t, o.x, o.y + text.textSize / 3, text)
    }

    private fun drawRing(c: Canvas, f: Slot.Folder, o: Recognizer.Pt, hover: Int, nested: Boolean, skip: Int = -1) {
        // Hub: the Flick symbol at the top level; inside a folder, its name ("‹" = tap to go up).
        if (f === root) {
            hubIcon?.let {
                it.setBounds((o.x - hubR).toInt(), (o.y - hubR).toInt(), (o.x + hubR).toInt(), (o.y + hubR).toInt())
                it.draw(c)
            }
        } else {
            drawHubLabel(c, f, o, nested)
        }

        for (i in 0 until Slot.SLOTS) {
            val a = Math.toRadians(i * 45.0)
            val x = o.x + (ringR * sin(a)).toFloat()
            val y = o.y - (ringR * cos(a)).toFloat()
            val s = if (i == skip) null else f.slots[i]
            if (s == null) {
                // Empty spot: a small dot (style is a setting), bigger when pointed at.
                val on = i == hover
                when (dotStyle) {
                    Store.DOT_OFF -> if (on) {
                        outline.color = onSurface
                        outline.alpha = 160
                        c.drawCircle(x, y, dp(8f), outline)
                    }
                    Store.DOT_GREY -> {
                        fill.color = if (on) highlight else surface
                        fill.alpha = 235
                        c.drawCircle(x, y, if (on) dp(9f) else dp(5f), fill)
                        fill.alpha = 255
                        outline.color = onSurface
                        outline.alpha = 120
                        c.drawCircle(x, y, if (on) dp(9f) else dp(5f), outline)
                    }
                    else -> {
                        // White, with a faint shadow ring so it shows on light wallpapers too.
                        val rad = if (on) dp(7f) else dp(3.5f)
                        fill.color = Color.BLACK
                        fill.alpha = 60
                        c.drawCircle(x, y, rad + dp(1.2f), fill)
                        fill.color = Color.WHITE
                        fill.alpha = 240
                        c.drawCircle(x, y, rad, fill)
                        fill.alpha = 255
                    }
                }
                continue
            }
            val on = i == hover
            fill.color = if (on) highlight else surface
            c.drawCircle(x, y, if (on) bubbleR * 1.18f else bubbleR, fill)
            if (on) {
                outline.color = accent
                outline.alpha = 255
                c.drawCircle(x, y, bubbleR * 1.18f, outline)
            }
            drawIcon(c, s, x - iconSize / 2, y - iconSize / 2, iconSize)
            val label = s.label + if (s is Slot.Folder) " ›" else ""
            drawChip(c, x, y + bubbleR + dp(13f), label, text)
        }
    }

    /** Messages go below the ring, or above it when the remove target is below. */
    private fun hintY(): Float {
        val below = ringAt.y + ringR + bubbleR + dp(40f)
        return if (below < height - dp(40f)) below else ringAt.y - ringR - bubbleR - dp(40f)
    }

    private fun drawEditTarget(c: Canvas, on: Boolean, label: String) {
        val p = editPos()
        val r = if (on) bubbleR * 1.15f else bubbleR * 0.85f
        fill.color = if (on) highlight else surface
        fill.alpha = 240
        c.drawCircle(p.x, p.y, r, fill)
        fill.alpha = 255
        if (on) {
            outline.color = accent
            outline.alpha = 255
            c.drawCircle(p.x, p.y, r, outline)
        }
        // A pencil: a slanted bar with a point.
        val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = onSurface
            style = Paint.Style.STROKE
            strokeWidth = dp(3.5f)
            strokeCap = Paint.Cap.ROUND
        }
        val k = r * 0.4f
        c.drawLine(p.x - k, p.y + k, p.x + k, p.y - k, ink)
        ink.strokeWidth = dp(2f)
        c.drawLine(p.x - k * 1.25f, p.y + k * 1.25f, p.x - k * 0.75f, p.y + k * 0.75f, ink)
        drawChip(c, p.x, p.y + r + dp(13f), label, text)
    }

    private fun drawDeleteTarget(c: Canvas, on: Boolean) {
        val p = deletePos()
        val r = if (on) bubbleR * 1.15f else bubbleR * 0.85f
        fill.color = if (on) context.getColor(android.R.color.holo_red_dark) else surface
        fill.alpha = 240
        c.drawCircle(p.x, p.y, r, fill)
        fill.alpha = 255
        val ink = if (on) Color.WHITE else onSurface
        val x = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ink
            style = Paint.Style.STROKE
            strokeWidth = dp(3f)
            strokeCap = Paint.Cap.ROUND
        }
        val k = r * 0.38f
        c.drawLine(p.x - k, p.y - k, p.x + k, p.y + k, x)
        c.drawLine(p.x + k, p.y - k, p.x - k, p.y + k, x)
        drawChip(c, p.x, p.y + r + dp(13f), "Remove", text)
    }

    /** Text on a small rounded backing so it reads over any wallpaper. Returns where it was drawn. */
    private fun drawChip(c: Canvas, x: Float, y: Float, label: String, p: TextPaint, maxDp: Float = 96f): RectF {
        val maxW = min(width * 0.9f, if (p === bigText) width * 0.9f else dp(maxDp))
        val t = TextUtils.ellipsize(label, p, maxW, TextUtils.TruncateAt.END).toString()
        val w = p.measureText(t)
        val padH = dp(6f)
        val h = p.textSize + dp(6f)
        fill.color = surface
        fill.alpha = 225
        val rect = RectF(x - w / 2 - padH, y - h / 2, x + w / 2 + padH, y + h / 2)
        c.drawRoundRect(rect, h / 2, h / 2, fill)
        fill.alpha = 255
        c.drawText(t, x, y + p.textSize / 3, p)
        return rect
    }

    companion object {
        private const val NONE = -1
        private const val HUB = -2
        private const val DELETE = -3
        private const val EDIT = -4
        private const val REVEAL_MS = 70L
        /** Hold over a folder (or the centre) this long while dragging to open it (or go up). */
        private const val DRAG_OPEN_MS = 450L
    }
}
