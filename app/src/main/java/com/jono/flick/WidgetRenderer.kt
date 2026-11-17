package com.jono.flick

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.util.SizeF
import android.view.View
import android.widget.RemoteViews

class FlickWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) = WidgetRenderer.renderAll(ctx)

    override fun onAppWidgetOptionsChanged(ctx: Context, mgr: AppWidgetManager, id: Int, opts: Bundle) =
        WidgetRenderer.renderAll(ctx)
}

/**
 * Draws the widget at every size. The hub (the radial symbol) always opens the flick overlay.
 * Bigger sizes add the top-level items as one-tap shortcuts (ring sizes put each one in its
 * compass position, so the widget doubles as a map of the gesture), then recent launches.
 */
object WidgetRenderer {

    /**
     * Each size's layout and contents. [cells]: shortcut cells; ring = cells sit at their compass
     * positions (all 8), otherwise the first non-empty top-level items in order. Keep the argument
     * order fixed: tools/check_layouts.py reads these lines.
     */
    enum class Kind(
        val layout: Int, val size: SizeF, val label: String,
        val cells: Int, val ring: Boolean, val labels: Boolean, val recents: Int,
    ) {
        TILE(R.layout.widget_tile, SizeF(50f, 50f), "hub", cells = 0, ring = false, labels = false, recents = 0),
        ROW_S(R.layout.widget_row_s, SizeF(150f, 50f), "hub + 3 items", cells = 3, ring = false, labels = false, recents = 0),
        ROW(R.layout.widget_row, SizeF(220f, 50f), "hub + 5 items", cells = 5, ring = false, labels = false, recents = 0),
        ROW_L(R.layout.widget_row_l, SizeF(320f, 50f), "hub + 8 items", cells = 8, ring = false, labels = false, recents = 0),
        COLUMN(R.layout.widget_column, SizeF(50f, 120f), "hub + 3 items stacked", cells = 3, ring = false, labels = false, recents = 0),
        COLUMN_L(R.layout.widget_column_l, SizeF(50f, 205f), "hub + 5 items stacked", cells = 5, ring = false, labels = false, recents = 0),
        RING(R.layout.widget_ring, SizeF(150f, 120f), "ring of 8 around the hub", cells = 8, ring = true, labels = false, recents = 0),
        WIDE(R.layout.widget_wide, SizeF(250f, 120f), "hub + 8 labelled items", cells = 8, ring = false, labels = true, recents = 0),
        WIDE_L(R.layout.widget_wide, SizeF(340f, 120f), "hub + 8 labelled items", cells = 8, ring = false, labels = true, recents = 0),
        TALL(R.layout.widget_tall, SizeF(150f, 205f), "ring + 3 recent", cells = 8, ring = true, labels = false, recents = 3),
        RING_L(R.layout.widget_ring_l, SizeF(250f, 205f), "labelled ring", cells = 8, ring = true, labels = true, recents = 0),
        XL_WIDE(R.layout.widget_xl_wide, SizeF(340f, 205f), "labelled ring | 4 recent", cells = 8, ring = true, labels = true, recents = 4),
        XL(R.layout.widget_xl, SizeF(260f, 270f), "labelled ring + 3 recent", cells = 8, ring = true, labels = true, recents = 3),
    }

    private val CELL = intArrayOf(R.id.c0, R.id.c1, R.id.c2, R.id.c3, R.id.c4, R.id.c5, R.id.c6, R.id.c7)
    private val CELL_ICON = intArrayOf(R.id.c0i, R.id.c1i, R.id.c2i, R.id.c3i, R.id.c4i, R.id.c5i, R.id.c6i, R.id.c7i)
    private val CELL_TEXT = intArrayOf(R.id.c0t, R.id.c1t, R.id.c2t, R.id.c3t, R.id.c4t, R.id.c5t, R.id.c6t, R.id.c7t)
    private val REC = intArrayOf(R.id.r0, R.id.r1, R.id.r2, R.id.r3)
    private val REC_ICON = intArrayOf(R.id.r0i, R.id.r1i, R.id.r2i, R.id.r3i)
    private val REC_TITLE = intArrayOf(R.id.r0t, R.id.r1t, R.id.r2t, R.id.r3t)
    private val REC_SUB = intArrayOf(R.id.r0s, R.id.r1s, R.id.r2s, R.id.r3s)

    private const val ICON_PX = 96

    fun renderAll(ctx: Context) {
        val mgr = AppWidgetManager.getInstance(ctx)
        val ids = mgr.getAppWidgetIds(ComponentName(ctx, FlickWidget::class.java))
        if (ids.isEmpty()) return
        Icons.clear()
        val menu = Store.menu(ctx)
        val recents = Store.recents(ctx)
        // One bitmap per item, shared by every size's layout (keeps the update small).
        val bitmaps = HashMap<Slot, Bitmap>()
        fun bmp(s: Slot) = bitmaps.getOrPut(s) { Icons.bitmap(ctx, s, ICON_PX) }
        val recentBitmaps = HashMap<String, Bitmap>()
        fun recentBmp(pkg: String) = recentBitmaps.getOrPut(pkg) { Icons.bitmap(ctx, Slot.App(pkg, ""), ICON_PX) }

        val views = RemoteViews(Kind.values().associate { k ->
            k.size to fill(ctx, k, menu, recents, ::bmp, ::recentBmp)
        })
        mgr.updateAppWidget(ids, views)
    }

    private fun fill(
        ctx: Context, k: Kind, menu: Slot.Folder, recents: List<Store.Recent>,
        bmp: (Slot) -> Bitmap, recentBmp: (String) -> Bitmap,
    ): RemoteViews {
        val rv = RemoteViews(ctx.packageName, k.layout)
        val hubIntent = mutable(ctx, 1, FlickActivity.intent(ctx))
        rv.setOnClickPendingIntent(R.id.hub, hubIntent)

        // Which top-level slot each cell shows.
        val slotFor: List<Int?> = if (k.ring) {
            (0 until Slot.SLOTS).toList()
        } else {
            val filled = (0 until Slot.SLOTS).filter { menu.slots[it] != null }
            (0 until k.cells).map { filled.getOrNull(it) }
        }
        for (c in 0 until k.cells) {
            val i = slotFor[c]
            val s = if (i != null) menu.slots[i] else null
            if (i == null || s == null) {
                // Empty compass spot keeps its place in the ring; elsewhere the cell goes away.
                rv.setViewVisibility(CELL[c], if (k.ring) View.INVISIBLE else View.GONE)
                continue
            }
            rv.setViewVisibility(CELL[c], View.VISIBLE)
            rv.setImageViewBitmap(CELL_ICON[c], bmp(s))
            rv.setContentDescription(CELL[c], s.label)
            if (k.labels) rv.setTextViewText(CELL_TEXT[c], s.label)
            val pi = when (s) {
                is Slot.App -> PendingIntent.getActivity(
                    ctx, 100 + i, LaunchActivity.intent(ctx, s.pkg, s.label, ""),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
                is Slot.Folder -> mutable(ctx, 200 + i, FlickActivity.intent(ctx, listOf(i)))
            }
            rv.setOnClickPendingIntent(CELL[c], pi)
        }

        for (r in 0 until k.recents) {
            val item = recents.getOrNull(r)
            if (item == null) {
                rv.setViewVisibility(REC[r], View.GONE)
                continue
            }
            rv.setViewVisibility(REC[r], View.VISIBLE)
            rv.setImageViewBitmap(REC_ICON[r], recentBmp(item.pkg))
            rv.setTextViewText(REC_TITLE[r], item.label)
            rv.setTextViewText(REC_SUB[r], item.route.ifEmpty { "Top level" })
            rv.setOnClickPendingIntent(
                REC[r],
                PendingIntent.getActivity(
                    ctx, 300 + r, LaunchActivity.intent(ctx, item.pkg, item.label, item.route),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
        }

        // Update symbol when the Widget Updater reports a newer build. Tap: update.
        val updater = if (UpdateStatus.available(ctx)) UpdateStatus.updaterIntent(ctx) else null
        if (updater != null) {
            rv.setViewVisibility(R.id.update, View.VISIBLE)
            rv.setOnClickPendingIntent(
                R.id.update,
                PendingIntent.getActivity(ctx, 4, updater, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE),
            )
        } else {
            rv.setViewVisibility(R.id.update, View.GONE)
        }
        return rv
    }

    /**
     * Mutable so the launcher can attach where on screen the tap was (source bounds), which is
     * how the overlay centres itself on the widget. Explicit intent to our own activity.
     */
    private fun mutable(ctx: Context, code: Int, i: Intent): PendingIntent =
        PendingIntent.getActivity(ctx, code, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)

    /** Same rule Android uses to pick a layout, so the setup screen can say which one you'll get. */
    fun bestFit(w: Float, h: Float): Kind {
        val fitting = Kind.values().filter { it.size.width <= w && it.size.height <= h }
        return fitting.minByOrNull {
            val dw = w - it.size.width
            val dh = h - it.size.height
            dw * dw + dh * dh
        } ?: Kind.TILE
    }

    fun describeSizes(ctx: Context): String {
        val mgr = AppWidgetManager.getInstance(ctx)
        return mgr.getAppWidgetIds(ComponentName(ctx, FlickWidget::class.java)).joinToString("\n") { id ->
            val o = mgr.getAppWidgetOptions(id)
            val w = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)
            val h = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT)
            val k = bestFit(w.toFloat(), h.toFloat())
            "• ${w}×${h} dp → ${k.name} (${k.label})"
        }.ifEmpty { "No Flick widget on your home screen yet." }
    }
}
