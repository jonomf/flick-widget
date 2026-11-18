package com.jono.flick

import android.app.AlertDialog
import android.content.Context
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout

object Dialogs {
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
