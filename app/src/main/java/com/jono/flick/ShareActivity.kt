package com.jono.flick

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast

/**
 * "Share → Flick" from any app: takes the shared link (a Chat space, Discord channel, page…),
 * asks for a name (and optional picture), then shows the ring to tap where it goes.
 */
class ShareActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)
        val url = Dialogs.findUrl(text) ?: text?.trim()?.takeIf { it.isNotEmpty() && !it.contains(" ") }
        if (url == null) {
            Toast.makeText(this, "Nothing to add: share a link to Flick", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT)?.takeIf { it.isNotBlank() && !it.contains("://") }
        Dialogs.link(this, url, subject, onCancel = { finish() }) { link ->
            startActivity(FlickActivity.assignSlotIntent(this, link))
            finish()
        }
    }
}
