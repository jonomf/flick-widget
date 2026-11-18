package com.jono.flick

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast

/** Widget app/recent taps: invisible, remembers the launch, opens the app, closes. */
class LaunchActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A person (call / text) from the widget.
        intent.getStringExtra(EXTRA_SLOT)?.let { json ->
            Store.slotFromJson(json)?.let { Actions.run(this, it) }
            finish()
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
            return
        }
        val pkg = intent.getStringExtra(EXTRA_PKG).orEmpty()
        val label = intent.getStringExtra(EXTRA_LABEL).orEmpty()
        val launch = if (pkg.isEmpty()) null else packageManager.getLaunchIntentForPackage(pkg)
        if (launch == null) {
            Toast.makeText(this, "${label.ifEmpty { "That app" }} isn't installed", Toast.LENGTH_SHORT).show()
        } else {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            launch.sourceBounds = intent.sourceBounds
            runCatching { startActivity(launch) }
            Store.addRecent(this, pkg, label, intent.getStringExtra(EXTRA_ROUTE).orEmpty())
            WidgetRenderer.renderAll(this)
        }
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    companion object {
        private const val EXTRA_PKG = "pkg"
        private const val EXTRA_LABEL = "label"
        private const val EXTRA_ROUTE = "route"
        private const val EXTRA_SLOT = "slot"

        fun slotIntent(ctx: Context, slot: Slot): Intent =
            Intent(ctx, LaunchActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                .putExtra(EXTRA_SLOT, Store.slotToJson(slot))

        fun intent(ctx: Context, pkg: String, label: String, route: String): Intent =
            Intent(ctx, LaunchActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                .putExtra(EXTRA_PKG, pkg)
                .putExtra(EXTRA_LABEL, label)
                .putExtra(EXTRA_ROUTE, route)
    }
}
