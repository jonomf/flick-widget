package com.jono.flick

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle

/** "Call Sam?" before a direct call (setting: Ask before calling). */
class ConfirmCallActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val c = intent.getStringExtra(EXTRA_SLOT)?.let { Store.slotFromJson(it) } as? Slot.Contact
        if (c == null) return finish()
        AlertDialog.Builder(this)
            .setTitle("Call ${c.name}?")
            .setMessage(c.number)
            .setPositiveButton("Call") { _, _ ->
                Actions.run(this, c, confirmed = true)
                done()
            }
            .setNegativeButton("Cancel") { _, _ -> done() }
            .setOnCancelListener { done() }
            .show()
    }

    private fun done() {
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    companion object {
        const val EXTRA_SLOT = "slot"
    }
}
