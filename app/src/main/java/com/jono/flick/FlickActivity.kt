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
        setIntent(intent)
        start(intent)
    }

    /** Set when a widget's remap pip opened us: the tapped spot gets this app. */
    private var assignApp: Slot.App? = null
    private lateinit var root: FrameLayout
    private var panelView: View? = null

    private fun start(i: Intent) {
        assignApp = i.getStringExtra(EXTRA_ASSIGN_PKG)?.let { Slot.App(it, i.getStringExtra(EXTRA_ASSIGN_LABEL) ?: it) }
        view = FlickView(this, Store.menu(this), this, assign = assignApp != null).apply { carry = assignApp }
        root = FrameLayout(this).apply { addView(view, FrameLayout.LayoutParams(-1, -1)) }
        setContentView(root)
        val bounds: Rect? = i.sourceBounds
        val dm = resources.displayMetrics
        val sx = bounds?.exactCenterX() ?: (dm.widthPixels / 2f)
        val sy = bounds?.exactCenterY() ?: (dm.heightPixels * 0.7f)
        val path = i.getIntArrayExtra(EXTRA_PATH)?.toList().orEmpty()
        val empty = i.getIntArrayExtra(EXTRA_EMPTY)?.toList()
        // Source bounds are screen coordinates; convert once the view is laid out.
        view.post {
            val loc = IntArray(2)
            view.getLocationOnScreen(loc)
            val at = Recognizer.Pt(sx - loc[0], sy - loc[1])
            when {
                // An empty dot on the widget: offer to put an app there (if it's still empty).
                empty != null && Store.menu(this).at(empty.dropLast(1))?.slots?.getOrNull(empty.last()) == null ->
                    onEmptySpot(empty)
                path.isEmpty() && assignApp == null -> view.arm(at)
                else -> view.openRing(path, at)
            }
        }
    }

    override fun onLaunch(app: Slot.App, route: List<String>, x: Float, y: Float) {
        val launch = packageManager.getLaunchIntentForPackage(app.pkg)
        if (launch == null) {
            Toast.makeText(this, "${app.label} isn't installed", Toast.LENGTH_SHORT).show()
            onClose()
            return
        }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val opts = ActivityOptions.makeScaleUpAnimation(view, x.toInt(), y.toInt(), 1, 1)
        runCatching { startActivity(launch, opts.toBundle()) }
        Store.addRecent(this, app.pkg, app.label, route.joinToString(" › "))
        WidgetRenderer.renderAll(this)
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
            val msg = if (old is Slot.App && old.pkg != app.pkg) "${app.label} → $where (was ${old.label})"
            else "${app.label} → $where"
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
        val picker = AppPicker(this, newFolder) { app, _ ->
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
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    // Leaving (home button, another app on top): close, so the next widget tap starts fresh.
    override fun onStop() {
        super.onStop()
        if (!isFinishing) finish()
    }

    companion object {
        const val EXTRA_PATH = "path"
        private const val EXTRA_EMPTY = "empty"

        /** An empty spot tapped on the widget: straight to the app picker for it. */
        fun emptyIntent(ctx: Context, path: List<Int>): Intent =
            intent(ctx).putExtra(EXTRA_EMPTY, path.toIntArray())
        private const val EXTRA_ASSIGN_PKG = "assign_pkg"
        private const val EXTRA_ASSIGN_LABEL = "assign_label"

        /** Opens the top ring to choose where [pkg] goes (the widget's remap pip). */
        fun assignIntent(ctx: Context, pkg: String, label: String): Intent =
            intent(ctx).putExtra(EXTRA_ASSIGN_PKG, pkg).putExtra(EXTRA_ASSIGN_LABEL, label)

        fun intent(ctx: Context, path: List<Int> = emptyList()): Intent =
            Intent(ctx, FlickActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                .apply { if (path.isNotEmpty()) putExtra(EXTRA_PATH, path.toIntArray()) }
    }
}
