package com.jono.flick

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Matrix
import android.graphics.Shader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.Drawable

/** App icons (the real installed icon) and folder icons (a 2×2 preview of what's inside). */
object Icons {
    // Synchronized: the widget is drawn on a worker thread while the ring draws on the main one.
    private val drawables: MutableMap<String, Drawable?> = java.util.Collections.synchronizedMap(HashMap())

    fun appDrawable(ctx: Context, pkg: String): Drawable? =
        drawables.getOrPut(pkg) { runCatching { ctx.packageManager.getApplicationIcon(pkg) }.getOrNull() }

    private val photos: MutableMap<String, Bitmap?> = java.util.Collections.synchronizedMap(HashMap())

    /** A copy to draw with (bounds are per-copy, so two threads can draw the same icon safely). */
    private fun drawCopy(ctx: Context, pkg: String): Drawable? {
        val d = appDrawable(ctx, pkg) ?: return null
        return d.constantState?.newDrawable(ctx.resources)?.mutate() ?: d
    }

    // ---------------------------------------------------------------- ring bitmaps
    // The ring draws each item many times a second while you move. Drawing an app icon, and even
    // more a folder's tiny radial of 8 icons, is slow on an older phone, so items are drawn once
    // into bitmaps (in the background, as soon as Flick opens) and the ring just copies those.

    private val ring: MutableMap<Slot, Bitmap> = java.util.Collections.synchronizedMap(java.util.IdentityHashMap())
    @Volatile private var ringPx = 0

    /** The ready-made bitmap for [s] at the ring's size, if it's been drawn yet. */
    fun ringBitmap(s: Slot, px: Int): Bitmap? = if (px == ringPx) ring[s] else null

    /**
     * Draws the bitmaps for the top level and the level under it, plus [extra] (e.g. an item
     * being placed). Runs on a background thread; [done] is called (on that thread) when finished.
     */
    fun warmRing(ctx: Context, root: Slot.Folder, px: Int, extra: Slot? = null, done: () -> Unit = {}) {
        if (px != ringPx) {
            ring.clear()
            ringPx = px
        }
        val items = mutableListOf<Slot>()
        extra?.let { items += it }
        root.slots.filterNotNull().forEach { s ->
            items += s
            if (s is Slot.Folder) items += s.slots.filterNotNull()
        }
        for (s in items) {
            if (ring.containsKey(s)) continue
            runCatching { ring[s] = bitmap(ctx, s, px) }
        }
        done()
    }

    /** The menu changed (moved, renamed, removed): redraw the bitmaps. */
    fun clearRing() = ring.clear()

    fun clear() {
        drawables.clear()
        photos.clear()
        ring.clear()
    }

    private fun photo(ctx: Context, name: String): Bitmap? = photos.getOrPut(name) {
        runCatching { BitmapFactory.decodeFile(PickContactActivity.photoFile(ctx, name).path) }.getOrNull()
    }

    /** A person: their photo in a circle (or their initials), with the calling/texting app's icon on it. */
    private fun drawContact(ctx: Context, c: Canvas, p: Slot.Contact, x: Float, y: Float, size: Float, badge: Boolean) =
        drawPictured(ctx, c, p.name, p.photo, if (badge) Actions.actionPackage(ctx, p.action) else null, x, y, size)

    private fun drawLink(ctx: Context, c: Canvas, l: Slot.Link, x: Float, y: Float, size: Float, badge: Boolean) =
        drawPictured(ctx, c, l.name, l.photo, if (badge) Actions.linkPackage(ctx, l) else null, x, y, size)

    /** A picture in a circle (or initials), with the app that opens it as a small badge. */
    private fun drawPictured(
        ctx: Context, c: Canvas, name: String, photoName: String?, badgePkg: String?, x: Float, y: Float, size: Float,
    ) {
        val cx = x + size / 2
        val cy = y + size / 2
        val bmp = photoName?.let { photo(ctx, it) }
        if (bmp != null) {
            val shader = BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            val scale = size / minOf(bmp.width, bmp.height)
            shader.setLocalMatrix(Matrix().apply {
                setScale(scale, scale)
                postTranslate(cx - bmp.width * scale / 2, cy - bmp.height * scale / 2)
            })
            c.drawCircle(cx, cy, size / 2, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.shader = shader })
        } else {
            c.drawCircle(cx, cy, size / 2, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ctx.getColor(R.color.btn_bg) })
            val initials = name.split(" ").filter { it.isNotBlank() }.take(2).joinToString("") { it.take(1).uppercase() }
            val t = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = ctx.getColor(R.color.btn_text)
                textAlign = Paint.Align.CENTER
                textSize = size * (if (initials.length > 1) 0.38f else 0.46f)
                isFakeBoldText = true
            }
            c.drawText(initials.ifEmpty { "?" }, cx, cy + t.textSize / 3, t)
        }
        val pkg = badgePkg ?: return
        val b = size * 0.42f
        val bx = x + size - b
        val by = y + size - b
        c.drawCircle(bx + b / 2, by + b / 2, b / 2 + size * 0.03f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ctx.getColor(R.color.widget_bg) })
        drawCopy(ctx, pkg)?.let {
            it.setBounds(bx.toInt(), by.toInt(), (bx + b).toInt(), (by + b).toInt())
            it.draw(c)
        }
    }

    /** Square bitmap of [px] for a slot. Folders: a tinted disc with up to 4 of their items inside. */
    fun bitmap(ctx: Context, slot: Slot, px: Int): Bitmap {
        val b = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        draw(ctx, Canvas(b), slot, 0f, 0f, px.toFloat())
        return b
    }

    /** Draws a slot's icon into the square at ([x], [y]) of side [size]. */
    fun draw(ctx: Context, c: Canvas, slot: Slot, x: Float, y: Float, size: Float) {
        when (slot) {
            is Slot.App -> drawCopy(ctx, slot.pkg)?.let {
                it.setBounds(x.toInt(), y.toInt(), (x + size).toInt(), (y + size).toInt())
                it.draw(c)
            } ?: run {
                val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ctx.getColor(R.color.btn_bg) }
                c.drawCircle(x + size / 2, y + size / 2, size / 2, p)
            }
            is Slot.Contact -> drawContact(ctx, c, slot, x, y, size, badge = true)
            is Slot.Link -> drawLink(ctx, c, slot, x, y, size, badge = true)
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
                        is Slot.App -> drawCopy(ctx, s.pkg)?.let {
                            it.setBounds((ix - mini / 2).toInt(), (iy - mini / 2).toInt(), (ix + mini / 2).toInt(), (iy + mini / 2).toInt())
                            it.draw(c)
                        }
                        is Slot.Folder -> {
                            ink.alpha = 150
                            c.drawCircle(ix, iy, mini * 0.42f, ink)
                        }
                        is Slot.Contact -> drawContact(ctx, c, s, ix - mini / 2, iy - mini / 2, mini, badge = false)
                        is Slot.Link -> drawLink(ctx, c, s, ix - mini / 2, iy - mini / 2, mini, badge = false)
                        null -> {} // empty spots aren't shown in the preview
                    }
                }
            }
        }
    }
}
