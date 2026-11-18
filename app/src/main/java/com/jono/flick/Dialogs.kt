package com.jono.flick

import android.app.AlertDialog
import android.content.Context
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout

object Dialogs {
    /** First web link in some shared or pasted text. */
    fun findUrl(text: String?): String? =
        text?.let { Regex("""(https?://|[a-z][a-z0-9+.-]*://)\S+""", RegexOption.IGNORE_CASE).find(it)?.value }

    /**
     * Adds (or edits) a link: a name, the link, and optionally a picture. Pasted links work, and
     * one on the clipboard is filled in. [onDone] gets the link.
     */
    fun link(
        ctx: Context, url: String? = null, name: String? = null, photo: String? = null,
        onCancel: () -> Unit = {}, onDone: (Slot.Link) -> Unit,
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
        fun build(p: String?): Slot.Link? {
            val u = urlIn.text.toString().trim()
            if (u.isEmpty()) return null
            val n = nameIn.text.toString().trim().ifEmpty {
                runCatching { android.net.Uri.parse(if (u.contains("://")) u else "https://$u").host }.getOrNull() ?: "Link"
            }
            return Slot.Link(n, u, p)
        }
        AlertDialog.Builder(ctx)
            .setTitle(if (url == null && name == null) "Add a link" else "Link")
            .setView(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(20), dp(8), dp(20), 0)
                addView(nameIn, LinearLayout.LayoutParams(-1, -2))
                addView(urlIn, LinearLayout.LayoutParams(-1, -2))
                addView(help, LinearLayout.LayoutParams(-1, -2))
            })
            .setPositiveButton("OK") { _, _ -> build(photo)?.let(onDone) ?: onCancel() }
            .setNeutralButton("Picture…") { _, _ ->
                val base = build(photo) ?: return@setNeutralButton onCancel()
                PickContactActivity.pickPhoto(ctx) { p -> onDone(if (p != null) Slot.Link(base.name, base.url, p) else base) }
            }
            .setNegativeButton("Cancel") { _, _ -> onCancel() }
            .setOnCancelListener { onCancel() }
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
            .show()
        input.requestFocus()
    }
}
