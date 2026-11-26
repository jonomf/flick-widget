package com.jono.flick

import android.app.AlertDialog
import android.content.Context
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout

object Dialogs {
    /** Dialogs opened from the touch spot (a service, not a screen) are shown as an overlay. */
    fun overOtherApps(ctx: Context, d: AlertDialog) {
        if (ctx !is android.app.Activity) {
            d.window?.setType(android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        }
    }

    /** Opens the keyboard on [field] as soon as the dialog shows (instead of waiting for a tap). */
    fun AlertDialog.withKeyboard(field: EditText): AlertDialog {
        window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        setOnShowListener { field.requestFocus() }
        return this
    }

    /** First web link in some shared or pasted text. */
    private val URL = Regex("""(https?://|[a-z][a-z0-9+.-]*://)[^\s)\]>"']+""", RegexOption.IGNORE_CASE)

    fun findUrl(text: String?): String? = text?.let { URL.find(it)?.value }

    /**
     * Every link in some text, each with a name from the same line when there is one:
     * "Story Atlas — https://…", "Story Atlas: https://…", "[Story Atlas](https://…)", or a bare link.
     */
    fun findLinks(text: String?): List<Pair<String, String>> {
        if (text == null) return emptyList()
        val out = mutableListOf<Pair<String, String>>()
        for (line in text.lines()) {
            var from = 0
            for (m in URL.findAll(line)) {
                val url = m.value.trimEnd('.', ',')
                // The name is the text before the link on its line (after any earlier link).
                val name = line.substring(from, m.range.first)
                    .replace(Regex("""[\[\]()*_#>•|]+"""), " ")
                    .replace(Regex("""\s[-–—:]+\s*$|^[\s\-–—:]+"""), " ")
                    .trim().trimEnd('-', '–', '—', ':').trim()
                    .ifEmpty { runCatching { android.net.Uri.parse(url).host }.getOrNull() ?: "Link" }
                from = m.range.last + 1
                if (out.none { it.second == url }) out += name.take(40) to url
            }
        }
        return out
    }

    /** A row of choices for how a link opens. */
    private fun openChoice(ctx: Context, current: String): Pair<android.view.View, () -> String> {
        val group = android.widget.RadioGroup(ctx).apply { orientation = android.widget.RadioGroup.HORIZONTAL }
        Slot.Link.MODES.forEachIndexed { i, m ->
            group.addView(android.widget.RadioButton(ctx).apply {
                id = 100 + i
                text = Slot.Link.MODE_NAMES[i]
                isChecked = m == current
            })
        }
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(android.widget.TextView(ctx).apply { text = "Open in"; textSize = 12f; alpha = 0.75f })
            addView(group)
        }
        return box to { Slot.Link.MODES.getOrElse(group.checkedRadioButtonId - 100) { Slot.Link.APP } }
    }

    /**
     * Several links at once (shared or pasted, e.g. a list of your web apps): makes a folder of
     * them, 8 per level. [onDone] gets the folder.
     */
    fun linkFolder(ctx: Context, links: List<Pair<String, String>>, onCancel: () -> Unit = {}, onDone: (Slot.Folder) -> Unit) {
        val d = ctx.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val nameIn = EditText(ctx).apply {
            setText(if (links.all { it.second.contains("claude.ai") }) "Web apps" else "Links")
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            selectAll()
        }
        val (choice, chosen) = openChoice(ctx, Slot.Link.TAB)
        val list = android.widget.TextView(ctx).apply {
            text = links.joinToString("\n") { "• ${it.first}" }
            textSize = 13f
            setPadding(0, dp(8), 0, 0)
        }
        AlertDialog.Builder(ctx)
            .setTitle("Add ${links.size} links as a folder")
            .setView(android.widget.ScrollView(ctx).apply {
                addView(LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(20), dp(8), dp(20), 0)
                    addView(nameIn, LinearLayout.LayoutParams(-1, -2))
                    addView(choice)
                    addView(list)
                })
            })
            .setPositiveButton("Add") { _, _ ->
                val open = chosen()
                val items = links.map { (n, u) -> Slot.Link(n, u, null, open) }
                onDone(folderOf(nameIn.text.toString().trim().ifEmpty { "Links" }, items))
            }
            .setNegativeButton("Cancel") { _, _ -> onCancel() }
            .setOnCancelListener { onCancel() }
            .create()
            .also { overOtherApps(ctx, it) }
            .show()
    }

    /**
     * Adds (or edits) a link: a name, the link, and optionally a picture. Pasted links work, and
     * one on the clipboard is filled in. [onDone] gets the link.
     */
    fun link(
        ctx: Context, url: String? = null, name: String? = null, photo: String? = null,
        onCancel: () -> Unit = {}, open: String = Slot.Link.APP, onDone: (Slot.Link) -> Unit,
    ) {
        val d = ctx.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val clip = runCatching {
            ctx.getSystemService(android.content.ClipboardManager::class.java)?.primaryClip?.getItemAt(0)?.text?.toString()
        }.getOrNull()
        val nameIn = EditText(ctx).apply {
            hint = "Name (e.g. Family chat)"
            setText(name.orEmpty())
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
        }
        val urlIn = EditText(ctx).apply {
            hint = "Link"
            setText(url ?: findUrl(clip).orEmpty())
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        val help = android.widget.TextView(ctx).apply {
            text = "Copy the link in the app, then paste it here (or share it to Flick):\n" +
                "• Google Chat: a space's menu → Copy link (or the address in Chat on the web)\n" +
                "• Discord: long-press a channel or group chat → Copy link\n" +
                "• Telegram, Slack, Messenger and web pages work too"
            textSize = 12f
            alpha = 0.75f
            setPadding(0, dp(6), 0, 0)
        }
        val (choice, chosen) = openChoice(ctx, open)
        fun build(p: String?): Slot.Link? {
            val u = urlIn.text.toString().trim()
            if (u.isEmpty()) return null
            val n = nameIn.text.toString().trim().ifEmpty {
                runCatching { android.net.Uri.parse(if (u.contains("://")) u else "https://$u").host }.getOrNull() ?: "Link"
            }
            return Slot.Link(n, u, p, chosen())
        }
        AlertDialog.Builder(ctx)
            .setTitle(if (url == null && name == null) "Add a link" else "Link")
            .setView(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(20), dp(8), dp(20), 0)
                addView(nameIn, LinearLayout.LayoutParams(-1, -2))
                addView(urlIn, LinearLayout.LayoutParams(-1, -2))
                addView(choice)
                addView(help, LinearLayout.LayoutParams(-1, -2))
            })
            .setPositiveButton("OK") { _, _ -> build(photo)?.let(onDone) ?: onCancel() }
            .setNeutralButton("Picture…") { _, _ ->
                val base = build(photo) ?: return@setNeutralButton onCancel()
                PickContactActivity.pickPhoto(ctx) { p -> onDone(if (p != null) base.copy(photo = p) else base) }
            }
            .setNegativeButton("Cancel") { _, _ -> onCancel() }
            .setOnCancelListener { onCancel() }
            .create()
            .also { overOtherApps(ctx, it) }
            .withKeyboard(if (nameIn.text.isEmpty()) nameIn else urlIn)
            .show()
    }

    /** Asks for a folder name. [onDone] gets the trimmed name ("Folder" if left blank). */
    fun folderName(ctx: Context, initial: String, onDone: (String) -> Unit) {
        val d = ctx.resources.displayMetrics.density
        val input = EditText(ctx).apply {
            setText(initial)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            selectAll()
        }
        AlertDialog.Builder(ctx)
            .setTitle("Folder name")
            .setView(LinearLayout(ctx).apply {
                setPadding((20 * d).toInt(), (8 * d).toInt(), (20 * d).toInt(), 0)
                addView(input, LinearLayout.LayoutParams(-1, -2))
            })
            .setPositiveButton("OK") { _, _ -> onDone(input.text.toString().trim().ifEmpty { "Folder" }) }
            .setNegativeButton("Cancel", null)
            .create()
            .also { overOtherApps(ctx, it) }
            .withKeyboard(input)
            .show()
    }
}
