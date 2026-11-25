package com.jono.flick

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager

/**
 * The touch spot: a small Flick button Flick draws on screen itself (Display over other apps),
 * kept running by a quiet foreground service so there's never an app start. Put a finger on it
 * and draw straight away: the touch goes straight into the ring, in one motion, no tap first.
 *
 * Two windows: the small spot (gets the finger), and a full-screen layer added the moment a
 * finger lands, where the ring is drawn. The spot keeps receiving that finger's movement (a
 * touch belongs to the window it started in) and passes it on. If the stroke ends with the ring
 * open for tapping, the full-screen layer starts taking touches itself until it closes.
 */
class SpotService : Service(), FlickView.Listener {
    private lateinit var wm: WindowManager
    private val main = Handler(Looper.getMainLooper())
    private var spot: SpotView? = null
    private var spotParams: WindowManager.LayoutParams? = null
    private var layer: FlickView? = null
    private var layerParams: WindowManager.LayoutParams? = null
    private var moving = false
    private var homeShown = true

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WindowManager::class.java)
        startForeground(NOTE_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        registerReceiver(screen, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!Store.spotOn(this) || !Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_MOVE -> moving = true
            ACTION_OFF -> {
                Store.setSpotOn(this, false)
                stopSelf()
                return START_NOT_STICKY
            }
        }
        showSpot()
        startHomeWatch()
        return START_STICKY
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        runCatching { unregisterReceiver(screen) }
        closeLayer()
        spot?.let { runCatching { wm.removeView(it) } }
        spot = null
        super.onDestroy()
    }

    // ---------------------------------------------------------------- the spot

    private fun dp(v: Float) = v * resources.displayMetrics.density

    private fun showSpot() {
        val size = dp(Store.spotSizeDp(this).toFloat()).toInt()
        val dm = resources.displayMetrics
        val x = Store.spotX(this).takeIf { it >= 0 } ?: (dm.widthPixels - size - dp(16f).toInt())
        val y = Store.spotY(this).takeIf { it >= 0 } ?: (dm.heightPixels - size - dp(120f).toInt())
        val p = (spotParams ?: WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        )).apply {
            width = size
            height = size
            gravity = Gravity.TOP or Gravity.START
            this.x = x.coerceIn(0, dm.widthPixels - size)
            this.y = y.coerceIn(0, dm.heightPixels - size)
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        spotParams = p
        val v = spot
        if (v == null) {
            val nv = SpotView(this)
            spot = nv
            runCatching { wm.addView(nv, p) }
        } else {
            v.invalidate()
            runCatching { wm.updateViewLayout(v, p) }
        }
        updateSpotVisibility()
    }

    private fun updateSpotVisibility() {
        spot?.visibility = if (homeShown || moving || layer != null) View.VISIBLE else View.GONE
    }

    /** Spot centre, in screen pixels. */
    private fun spotCentre(): Recognizer.Pt {
        val p = spotParams ?: return Recognizer.Pt(0f, 0f)
        return Recognizer.Pt(p.x + p.width / 2f, p.y + p.height / 2f)
    }

    private inner class SpotView(ctx: Context) : View(ctx) {
        private val icon = ctx.getDrawable(R.drawable.ic_hub)
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(2f)
            color = Color.WHITE
        }
        private var downRaw = Recognizer.Pt(0f, 0f)
        private var startPos = Pair(0, 0)

        override fun onDraw(c: Canvas) {
            val pad = dp(4f).toInt()
            icon?.setBounds(pad, pad, width - pad, height - pad)
            icon?.alpha = if (moving) 255 else 190
            icon?.draw(c)
            if (moving) c.drawCircle(width / 2f, height / 2f, width / 2f - dp(1.5f), ring)
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (moving) return move(e)
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    val l = openLayer() ?: return true
                    l.beginFromSpot(toLayer(l, spotCentre()), raw(l, e).x, raw(l, e).y, e.eventTime)
                }
                else -> layer?.let { l ->
                    val copy = MotionEvent.obtain(e)
                    val off = layerOffset(l)
                    copy.offsetLocation(e.rawX - e.x - off.x, e.rawY - e.y - off.y)
                    l.onTouchEvent(copy)
                    copy.recycle()
                    if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) {
                        afterStroke()
                    }
                }
            }
            return true
        }

        /** Placement mode: drag the spot where you want it; letting go saves it. */
        private fun move(e: MotionEvent): Boolean {
            val p = spotParams ?: return true
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRaw = Recognizer.Pt(e.rawX, e.rawY)
                    startPos = p.x to p.y
                }
                MotionEvent.ACTION_MOVE -> {
                    p.x = (startPos.first + (e.rawX - downRaw.x)).toInt()
                    p.y = (startPos.second + (e.rawY - downRaw.y)).toInt()
                    runCatching { wm.updateViewLayout(this, p) }
                }
                MotionEvent.ACTION_UP -> {
                    // Letting go (after a drag or a tap) saves the place and ends placement.
                    Store.setSpotPos(this@SpotService, p.x, p.y)
                    moving = false
                    performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                    invalidate()
                    updateSpotVisibility()
                }
            }
            return true
        }
    }

    private fun raw(l: View, e: MotionEvent): Recognizer.Pt {
        val off = layerOffset(l)
        return Recognizer.Pt(e.rawX - off.x, e.rawY - off.y)
    }

    private fun toLayer(l: View, screen: Recognizer.Pt): Recognizer.Pt {
        val off = layerOffset(l)
        return Recognizer.Pt(screen.x - off.x, screen.y - off.y)
    }

    /** Where the full-screen layer sits on screen (0,0 once laid out; 0,0 before too). */
    private fun layerOffset(l: View): Recognizer.Pt {
        if (!l.isAttachedToWindow) return Recognizer.Pt(0f, 0f)
        val a = IntArray(2)
        l.getLocationOnScreen(a)
        return Recognizer.Pt(a[0].toFloat(), a[1].toFloat())
    }

    // ---------------------------------------------------------------- the full-screen layer

    private fun openLayer(): FlickView? {
        layer?.let { return it }
        val v = FlickView(android.view.ContextThemeWrapper(this, R.style.Overlay), Store.menu(this), this)
        v.spotMode = true
        v.warmIcons()
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            setFitInsetsTypes(0)
        }
        if (runCatching { wm.addView(v, p) }.isFailure) return null
        layer = v
        layerParams = p
        updateSpotVisibility()
        return v
    }

    /** The finger left the spot's stroke: if a ring is now open, let the layer take taps. */
    private fun afterStroke() {
        val l = layer ?: return
        val p = layerParams ?: return
        if (p.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0) {
            p.flags = p.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            runCatching { wm.updateViewLayout(l, p) }
        }
    }

    private fun closeLayer() {
        val l = layer ?: return
        layer = null
        layerParams = null
        l.idle()
        runCatching { wm.removeView(l) }
        updateSpotVisibility()
    }

    // ---------------------------------------------------------------- FlickView.Listener

    override fun onLaunch(app: Slot, route: List<String>, x: Float, y: Float) {
        closeLayer()
        if (Actions.run(this, app)) {
            Store.addNode(this, app)
            if (app is Slot.App) Store.addRecent(this, app.pkg, app.label, route.joinToString(" › "))
            WidgetRenderer.renderAll(this)
        }
    }

    override fun onClose() = closeLayer()

    /** Empty spot: open Flick's panel to fill it (it has the app picker). */
    override fun onEmptySpot(path: List<Int>): Boolean {
        closeLayer()
        startActivity(FlickActivity.emptyIntent(this, path))
        return true
    }

    // ---------------------------------------------------------------- home screen only

    private val homePkgs by lazy {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        packageManager.queryIntentActivities(home, 0).map { it.activityInfo.packageName }.toSet()
    }
    private var lastEventTs = 0L
    private var foreground: String? = null

    /** With "only on the home screen": checks which app is in front about twice a second. */
    private val homeWatch = object : Runnable {
        override fun run() {
            if (!Store.spotHomeOnly(this@SpotService) || !RecentApps.hasAccess(this@SpotService)) {
                setHome(true)
                return
            }
            runCatching {
                val usm = getSystemService(UsageStatsManager::class.java)
                val now = System.currentTimeMillis()
                val ev = usm.queryEvents(if (lastEventTs == 0L) now - 60_000 else lastEventTs, now)
                val e = UsageEvents.Event()
                while (ev.hasNextEvent()) {
                    ev.getNextEvent(e)
                    if (e.eventType == UsageEvents.Event.ACTIVITY_RESUMED) foreground = e.packageName
                    lastEventTs = maxOf(lastEventTs, e.timeStamp)
                }
            }
            val fg = foreground
            // Flick's own screens count as "home" (they open over it).
            setHome(fg == null || fg in homePkgs || fg == packageName)
            main.postDelayed(this, 450)
        }
    }

    private fun setHome(v: Boolean) {
        if (homeShown == v) return
        homeShown = v
        updateSpotVisibility()
    }

    private fun startHomeWatch() {
        main.removeCallbacks(homeWatch)
        val pm = getSystemService(PowerManager::class.java)
        if (pm.isInteractive) main.post(homeWatch)
    }

    private val screen = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (i.action == Intent.ACTION_SCREEN_ON) startHomeWatch() else main.removeCallbacks(homeWatch)
        }
    }

    // ---------------------------------------------------------------- notification

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Touch spot", NotificationManager.IMPORTANCE_MIN).apply {
                description = "Keeps the Flick touch spot ready. You can turn this notification off."
                setShowBadge(false)
            },
        )
        fun action(a: String, code: Int) = PendingIntent.getService(
            this, code, Intent(this, SpotService::class.java).setAction(a), PendingIntent.FLAG_IMMUTABLE,
        )
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_fg)
            .setContentTitle("Flick touch spot is on")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Move spot", action(ACTION_MOVE, 1)).build())
            .addAction(Notification.Action.Builder(null, "Turn off", action(ACTION_OFF, 2)).build())
            .build()
    }

    companion object {
        private const val NOTE_ID = 7
        private const val CHANNEL = "spot"
        const val ACTION_MOVE = "com.jono.flick.MOVE_SPOT"
        const val ACTION_OFF = "com.jono.flick.SPOT_OFF"

        /** Starts (or refreshes) the spot if it's turned on and allowed. */
        fun sync(ctx: Context, action: String? = null) {
            if (Store.spotOn(ctx) && Settings.canDrawOverlays(ctx)) {
                runCatching {
                    ctx.startForegroundService(Intent(ctx, SpotService::class.java).setAction(action))
                }
            } else {
                ctx.stopService(Intent(ctx, SpotService::class.java))
            }
        }

        fun overlaySettings(ctx: Context): Intent =
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}"))
    }
}

/** Brings the spot back after a restart or an update. */
class SpotBootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) = SpotService.sync(ctx)
}
