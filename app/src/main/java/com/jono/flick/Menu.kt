package com.jono.flick

/**
 * The menu: every folder has 8 slots, one per compass direction, clockwise from straight up.
 * 0 = N, 1 = NE, 2 = E, 3 = SE, 4 = S, 5 = SW, 6 = W, 7 = NW. A slot is an app, a folder, or empty.
 * Pure Kotlin (no Android), so the gesture logic can be unit-tested.
 */
sealed class Slot {
    abstract val label: String

    class App(val pkg: String, override val label: String) : Slot()

    /**
     * A person and what to do: call, text (SMS) or WhatsApp. Like the system's per-person
     * "Direct dial" / chat shortcuts. [photo] is a file name in the app's files, if any.
     */
    class Contact(val name: String, val number: String, val action: String, val photo: String? = null) : Slot() {
        override val label: String get() = name

        companion object {
            const val CALL = "call"
            const val SMS = "sms"
            const val WHATSAPP = "whatsapp"
        }
    }

    /**
     * A link opened in whichever app handles it: a Google Chat space or group, a Discord channel,
     * a Telegram or Slack chat, a web page. [photo] as for people.
     */
    class Link(val name: String, val url: String, val photo: String? = null, val open: String = APP) : Slot() {
        override val label: String get() = name

        fun copy(name: String = this.name, url: String = this.url, photo: String? = this.photo, open: String = this.open) =
            Link(name, url, photo, open)

        companion object {
            /** Open in whichever app claims the link (e.g. Claude, Discord), else the browser. */
            const val APP = "app"
            /** A Chrome tab inside Flick's flow: quick, full-screen, signed in like Chrome. */
            const val TAB = "tab"
            /** Chrome itself. */
            const val BROWSER = "browser"
            val MODES = listOf(APP, TAB, BROWSER)
            val MODE_NAMES = listOf("Its app", "Chrome tab", "Chrome")
        }
    }



    class Folder(var name: String, val slots: MutableList<Slot?> = MutableList(SLOTS) { null }) : Slot() {
        override val label: String get() = name
    }

    companion object {
        const val SLOTS = 8
        val DIRECTIONS = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
    }
}

/** Walks [path] (slot indexes) down from [root]; null if any step isn't a folder. */
fun Slot.Folder.at(path: List<Int>): Slot.Folder? {
    var f: Slot.Folder = this
    for (i in path) f = f.slots.getOrNull(i) as? Slot.Folder ?: return null
    return f
}

/** Folder names along [path], e.g. ["More", "Deeper"]. */
fun Slot.Folder.names(path: List<Int>): List<String> {
    val out = mutableListOf<String>()
    var f: Slot.Folder = this
    for (i in path) {
        f = f.slots.getOrNull(i) as? Slot.Folder ?: break
        out += f.name
    }
    return out
}

/** Every place each app sits in the menu: package → slot paths. */
fun Slot.Folder.appPaths(): Map<String, List<List<Int>>> {
    val out = LinkedHashMap<String, MutableList<List<Int>>>()
    fun walk(f: Slot.Folder, base: List<Int>) {
        f.slots.forEachIndexed { i, s ->
            when (s) {
                is Slot.App -> out.getOrPut(s.pkg) { mutableListOf() } += base + i
                is Slot.Folder -> walk(s, base + i)
                is Slot.Contact, is Slot.Link, null -> {}
            }
        }
    }
    walk(this, emptyList())
    return out
}

/** A spot in words: folder names, then the direction, e.g. "More › NE" or just "N". */
fun Slot.Folder.describe(path: List<Int>): String =
    (names(path.dropLast(1)) + Slot.DIRECTIONS[path.last()]).joinToString(" › ")

/** Items → a folder: up to 8 in its spots; with more, the 8th spot becomes "More", and so on. */
fun folderOf(name: String, items: List<Slot>): Slot.Folder {
    val f = Slot.Folder(name)
    if (items.size <= Slot.SLOTS) {
        items.forEachIndexed { i, l -> f.slots[i] = l }
    } else {
        items.take(Slot.SLOTS - 1).forEachIndexed { i, l -> f.slots[i] = l }
        f.slots[Slot.SLOTS - 1] = folderOf("More", items.drop(Slot.SLOTS - 1))
    }
    return f
}
