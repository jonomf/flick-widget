package com.jono.flick

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Marking-menu gesture recognizer for one stroke (finger down → up).
 *
 * Two ways to use it, mixed freely within one stroke:
 * - **Flick (expert):** nothing is shown. Each straight segment of the stroke picks a slot by its
 *   direction. A sharp turn after a segment that points at a folder enters that folder, so a fast
 *   zigzag goes several levels deep. Lifting picks whatever the last segment points at.
 * - **Hold (novice):** keeping the finger still for [Geometry.dwellMs] shows the current ring.
 *   While the ring is shown, the slot under the finger is highlighted; holding on a folder opens it
 *   where the finger is; lifting on an app opens it; lifting near the centre keeps the ring open.
 *
 * Coordinates are pixels; all distances in [Geometry] are pixels too. Pure Kotlin, unit-tested.
 */
class Recognizer(private val root: Slot.Folder, private val g: Geometry, startShown: Boolean = false) {

    class Geometry(
        /** A segment must be at least this long to pick a direction. */
        val minStroke: Float,
        /** After a turn, the new segment must travel this far before the turn counts. */
        val minAfterTurn: Float,
        /** Moving less than this counts as holding still. */
        val still: Float,
        /** Hold this long to show the ring (or open the folder under the finger). */
        val dwellMs: Long,
        /** Minimum change of direction, in degrees, for a turn. */
        val turnDeg: Float = 55f,
        /** While the ring is shown: inner radius where nothing is highlighted. */
        val deadZone: Float = minStroke * 0.6f,
    )

    data class Pt(val x: Float, val y: Float)

    /** One level of the path taken: the folder, where its ring is centred, and the slot index used to reach it. */
    class Level(val folder: Slot.Folder, val origin: Pt, val index: Int)

    sealed class Result {
        /** Open this app. [path] is the folder indexes walked to reach it, then the app's index. */
        class Launch(val app: Slot.App, val path: List<Int>, val names: List<String>) : Result()
        /** Show this folder's ring at [at] and wait for a tap or another flick. */
        class Open(val folder: Slot.Folder, val at: Pt, val path: List<Int>) : Result()
        /** A plain tap: no movement, nothing chosen. */
        class Tap(val at: Pt) : Result()
        /** The stroke went somewhere with nothing in it. */
        object Cancel : Result()
    }

    /** Levels entered so far; the last one is current. */
    val levels = mutableListOf<Level>()
    /** Every point of the stroke, for drawing the trail. */
    val trail = mutableListOf<Pt>()
    /** Whether the current level's ring is visible. */
    var shown = startShown
        private set
    /** Slot index currently pointed at in the current folder, or -1. */
    var hover = -1
        private set

    private var cur = Pt(0f, 0f)
    /** Index in [trail] where the current segment (the current level's part of the stroke) starts. */
    private var segStart = 0
    private var stillAt = Pt(0f, 0f)
    private var stillSince = 0L
    private var dwellDone = false
    private var travel = 0f

    val current: Level get() = levels.last()
    val folder: Slot.Folder get() = current.folder
    val origin: Pt get() = current.origin
    val hovered: Slot? get() = if (hover >= 0) folder.slots[hover] else null
    val point: Pt get() = cur
    /** Path of slot indexes from the root to the current folder. */
    val path: List<Int> get() = levels.drop(1).map { it.index }

    /**
     * Finger down at ([x], [y]). [origin] centres the first ring somewhere else (used when a ring
     * is already on screen and the finger lands on one of its items).
     */
    fun down(x: Float, y: Float, t: Long, origin: Pt? = null) {
        val p = Pt(x, y)
        val o = origin ?: p
        levels.clear()
        levels += Level(root, o, -1)
        trail.clear()
        trail += p
        cur = p
        segStart = 0
        stillAt = p
        stillSince = t
        dwellDone = false
        travel = 0f
        updateHover()
    }

    /** Returns true when a folder was entered (for a haptic tick). */
    fun move(x: Float, y: Float, t: Long): Boolean {
        val p = Pt(x, y)
        travel += dist(cur, p)
        cur = p
        trail += p
        if (dist(stillAt, p) > g.still) {
            stillAt = p
            stillSince = t
            dwellDone = false
        }
        var entered = false
        if (!shown) entered = checkTurn()
        updateHover()
        return entered
    }

    /** Call regularly while the finger is down. Returns true when something changed (redraw). */
    fun tick(t: Long): Boolean {
        if (dwellDone || t - stillSince < g.dwellMs) return false
        dwellDone = true
        val s = hovered
        if (s is Slot.Folder) {
            enter(hover, s, cur)
            shown = true
        } else if (!shown) {
            shown = true
        } else {
            return false
        }
        updateHover()
        return true
    }

    fun up(x: Float, y: Float, t: Long): Result {
        if (x != cur.x || y != cur.y) move(x, y, t)
        val s = hovered
        return when {
            s is Slot.App -> Result.Launch(s, path + hover, root.names(path) + s.label)
            s is Slot.Folder -> Result.Open(s, cur, path + hover)
            hover == -1 && levels.size == 1 && !shown && travel < g.minStroke -> Result.Tap(trail.first())
            hover == -1 && shown -> Result.Open(folder, origin, path)
            hover == -1 && levels.size > 1 -> Result.Open(folder, origin, path)
            else -> Result.Cancel
        }
    }

    // ------------------------------------------------------------------------------------------

    /**
     * A sharp turn after a segment pointing at a folder enters that folder at the corner.
     * Looks back along the trail to the point [Geometry.minAfterTurn] behind the finger: if the
     * direction from there to the finger differs enough from the segment's direction, the corner
     * is the segment point farthest from the level's origin.
     */
    private fun checkTurn(): Boolean {
        val o = origin
        var qi = -1
        for (k in trail.size - 2 downTo segStart) {
            if (dist(trail[k], cur) >= g.minAfterTurn) { qi = k; break }
        }
        if (qi < 0) return false
        var ci = segStart
        var best = -1f
        for (k in segStart..qi) {
            val d = dist(o, trail[k])
            if (d > best) { best = d; ci = k }
        }
        val corner = trail[ci]
        if (best < g.minStroke) return false
        val a1 = angle(o, corner)
        if (angleDiff(a1, angle(trail[qi], cur)) < g.turnDeg) return false
        val i = sector(a1)
        val s = folder.slots[i] as? Slot.Folder ?: return false
        enter(i, s, corner)
        segStart = ci
        return true
    }

    private fun enter(index: Int, f: Slot.Folder, at: Pt) {
        levels += Level(f, at, index)
        segStart = trail.size - 1
        hover = -1
    }

    private fun updateHover() {
        hover = if (shown) {
            if (dist(origin, cur) < g.deadZone) -1 else sector(angle(origin, cur))
        } else {
            if (dist(origin, cur) < g.minStroke) -1 else sector(angle(origin, cur))
        }
    }

    companion object {
        fun dist(a: Pt, b: Pt) = hypot(b.x - a.x, b.y - a.y)

        /** Compass angle of a→b in degrees: 0 = up, 90 = right, clockwise. */
        fun angle(a: Pt, b: Pt): Float {
            val deg = Math.toDegrees(atan2((b.x - a.x).toDouble(), (a.y - b.y).toDouble())).toFloat()
            return (deg + 360f) % 360f
        }

        fun angleDiff(a: Float, b: Float): Float {
            val d = abs(a - b) % 360f
            return if (d > 180f) 360f - d else d
        }

        /** Which of the 8 slots a compass angle points at. */
        fun sector(deg: Float): Int = (((deg + 22.5f) % 360f) / 45f).toInt() % Slot.SLOTS
    }
}
