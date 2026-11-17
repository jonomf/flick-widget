package com.jono.flick

/**
 * The menu: every folder has 8 slots, one per compass direction, clockwise from straight up.
 * 0 = N, 1 = NE, 2 = E, 3 = SE, 4 = S, 5 = SW, 6 = W, 7 = NW. A slot is an app, a folder, or empty.
 * Pure Kotlin (no Android), so the gesture logic can be unit-tested.
 */
sealed class Slot {
    abstract val label: String

    class App(val pkg: String, override val label: String) : Slot()

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
