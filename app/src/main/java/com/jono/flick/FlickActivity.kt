package com.jono.flick

import android.app.Activity
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.view.WindowManager
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * The see-through overlay the widget opens. It covers the whole screen with a [FlickView], centred
 * on the widget that was tapped (Android tells us where via the intent's source bounds).
 */
class FlickActivity : Activity(), FlickView.Listener {
    private lateinit var view: FlickView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setDecorFitsSystemWindows(false)
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        start(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        entering = false
        setIntent(intent)
        start(intent)
    }

    /** Set when the remap pip (or sharing a link to Flick) opened us: the chosen spot gets this. */
    private var assignApp: Slot? = null
    private lateinit var root: FrameLayout
    private var panelView: View? = null

    private fun start(i: Intent) {
        assignApp = i.getStringExtra(EXTRA_ASSIGN_SLOT)?.let { Store.slotFromJson(it) }
            ?: i.getStringExtra(EXTRA_ASSIGN_PKG)?.let { Slot.App(it, i.getStringExtra(EXTRA_ASSIGN_LABEL) ?: it) }
        view = FlickView(this, Store.menu(this), this, assign = assignApp != null).apply { carry = assignApp }
        view.warmIcons() // icon bitmaps in the background, ready before the ring shows
        root = FrameLayout(this).apply { addView(view, FrameLayout.LayoutParams(-1, -1)) }
        setContentView(root)
        val (sx, sy) = widgetCentre(i.sourceBounds)
        val path = i.getIntArrayExtra(EXTRA_PATH)?.toList().orEmpty()
        val empty = i.getIntArrayExtra(EXTRA_EMPTY)?.toList()
        WidgetSpot.seenFrom(this, i, plainHub = path.isEmpty() && empty == null && assignApp == null)
        val v = view
        // Source bounds are screen coordinates; convert once this view has its real size and place.
        whenLaidOut(v) {
            if (v !== view) return@whenLaidOut // a newer tap replaced this view
            // Show as soon as the opening animation ends (onEnterAnimationComplete); if the
            // launcher gives none (e.g. we were already open), shortly after.
            v.postDelayed({ v.reveal() }, if (entering) 220L else 0L)
            val loc = IntArray(2)
            v.getLocationOnScreen(loc)
            val at = Recognizer.Pt(sx - loc[0], sy - loc[1])
            when {
                // An empty dot on the widget: offer to put an app there (if it's still empty).
                empty != null && Store.menu(this).at(empty.dropLast(1))?.slots?.getOrNull(empty.last()) == null ->
                    onEmptySpot(empty)
                path.isEmpty() && assignApp == null -> v.arm(at)
                else -> v.openRing(path, at)
            }
        }
    }

    /**
     * Where the tapped widget is, in screen pixels. Launchers sometimes pass no position or an
     * empty one (0,0 — the top-left corner); then use the last good position, else low-centre.
     */
    /** True from creation until the window's opening animation has finished. */
    private var entering = true

    override fun onEnterAnimationComplete() {
        super.onEnterAnimationComplete()
        entering = false
        if (::view.isInitialized) view.reveal()
    }

    private fun widgetCentre(b: Rect?): Pair<Float, Float> {
        val dm = resources.displayMetrics
        val prefs = getSharedPreferences("flick", MODE_PRIVATE)
        val ok = b != null && !b.isEmpty && b.width() > 4 && b.height() > 4 &&
            b.centerX() in 1 until dm.widthPixels && b.centerY() in 1 until dm.heightPixels
        if (ok) {
            prefs.edit().putFloat("anchor_x", b!!.exactCenterX()).putFloat("anchor_y", b.exactCenterY()).apply()
            return b.exactCenterX() to b.exactCenterY()
        }
        return prefs.getFloat("anchor_x", dm.widthPixels / 2f) to prefs.getFloat("anchor_y", dm.heightPixels * 0.7f)
    }

    /** Runs [then] once [v] has been laid out (right away if it already has). */
    private fun whenLaidOut(v: View, then: () -> Unit) {
        if (v.isLaidOut && v.width > 0 && v.height > 0) {
            then()
            return
        }
        v.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
            override fun onLayoutChange(x: View, l: Int, t: Int, r: Int, b: Int, ol: Int, ot: Int, orr: Int, ob: Int) {
                if (x.width == 0 || x.height == 0) return
                x.removeOnLayoutChangeListener(this)
                x.post(then)
            }
        })
    }

    override fun onLaunch(app: Slot, route: List<String>, x: Float, y: Float) {
        WidgetRenderer.setBlank(this, false)
        val opts = ActivityOptions.makeScaleUpAnimation(view, x.toInt(), y.toInt(), 1, 1)
        // Clear the trail now: a slow-starting app can leave this window on screen a while.
        view.visibility = View.INVISIBLE
        val ok = Actions.run(this, app, opts.toBundle())
        if (ok) Store.addNode(this, app)
        if (ok && app is Slot.App) {
            Store.addRecent(this, app.pkg, app.label, route.joinToString(" › "))
            WidgetRenderer.renderAll(this)
        }
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    /** Remap pip: put the app on the chosen spot (an app there is replaced; folders stay). */
    override fun onAssign(path: List<Int>) {
        val app = assignApp ?: return
        val menu = Store.menu(this)
        val folder = menu.at(path.dropLast(1)) ?: return
        val old = folder.slots[path.last()]
        if (old !is Slot.Folder) {
            folder.slots[path.last()] = app
            Store.saveMenu(this, menu)
            WidgetRenderer.renderAll(this)
            val where = menu.describe(path)
            val replaced = old is Slot.App && (app !is Slot.App || old.pkg != app.pkg)
            val msg = "${app.label} → $where" + if (replaced) " (was ${old?.label})" else ""
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }
        onClose()
    }

    /** A stroke ended on an empty spot: offer to put an app there (then open it). */
    override fun onEmptySpot(path: List<Int>): Boolean {
        panelView?.let { root.removeView(it) }
        val menu = Store.menu(this)
        val where = menu.describe(path)
        view.visibility = View.INVISIBLE
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()

        lateinit var panel: FrameLayout
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = getDrawable(R.drawable.panel_bg)
            setPadding(dp(16), dp(16), dp(16), 0)
            isClickable = true // taps inside don't close
        }
        card.addView(TextView(this).apply {
            text = "$where is empty"
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(getColor(R.color.widget_text))
        })
        card.addView(TextView(this).apply {
            text = "Pick an app to put there. It opens right away."
            textSize = 13f
            setTextColor(getColor(R.color.widget_subtext))
            setPadding(0, dp(2), 0, dp(4))
        })
        val newFolder = { _: View ->
            Dialogs.folderName(this, "Folder") { name ->
                val fresh = Store.menu(this)
                val folder = fresh.at(path.dropLast(1))
                if (folder != null && folder.slots[path.last()] == null) {
                    folder.slots[path.last()] = Slot.Folder(name)
                    Store.saveMenu(this, fresh)
                    WidgetRenderer.renderAll(this)
                }
                // Show the new (empty) folder's ring: tap its dots to fill it.
                root.removeView(panel)
                view.useMenu(fresh)
                view.visibility = View.VISIBLE
                view.openRing(path, Recognizer.Pt(view.width / 2f, view.height / 2f))
            }
        }
        val newPerson = { _: View ->
            picking = true
            PickContactActivity.start(this) { p ->
                picking = false
                if (p != null) {
                    val fresh = Store.menu(this)
                    val folder = fresh.at(path.dropLast(1))
                    if (folder != null && folder.slots[path.last()] == null) {
                        folder.slots[path.last()] = p
                        Store.saveMenu(this, fresh)
                        WidgetRenderer.renderAll(this)
                        Toast.makeText(this, "${Actions.actionVerb(p.action)} ${p.name} → $where", Toast.LENGTH_SHORT).show()
                    }
                }
                onClose()
            }
        }
        val newLink = { _: View ->
            picking = true // its picture picker may cover us
            Dialogs.link(this, onCancel = { picking = false }) { l ->
                picking = false
                val fresh = Store.menu(this)
                val folder = fresh.at(path.dropLast(1))
                if (folder != null && folder.slots[path.last()] == null) {
                    folder.slots[path.last()] = l
                    Store.saveMenu(this, fresh)
                    WidgetRenderer.renderAll(this)
                    Toast.makeText(this, "${l.name} → $where", Toast.LENGTH_SHORT).show()
                }
                onClose()
            }
        }
        val picker = AppPicker(this, newFolder, newPerson, newLink) { app, _ ->
            val fresh = Store.menu(this)
            val folder = fresh.at(path.dropLast(1))
            if (folder != null && folder.slots[path.last()] == null) {
                folder.slots[path.last()] = app
                Store.saveMenu(this, fresh)
                Toast.makeText(this, "${app.label} → $where", Toast.LENGTH_SHORT).show()
            }
            val c = card
            onLaunch(app, fresh.names(path.dropLast(1)), c.width / 2f, c.top.toFloat())
        }.apply { this.menu = menu }
        card.addView(picker, LinearLayout.LayoutParams(-1, (resources.displayMetrics.heightPixels * 0.62f).toInt()))

        panel = FrameLayout(this).apply {
            setBackgroundColor(0x88000000.toInt())
            setOnClickListener { onClose() }
            addView(card, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply {
                setMargins(dp(8), 0, dp(8), dp(8))
            })
            // Stay above the navigation bar and the keyboard.
            setOnApplyWindowInsetsListener { v, insets ->
                val b = insets.getInsets(WindowInsets.Type.ime() or WindowInsets.Type.navigationBars()).bottom
                v.setPadding(0, 0, 0, b)
                insets
            }
        }
        root.addView(panel, FrameLayout.LayoutParams(-1, -1))
        panelView = panel
        panel.requestApplyInsets()
        return true
    }

    override fun onBackPressed() {
        onClose()
    }

    override fun onClose() {
        // Bring the widget's buttons back now, not when Android gets round to destroying us.
        WidgetRenderer.setBlank(this, false)
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    /** While the ring is on screen, blank the widget under it so its icons don't show twice. */
    override fun onRingShown(shown: Boolean) = WidgetRenderer.setBlank(this, shown)

    // While this screen is up, the touch spot steps aside (it would float over the ring).
    override fun onStart() {
        super.onStart()
        SpotService.cover(true)
    }

    override fun keepOpen(on: Boolean) {
        picking = on
    }

    override fun onDestroy() {
        WidgetRenderer.setBlank(this, false)
        super.onDestroy()
    }

    /** True while the contact picker is on top (so leaving doesn't close us). */
    private var picking = false

    // Leaving (home button, another app on top): close, so the next widget tap starts fresh.
    override fun onStop() {
        super.onStop()
        SpotService.cover(false)
        if (!picking) WidgetRenderer.setBlank(this, false)
        if (!isFinishing && !picking) finish()
    }

    companion object {
        const val EXTRA_PATH = "path"
        private const val EXTRA_EMPTY = "empty"

        /** An empty spot tapped on the widget: straight to the app picker for it. */
        fun emptyIntent(ctx: Context, path: List<Int>): Intent =
            intent(ctx).putExtra(EXTRA_EMPTY, path.toIntArray())
        private const val EXTRA_ASSIGN_PKG = "assign_pkg"
        private const val EXTRA_ASSIGN_LABEL = "assign_label"
        private const val EXTRA_ASSIGN_SLOT = "assign_slot"

        /** Opens the top ring to choose where [slot] goes (e.g. a link shared to Flick). */
        fun assignSlotIntent(ctx: Context, slot: Slot): Intent =
            intent(ctx).putExtra(EXTRA_ASSIGN_SLOT, Store.slotToJson(slot))

        /** Opens the top ring to choose where [pkg] goes (the widget's remap pip). */
        fun assignIntent(ctx: Context, pkg: String, label: String): Intent =
            intent(ctx).putExtra(EXTRA_ASSIGN_PKG, pkg).putExtra(EXTRA_ASSIGN_LABEL, label)

        fun intent(ctx: Context, path: List<Int> = emptyList()): Intent =
            Intent(ctx, FlickActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                .apply { if (path.isNotEmpty()) putExtra(EXTRA_PATH, path.toIntArray()) }
    }
}
