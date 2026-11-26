package com.jono.flick

import android.content.Context
import android.graphics.Rect
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Keeps the touch spot exactly over the widget's centre (setting). Every tap on the widget tells
 * us where the tapped part is on screen; a ring layout's cells sit in a 3×3 grid around the
 * centre, so any cell's position gives the centre's. So the spot follows the widget whenever you
 * use it (including after you move or resize the widget).
 */
object WidgetSpot {
    /** Grid row/column of each compass slot in the ring layouts (the centre is row 1, column 1). */
    private val GRID = mapOf(
        7 to (0 to 0), 0 to (0 to 1), 1 to (0 to 2),
        6 to (1 to 0), 2 to (1 to 2),
        5 to (2 to 0), 4 to (2 to 1), 3 to (2 to 2),
    )

    /**
     * A tap on the widget: [cell] is the compass slot of a ring cell, or [HUB] for the centre
     * itself; [b] is the tapped view's rectangle on screen.
     */
    fun seen(ctx: Context, b: Rect?, cell: Int) {
        if (b == null || b.isEmpty || b.width() < 8 || b.height() < 8) return
        val d = ctx.resources.displayMetrics.density
        val cx: Float
        val cy: Float
        val sizePx: Float
        if (cell == HUB) {
            cx = b.exactCenterX()
            cy = b.exactCenterY()
            sizePx = min(b.width(), b.height()).toFloat()
        } else {
            val (r, c) = GRID[cell] ?: return
            cx = b.exactCenterX() + (1 - c) * b.width()
            cy = b.exactCenterY() + (1 - r) * b.height()
            sizePx = min(b.width(), b.height()) - 8 * d // the centre has a 4 dp margin
        }
        val dm = ctx.resources.displayMetrics
        if (cx < 0 || cy < 0 || cx > dm.widthPixels || cy > dm.heightPixels) return
        ctx.getSharedPreferences("flick", Context.MODE_PRIVATE).edit()
            .putFloat("hub_x", cx).putFloat("hub_y", cy).putFloat("hub_size", sizePx).apply()
        if (Store.spotAuto(ctx)) snap(ctx)
    }

    /** Puts the spot on the widget's centre as last seen. Returns false if it's never been seen. */
    fun snap(ctx: Context): Boolean {
        val p = ctx.getSharedPreferences("flick", Context.MODE_PRIVATE)
        if (!p.contains("hub_x")) return false
        val size = p.getFloat("hub_size", 0f).coerceAtLeast(24 * ctx.resources.displayMetrics.density)
        val x = (p.getFloat("hub_x", 0f) - size / 2).roundToInt()
        val y = (p.getFloat("hub_y", 0f) - size / 2).roundToInt()
        val sizeDp = (size / ctx.resources.displayMetrics.density).roundToInt()
        if (Store.spotX(ctx) == x && Store.spotY(ctx) == y && Store.spotSizeDp(ctx) == sizeDp) return true
        Store.setSpotSizeDp(ctx, sizeDp)
        Store.setSpotPos(ctx, x, y)
        SpotService.sync(ctx)
        return true
    }

    const val HUB = -1
    const val EXTRA_CELL = "ring_cell"

    /** From an intent a widget tap started: which cell (or the centre, for a plain hub tap). */
    fun seenFrom(ctx: Context, i: android.content.Intent, plainHub: Boolean) {
        val cell = i.getIntExtra(EXTRA_CELL, Int.MIN_VALUE)
        when {
            cell != Int.MIN_VALUE -> seen(ctx, i.sourceBounds, cell)
            plainHub -> seen(ctx, i.sourceBounds, HUB)
        }
    }
}
