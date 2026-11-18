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
        dwellButton.text = "Hold to show the menu: ${Store.dwellMs(this)} ms (tap to change)"
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
        opt("Choose app…") { pickApp { app -> f.slots[i] = app; save() } }
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
        val input = EditText(this).apply {
            setText(f.name)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            selectAll()
        }
        AlertDialog.Builder(this)
            .setTitle("Folder name")
            .setView(LinearLayout(this).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(input, LinearLayout.LayoutParams(-1, -2)) })
            .setPositiveButton("OK") { _, _ ->
                f.name = input.text.toString().trim().ifEmpty { "Folder" }
                save()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Same picker as everywhere: last 3 apps, then all apps A–Z. */
    private fun pickApp(onPick: (Slot.App) -> Unit) {
        lateinit var dialog: AlertDialog
        val picker = AppPicker(this) { app, _ ->
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
