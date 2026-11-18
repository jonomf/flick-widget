package com.jono.flick

import android.content.Context
import android.content.Intent
import org.json.JSONArray
import org.json.JSONObject

/** Saved menu, settings and recent launches (SharedPreferences, menu as JSON). */
object Store {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("flick", Context.MODE_PRIVATE)

    // ---------------------------------------------------------------- menu

    fun menu(ctx: Context): Slot.Folder {
        val json = prefs(ctx).getString("menu", null)
        if (json != null) runCatching { return folderFrom(JSONObject(json)) }
        val m = sampleMenu(ctx)
        saveMenu(ctx, m)
        return m
    }

    fun saveMenu(ctx: Context, root: Slot.Folder) {
        prefs(ctx).edit().putString("menu", toJson(root).toString()).apply()
    }

    fun slotToJson(s: Slot): String = toJson(s).toString()
    fun slotFromJson(json: String): Slot? = runCatching { slotFrom(JSONObject(json)) }.getOrNull()

    private fun toJson(s: Slot): JSONObject = when (s) {
        is Slot.App -> JSONObject().put("app", s.pkg).put("label", s.label)
        is Slot.Link -> JSONObject().put("link", s.name).put("url", s.url)
            .apply { if (s.photo != null) put("photo", s.photo) }
        is Slot.Contact -> JSONObject().put("contact", s.name).put("number", s.number).put("action", s.action)
            .apply { if (s.photo != null) put("photo", s.photo) }
        is Slot.Folder -> JSONObject().put("folder", s.name).put("slots", JSONArray().apply {
            s.slots.forEach { put(if (it == null) JSONObject.NULL else toJson(it)) }
        })
    }

    private fun slotFrom(o: JSONObject): Slot? = when {
        o.has("app") -> Slot.App(o.getString("app"), o.optString("label", o.getString("app")))
        o.has("folder") -> folderFrom(o)
        o.has("link") -> Slot.Link(o.getString("link"), o.getString("url"), o.optString("photo").ifEmpty { null })
        o.has("contact") -> Slot.Contact(
            o.getString("contact"), o.getString("number"), o.optString("action", Slot.Contact.CALL),
            o.optString("photo").ifEmpty { null },
        )
        else -> null
    }

    private fun folderFrom(o: JSONObject): Slot.Folder {
        val f = Slot.Folder(o.optString("folder", "Menu"))
        val arr = o.optJSONArray("slots") ?: JSONArray()
        for (i in 0 until Slot.SLOTS) {
            f.slots[i] = if (i < arr.length() && !arr.isNull(i)) slotFrom(arr.getJSONObject(i)) else null
        }
        return f
    }

    /** Apps that have a launcher icon, sorted by name. */
    fun launchableApps(ctx: Context): List<Slot.App> {
        val pm = ctx.packageManager
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(i, 0)
            .map { Slot.App(it.activityInfo.packageName, it.loadLabel(pm).toString()) }
            .filter { it.pkg != ctx.packageName }
            .distinctBy { it.pkg }
            .sortedBy { it.label.lowercase() }
    }

    /**
     * A starting menu to practise with: three levels deep so zigzags can be tried right away.
     * Root: 6 apps, E = "More" folder, W = "Apps" folder. More's N = "Deeper" folder.
     */
    fun sampleMenu(ctx: Context): Slot.Folder {
        val preferred = listOf(
            "com.android.chrome", "com.google.android.gm", "com.google.android.apps.maps",
            "com.google.android.GoogleCamera", "com.google.android.apps.photos",
            "com.google.android.calendar", "com.google.android.apps.messaging",
            "com.google.android.dialer", "md.obsidian", "com.todoist",
            "com.google.android.apps.youtube.music", "com.android.settings",
        )
        val all = launchableApps(ctx)
        val queue = ArrayDeque(
            preferred.mapNotNull { p -> all.firstOrNull { it.pkg == p } } +
                all.filter { a -> a.pkg !in preferred },
        )
        fun next(): Slot.App? = queue.removeFirstOrNull()

        val root = Slot.Folder("Flick")
        val more = Slot.Folder("More")
        val apps = Slot.Folder("Apps")
        val deeper = Slot.Folder("Deeper")
        root.slots[2] = more
        root.slots[6] = apps
        more.slots[0] = deeper
        for (i in listOf(0, 1, 3, 4, 5, 7)) root.slots[i] = next()
        for (i in 1 until Slot.SLOTS) more.slots[i] = next()
        for (i in 0 until Slot.SLOTS) apps.slots[i] = next()
        for (i in 0 until Slot.SLOTS) deeper.slots[i] = next()
        return root
    }

    // ---------------------------------------------------------------- settings

    fun dwellMs(ctx: Context): Long = prefs(ctx).getLong("dwell", 300L)
    fun setDwellMs(ctx: Context, v: Long) = prefs(ctx).edit().putLong("dwell", v).apply()

    /** Practice mode: show what was picked instead of opening it. */
    fun practice(ctx: Context): Boolean = prefs(ctx).getBoolean("practice", false)
    fun setPractice(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean("practice", v).apply()

    /** After tapping the widget, show the ring on it if no flick has started by then. 0 = never. */
    fun ringDelayMs(ctx: Context): Long = prefs(ctx).getLong("ring_delay", 500L)
    fun setRingDelayMs(ctx: Context, v: Long) = prefs(ctx).edit().putLong("ring_delay", v).apply()

    const val DOT_WHITE = 0
    const val DOT_GREY = 1
    const val DOT_OFF = 2
    val DOT_NAMES = listOf("small white dots", "grey dots", "hidden")

    /** How empty spots look, on the widget and in the ring. */
    fun dotStyle(ctx: Context): Int = prefs(ctx).getInt("dot_style", DOT_WHITE)
    fun setDotStyle(ctx: Context, v: Int) = prefs(ctx).edit().putInt("dot_style", v).apply()

    fun haptics(ctx: Context): Boolean = prefs(ctx).getBoolean("haptics", true)
    fun setHaptics(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean("haptics", v).apply()

    // ---------------------------------------------------------------- recents

    /** Same thing in the menu? Apps by package, people by number + action, links by address. */
    fun key(s: Slot): String = when (s) {
        is Slot.App -> "app:" + s.pkg
        is Slot.Contact -> "contact:" + s.number + ":" + s.action
        is Slot.Link -> "link:" + s.url
        is Slot.Folder -> "folder:" + s.name
    }

    /** Anything opened through Flick (apps, people, links), newest first, with when. */
    fun nodes(ctx: Context): List<Pair<Slot, Long>> {
        val arr = runCatching { JSONArray(prefs(ctx).getString("nodes", "[]")) }.getOrElse { JSONArray() }
        return (0 until arr.length()).mapNotNull {
            val o = arr.getJSONObject(it)
            slotFromJson(o.getString("slot"))?.let { s -> s to o.getLong("ts") }
        }
    }

    fun addNode(ctx: Context, s: Slot) {
        if (s is Slot.Folder) return
        val k = key(s)
        val list = listOf(s to System.currentTimeMillis()) + nodes(ctx).filter { key(it.first) != k }
        val arr = JSONArray()
        list.take(12).forEach { (slot, ts) -> arr.put(JSONObject().put("slot", slotToJson(slot)).put("ts", ts)) }
        prefs(ctx).edit().putString("nodes", arr.toString()).apply()
    }

    class Recent(val pkg: String, val label: String, val route: String)

    fun recents(ctx: Context): List<Recent> {
        val arr = runCatching { JSONArray(prefs(ctx).getString("recents", "[]")) }.getOrElse { JSONArray() }
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Recent(o.getString("pkg"), o.optString("label"), o.optString("route"))
        }
    }

    /** Remembers a launch (newest first, one entry per app). [route] is e.g. "More › Deeper". */
    fun addRecent(ctx: Context, pkg: String, label: String, route: String) {
        val list = listOf(Recent(pkg, label, route)) + recents(ctx).filter { it.pkg != pkg }
        val arr = JSONArray()
        list.take(10).forEach { arr.put(JSONObject().put("pkg", it.pkg).put("label", it.label).put("route", it.route)) }
        prefs(ctx).edit().putString("recents", arr.toString()).apply()
    }
}
