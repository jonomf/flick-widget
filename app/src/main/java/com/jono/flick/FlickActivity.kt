package com.jono.flick

import android.app.Activity
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.view.WindowManager
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
        view = FlickView(this, Store.menu(this), this)
        setContentView(view)
        start(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        view = FlickView(this, Store.menu(this), this)
        setContentView(view)
        start(intent)
    }

    private fun start(i: Intent) {
        val bounds: Rect? = i.sourceBounds
        val dm = resources.displayMetrics
        val sx = bounds?.exactCenterX() ?: (dm.widthPixels / 2f)
        val sy = bounds?.exactCenterY() ?: (dm.heightPixels * 0.7f)
        val path = i.getIntArrayExtra(EXTRA_PATH)?.toList().orEmpty()
        // Source bounds are screen coordinates; convert once the view is laid out.
        view.post {
            val loc = IntArray(2)
            view.getLocationOnScreen(loc)
            val at = Recognizer.Pt(sx - loc[0], sy - loc[1])
            if (path.isEmpty()) view.arm(at) else view.openRing(path, at)
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

        fun intent(ctx: Context, path: List<Int> = emptyList()): Intent =
            Intent(ctx, FlickActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                .apply { if (path.isNotEmpty()) putExtra(EXTRA_PATH, path.toIntArray()) }
    }
}
