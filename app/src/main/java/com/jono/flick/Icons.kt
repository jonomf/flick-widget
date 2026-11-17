package com.jono.flick

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.Drawable

/** App icons (the real installed icon) and folder icons (a 2×2 preview of what's inside). */
object Icons {
    private val drawables = HashMap<String, Drawable?>()

    fun appDrawable(ctx: Context, pkg: String): Drawable? =
        drawables.getOrPut(pkg) { runCatching { ctx.packageManager.getApplicationIcon(pkg) }.getOrNull() }

    fun clear() = drawables.clear()

    /** Square bitmap of [px] for a slot. Folders: a tinted disc with up to 4 of their items inside. */
    fun bitmap(ctx: Context, slot: Slot, px: Int): Bitmap {
        val b = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        draw(ctx, Canvas(b), slot, 0f, 0f, px.toFloat())
        return b
    }

    /** Draws a slot's icon into the square at ([x], [y]) of side [size]. */
    fun draw(ctx: Context, c: Canvas, slot: Slot, x: Float, y: Float, size: Float) {
        when (slot) {
            is Slot.App -> appDrawable(ctx, slot.pkg)?.let {
                it.setBounds(x.toInt(), y.toInt(), (x + size).toInt(), (y + size).toInt())
                it.draw(c)
            } ?: run {
                val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ctx.getColor(R.color.btn_bg) }
                c.drawCircle(x + size / 2, y + size / 2, size / 2, p)
            }
            is Slot.Folder -> {
                val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ctx.getColor(R.color.folder_bg) }
                c.drawCircle(x + size / 2, y + size / 2, size / 2, bg)
                val inner = slot.slots.filterNotNull().take(4)
                val cell = size * 0.30f
                val gap = size * 0.04f
                val start = x + (size - 2 * cell - gap) / 2
                val top = y + (size - 2 * cell - gap) / 2
                inner.forEachIndexed { i, s ->
                    val cx = start + (i % 2) * (cell + gap)
                    val cy = top + (i / 2) * (cell + gap)
                    if (s is Slot.App) {
                        appDrawable(ctx, s.pkg)?.let {
                            it.setBounds(cx.toInt(), cy.toInt(), (cx + cell).toInt(), (cy + cell).toInt())
                            it.draw(c)
                        }
                    } else {
                        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ctx.getColor(R.color.btn_text); alpha = 140 }
                        c.drawCircle(cx + cell / 2, cy + cell / 2, cell / 2.4f, p)
                    }
                }
            }
        }
    }
}
