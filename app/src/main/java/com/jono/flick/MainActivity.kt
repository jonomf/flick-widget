package com.jono.flick

import android.app.Activity
import android.app.AlertDialog
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/**
 * Setup screen (also the widget's long-press "settings" entry): how to use it, the menu editor,
 * settings, your widget sizes, updates.
 */
class MainActivity : Activity() {
    private lateinit var menu: Slot.Folder
    private var editPath = listOf<Int>()
    private lateinit var crumb: TextView
    private lateinit var grid: GridLayout
    private lateinit var sizes: TextView
    private lateinit var updateStatus: TextView
    private lateinit var updaterButton: Button
    private lateinit var dwellButton: Button
    private lateinit var ringDelayButton: Button
    private lateinit var spotSwitch: Switch
    private lateinit var spotStatus: TextView
    private lateinit var spotSizeButton: Button
    private lateinit var spotHomeSwitch: Switch
    private lateinit var dotButton: Button
    private lateinit var usageStatus: TextView
    private lateinit var usageButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent.action == AppWidgetManager.ACTION_APPWIDGET_CONFIGURE) {
            val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
        }
        menu = Store.menu(this)

        val pad = dp(24)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
        }
        col.addView(TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = 28f
            setTypeface(typeface, Typeface.BOLD)
        })
        col.addView(body(
            "Tap the widget, then flick. Each straight stroke picks the item in that direction; " +
                "turn sharply after pointing at a folder (›) to go into it, so one zigzag can go " +
                "several levels deep. Hold still to see the menu. Tap instead of flicking to get " +
                "the menu to tap on. Tap outside or wait to close.",
        ))
        col.addView(Button(this).apply {
            text = "Map apps (A–Z): hold an app, draw where it goes"
            setOnClickListener { startActivity(Intent(this@MainActivity, MapActivity::class.java)) }
        })
        col.addView(Button(this).apply { text = "Try it here"; setOnClickListener { startActivity(FlickActivity.intent(this@MainActivity)) } })
        col.addView(Button(this).apply { text = "Add the widget"; setOnClickListener { pinWidget() } })

        // --- Menu editor ---
        col.addView(heading("Menu"))
        col.addView(body("Each spot is a direction. Tap one to set an app or a folder. The middle goes back up. " +
            "For apps, Map apps above is quicker."))
        crumb = TextView(this).apply { textSize = 15f; setTypeface(typeface, Typeface.BOLD); setPadding(0, dp(4), 0, dp(8)) }
        col.addView(crumb)
        grid = GridLayout(this).apply { columnCount = 3; rowCount = 3 }
        col.addView(grid, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        col.addView(Button(this).apply {
            text = "Reset to the sample menu"
            setOnClickListener {
                AlertDialog.Builder(this@MainActivity)
                    .setMessage("Replace your whole menu with the sample one?")
                    .setPositiveButton("Replace") { _, _ ->
                        menu = Store.sampleMenu(this@MainActivity)
                        editPath = emptyList()
                        save()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        })

        // --- Settings ---
        // --- Touch spot ---
        col.addView(heading("Touch spot (no tap first)"))
        spotStatus = body("")
        col.addView(spotStatus)
        spotSwitch = Switch(this).apply {
            text = "Show the touch spot"
            setPadding(0, dp(6), 0, dp(6))
            setOnCheckedChangeListener { _, on -> setSpot(on) }
        }
        col.addView(spotSwitch)
        col.addView(Button(this).apply {
            text = "Move the spot (drag it, then let go)"
            setOnClickListener {
                if (!Store.spotOn(this@MainActivity)) setSpot(true)
                SpotService.sync(this@MainActivity, SpotService.ACTION_MOVE)
                moveTaskToBack(true) // show the home screen, where it usually goes
            }
        })
        spotSizeButton = Button(this).apply {
            setOnClickListener {
                val sizes = Store.SPOT_SIZES
                val next = sizes[(sizes.indexOf(Store.spotSizeDp(this@MainActivity)) + 1) % sizes.size]
                Store.setSpotSizeDp(this@MainActivity, next)
                SpotService.sync(this@MainActivity)
                showSpotState()
            }
        }
        col.addView(spotSizeButton)
        spotHomeSwitch = Switch(this).apply {
            text = "Only on the home screen"
            setPadding(0, dp(6), 0, dp(6))
            setOnCheckedChangeListener { _, on ->
                Store.setSpotHomeOnly(this@MainActivity, on)
                if (on && !RecentApps.hasAccess(this@MainActivity)) RecentApps.openSettings(this@MainActivity)
                SpotService.sync(this@MainActivity)
                showSpotState()
            }
        }
        col.addView(spotHomeSwitch)

        col.addView(heading("Settings"))
        usageStatus = body("")
        col.addView(usageStatus)
        usageButton = Button(this).apply { setOnClickListener { RecentApps.openSettings(this@MainActivity) } }
        col.addView(usageButton)
        col.addView(Button(this).apply {
            text = "App info (if Android says it's a restricted setting)"
            setOnClickListener {
                startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            }
        })
        col.addView(Switch(this).apply {
            text = "Practice mode: show what you picked instead of opening it"
            isChecked = Store.practice(this@MainActivity)
            setOnCheckedChangeListener { _, on -> Store.setPractice(this@MainActivity, on) }
            setPadding(0, dp(6), 0, dp(6))
        })
        col.addView(Switch(this).apply {
            text = "Vibrate on each direction and folder"
            isChecked = Store.haptics(this@MainActivity)
            setOnCheckedChangeListener { _, on -> Store.setHaptics(this@MainActivity, on) }
            setPadding(0, dp(6), 0, dp(6))
        })
        dwellButton = Button(this).apply { setOnClickListener { cycleDwell() } }
        col.addView(dwellButton)
        ringDelayButton = Button(this).apply { setOnClickListener { cycleRingDelay() } }
        col.addView(ringDelayButton)
        dotButton = Button(this).apply {
            setOnClickListener {
                Store.setDotStyle(this@MainActivity, (Store.dotStyle(this@MainActivity) + 1) % Store.DOT_NAMES.size)
                showSettings()
                WidgetRenderer.renderAll(this@MainActivity)
            }
        }
        col.addView(dotButton)

        // --- Widgets ---
        col.addView(heading("Your widgets"))
        sizes = body("")
        col.addView(sizes)

        // --- Updates ---
        col.addView(heading("Updates"))
        updateStatus = body("")
        col.addView(updateStatus)
        updaterButton = Button(this).apply { setOnClickListener { openUpdater() } }
        col.addView(updaterButton)
        col.addView(Button(this).apply {
            text = "Get the latest build in the browser"
            setOnClickListener { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(RELEASES_URL))) }
        })

        ClaudeLink.addTopButton(this, col)
        ClaudeLink.addSection(this, col) { WidgetRenderer.renderAll(this) }

        setContentView(ScrollView(this).apply { addView(col) })
    }

    override fun onResume() {
        super.onResume()
        Icons.clear()
        menu = Store.menu(this) // Map apps may have changed it
        drawGrid()
        showSettings()
        SpotService.sync(this)
        showSpotState()
        val usage = RecentApps.hasAccess(this)
        usageStatus.text = if (usage) {
            "✓ Usage access is on: the app picker starts with the 3 apps you used last."
        } else {
            "Recent apps in the app picker: right now only apps opened through Flick. With usage access, " +
                "the picker starts with the 3 apps you used last on your phone. Android warns that usage access " +
                "lets an app see which apps you use and how often; Flick only reads the last few, on the phone, " +
                "to fill that row. If Android says it's a restricted setting: App info → ⋮ → Allow restricted settings."
        }
        usageButton.text = if (usage) "Usage access settings" else "Allow usage access"
        sizes.text = WidgetRenderer.describeSizes(this)
        WidgetRenderer.renderAll(this)
        val build = UpdateStatus.currentBuild(this)
        val hasUpdater = UpdateStatus.updaterIntent(this) != null
        val latest = UpdateStatus.latest(this)
        updateStatus.text = when {
            !hasUpdater -> "You're on build $build. Install Widget Updater to get an update symbol on " +
                "the widget and one-tap updates for all your widgets."
            latest > build -> "Build $latest is available (you're on $build)."
            else -> "You're on build $build. Widget Updater keeps this up to date; the widget shows " +
                "a small ↑ symbol when there's a new build."
        }
        updaterButton.text = when {
            !hasUpdater -> "Get Widget Updater"
            latest > build -> "Update now"
            else -> "Open Widget Updater"
        }
    }

    // ---------------------------------------------------------------- menu editor

    private fun folder(): Slot.Folder = menu.at(editPath) ?: menu.also { editPath = emptyList() }

    private fun drawGrid() {
        val f = folder()
        crumb.text = (listOf(menu.name) + menu.names(editPath)).joinToString("  ›  ")
        grid.removeAllViews()
        val order = listOf(7, 0, 1, 6, -1, 2, 5, 4, 3)
        val cellW = (resources.displayMetrics.widthPixels - dp(48)) / 3
        order.forEachIndexed { pos, i ->
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(4), dp(10), dp(4), dp(10))
                isClickable = true
                background = getDrawable(android.R.drawable.list_selector_background)
            }
            val icon = ImageView(this)
            val label = TextView(this).apply {
                gravity = Gravity.CENTER
                textSize = 12f
                maxLines = 2
            }
            val dir = TextView(this).apply { gravity = Gravity.CENTER; textSize = 10f; alpha = 0.55f }
            if (i == -1) {
                label.text = if (editPath.isEmpty()) "(top)" else "‹ Back up"
                label.setTypeface(label.typeface, Typeface.BOLD)
                icon.setImageResource(R.drawable.ic_hub)
                cell.setOnClickListener { if (editPath.isNotEmpty()) { editPath = editPath.dropLast(1); drawGrid() } }
            } else {
                val s = f.slots[i]
                dir.text = Slot.DIRECTIONS[i]
                if (s == null) {
                    label.text = "+"
                    label.alpha = 0.5f
                } else {
                    icon.setImageBitmap(Icons.bitmap(this, s, dp(44)))
                    label.text = s.label + if (s is Slot.Folder) " ›" else ""
                }
                cell.setOnClickListener { editSlot(i) }
            }
            cell.addView(icon, LinearLayout.LayoutParams(dp(44), dp(44)))
            cell.addView(label)
            cell.addView(dir)
            grid.addView(cell, GridLayout.LayoutParams(GridLayout.spec(pos / 3), GridLayout.spec(pos % 3)).apply {
                width = cellW
            })
        }
    }

    private fun editSlot(i: Int) {
        val f = folder()
        val s = f.slots[i]
        val options = mutableListOf<Pair<String, () -> Unit>>()
        fun opt(label: String, action: () -> Unit) { options += label to action }
        if (s is Slot.Folder) {
            opt("Open folder") { editPath = editPath + i; drawGrid() }
            opt("Rename folder") { renameFolder(s) }
        }
        if (s is Slot.Link) {
            opt("Edit link or picture…") {
                Dialogs.link(this, s.url, s.name, s.photo) { l -> f.slots[i] = l; Icons.clear(); save() }
            }
        }
        if (s is Slot.Contact) {
            opt("Change picture…") {
                PickContactActivity.pickPhoto(this) { photo ->
                    if (photo != null) {
                        f.slots[i] = Slot.Contact(s.name, s.number, s.action, photo)
                        Icons.clear()
                        save()
                    }
                }
            }
        }
        opt("Choose app, person, link or folder…") {
            pickApp(
                onFolder = {
                    Dialogs.folderName(this, "Folder") { name ->
                        f.slots[i] = Slot.Folder(name)
                        save()
                    }
                },
                onPerson = {
                    PickContactActivity.start(this) { p -> if (p != null) { f.slots[i] = p; save() } }
                },
                onLink = { Dialogs.link(this) { l -> f.slots[i] = l; save() } },
            ) { app -> f.slots[i] = app; save() }
        }
        if (s !is Slot.Folder) {
            opt("Make a folder here") {
                val nf = Slot.Folder("Folder")
                f.slots[i] = nf
                save()
                renameFolder(nf)
            }
        }
        if (s != null) opt("Clear") {
            if (s is Slot.Folder && s.slots.any { it != null }) {
                AlertDialog.Builder(this)
                    .setMessage("Clear the folder \"${s.name}\" and everything in it?")
                    .setPositiveButton("Clear") { _, _ -> f.slots[i] = null; save() }
                    .setNegativeButton("Cancel", null)
                    .show()
            } else {
                f.slots[i] = null
                save()
            }
        }
        AlertDialog.Builder(this)
            .setTitle("${Slot.DIRECTIONS[i]}: ${s?.label ?: "empty"}")
            .setItems(options.map { it.first }.toTypedArray()) { _, which -> options[which].second() }
            .show()
    }

    private fun renameFolder(f: Slot.Folder) {
        Dialogs.folderName(this, f.name) { name ->
            f.name = name
            save()
        }
    }

    /** Same picker as everywhere: last 3 apps, then all apps A–Z. */
    private fun pickApp(
        onFolder: (() -> Unit)? = null, onPerson: (() -> Unit)? = null, onLink: (() -> Unit)? = null,
        onPick: (Slot.App) -> Unit,
    ) {
        lateinit var dialog: AlertDialog
        val newFolder: ((View) -> Unit)? = onFolder?.let { f -> { _: View -> dialog.dismiss(); f() } }
        val newPerson: ((View) -> Unit)? = onPerson?.let { f -> { _: View -> dialog.dismiss(); f() } }
        val newLink: ((View) -> Unit)? = onLink?.let { f -> { _: View -> dialog.dismiss(); f() } }
        val picker = AppPicker(this, newFolder, newPerson, newLink) { app, _ ->
            onPick(app)
            dialog.dismiss()
        }.apply {
            menu = this@MainActivity.menu
            setPadding(dp(12), dp(4), dp(12), 0)
        }
        val box = android.widget.FrameLayout(this).apply {
            addView(picker, android.widget.FrameLayout.LayoutParams(-1, (resources.displayMetrics.heightPixels * 0.7f).toInt()))
        }
        dialog = AlertDialog.Builder(this).setTitle("Choose an app").setView(box)
            .setNegativeButton("Cancel", null).create()
        dialog.show()
    }

    private fun save() {
        Store.saveMenu(this, menu)
        drawGrid()
        WidgetRenderer.renderAll(this)
    }

    // ---------------------------------------------------------------- settings

    private fun setSpot(on: Boolean) {
        Store.setSpotOn(this, on)
        if (on && !android.provider.Settings.canDrawOverlays(this)) {
            AlertDialog.Builder(this)
                .setTitle("Let Flick draw its spot")
                .setMessage(
                    "The touch spot is a small Flick button that Flick draws on screen itself, so you can " +
                        "start drawing on it straight away, with no app to open. Android calls this " +
                        "\"Display over other apps\". Flick only uses it for the spot and the ring.\n\n" +
                        "To stay instant, Flick keeps running quietly, with a silent notification you can hide.",
                )
                .setPositiveButton("Open setting") { _, _ -> startActivity(SpotService.overlaySettings(this)) }
                .setNegativeButton("Not now") { _, _ -> Store.setSpotOn(this, false); showSpotState() }
                .show()
        }
        SpotService.sync(this)
        showSpotState()
    }

    private fun showSpotState() {
        val on = Store.spotOn(this)
        val allowed = android.provider.Settings.canDrawOverlays(this)
        spotSwitch.setOnCheckedChangeListener(null)
        spotSwitch.isChecked = on
        spotSwitch.setOnCheckedChangeListener { _, v -> setSpot(v) }
        spotHomeSwitch.setOnCheckedChangeListener(null)
        spotHomeSwitch.isChecked = Store.spotHomeOnly(this)
        spotHomeSwitch.setOnCheckedChangeListener { _, v ->
            Store.setSpotHomeOnly(this, v)
            if (v && !RecentApps.hasAccess(this)) RecentApps.openSettings(this)
            SpotService.sync(this)
            showSpotState()
        }
        spotSizeButton.text = "Spot size: ${Store.spotSizeDp(this)} dp (tap to change)"
        spotStatus.text = when {
            on && allowed -> "✓ On. Put a finger on the spot and draw: flicks and zigzags start right away. " +
                "Tap it for the ring. Hold it still for about a second to drag it somewhere else." +
                if (Store.spotHomeOnly(this) && !RecentApps.hasAccess(this)) " (Only-on-home needs usage access.)" else ""
            on -> "Needs \"Display over other apps\" for Flick. Turn the switch off and on to open the setting."
            else -> "A floating Flick spot you draw from directly, no widget tap first, and no wait for Flick to start."
        }
    }

    private fun showSettings() {
        dwellButton.text = "Hold to show the menu: ${Store.dwellMs(this)} ms (tap to change)"
        val rd = Store.ringDelayMs(this)
        ringDelayButton.text = if (rd == 0L) "After tapping the widget: wait for a flick (ring never shows by itself)"
        else "After tapping the widget: show the ring after $rd ms (tap to change)"
        dotButton.text = "Empty spots: ${Store.DOT_NAMES[Store.dotStyle(this)]} (tap to change)"
    }

    private fun cycleRingDelay() {
        val steps = listOf(300L, 500L, 700L, 1000L, 1500L, 0L)
        val i = steps.indexOf(Store.ringDelayMs(this))
        Store.setRingDelayMs(this, steps[(i + 1) % steps.size])
        showSettings()
    }

    private fun cycleDwell() {
        val steps = listOf(200L, 250L, 300L, 400L, 500L, 700L)
        val next = steps.firstOrNull { it > Store.dwellMs(this) } ?: steps.first()
        Store.setDwellMs(this, next)
        dwellButton.text = "Hold to show the menu: $next ms (tap to change)"
    }

    private fun pinWidget() {
        val mgr = getSystemService(AppWidgetManager::class.java)
        if (mgr.isRequestPinAppWidgetSupported) {
            mgr.requestPinAppWidget(ComponentName(this, FlickWidget::class.java), null, null)
        } else {
            Toast.makeText(this, "Long-press your home screen → Widgets → Flick", Toast.LENGTH_LONG).show()
        }
    }

    private fun openUpdater() {
        val intent = UpdateStatus.updaterIntent(this)
            ?: Intent(Intent.ACTION_VIEW, Uri.parse(UpdateStatus.UPDATER_RELEASES))
        startActivity(intent)
    }

    private fun heading(text: String) = TextView(this).apply {
        this.text = text
        textSize = 18f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(24), 0, dp(6))
    }

    private fun body(text: String) = TextView(this).apply {
        this.text = text
        textSize = 15f
        setPadding(0, dp(8), 0, dp(8))
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        const val RELEASES_URL = "https://github.com/jonomf/flick-widget/releases/latest"
    }
}
