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
                // A tiny radial menu: each item in its compass spot around a centre dot,
                // empty spots as faint dots, sub-folders as small discs.
                val cx = x + size / 2
                val cy = y + size / 2
                val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ctx.getColor(R.color.folder_bg) }
                c.drawCircle(cx, cy, size / 2, bg)
                val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ctx.getColor(R.color.btn_text) }
                ink.alpha = 170
                c.drawCircle(cx, cy, size * 0.05f, ink)
                val r = size * 0.33f
                val mini = size * 0.22f
                for (i in 0 until Slot.SLOTS) {
                    val a = Math.toRadians(i * 45.0)
                    val ix = cx + (r * Math.sin(a)).toFloat()
                    val iy = cy - (r * Math.cos(a)).toFloat()
                    when (val s = slot.slots[i]) {
                        is Slot.App -> appDrawable(ctx, s.pkg)?.let {
                            it.setBounds((ix - mini / 2).toInt(), (iy - mini / 2).toInt(), (ix + mini / 2).toInt(), (iy + mini / 2).toInt())
                            it.draw(c)
                        }
                        is Slot.Folder -> {
                            ink.alpha = 150
                            c.drawCircle(ix, iy, mini * 0.42f, ink)
                        }
                        null -> {
                            ink.alpha = 70
                            c.drawCircle(ix, iy, size * 0.025f, ink)
                        }
                    }
                }
            }
        }
    }
}
