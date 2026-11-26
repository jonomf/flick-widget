package com.jono.flick

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Telephony
import android.telephony.PhoneNumberUtils
import android.telephony.TelephonyManager
import android.widget.Toast
import java.util.Locale

/** Running a menu item: open an app, or call / text / WhatsApp a person. */
object Actions {
    const val WHATSAPP = "com.whatsapp"
    const val WHATSAPP_BUSINESS = "com.whatsapp.w4b"

    /** Returns false (and says why) when it couldn't be done. */
    fun run(ctx: Context, item: Slot, opts: Bundle? = null, confirmed: Boolean = false): Boolean {
        // A call that would start straight away: ask first, if that's the setting.
        if (!confirmed && item is Slot.Contact && item.action == Slot.Contact.CALL &&
            canCallDirectly(ctx) && Store.confirmCalls(ctx)
        ) {
            return runCatching {
                ctx.startActivity(
                    Intent(ctx, ConfirmCallActivity::class.java)
                        .putExtra(ConfirmCallActivity.EXTRA_SLOT, Store.slotToJson(item))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }.isSuccess
        }
        val intent = when (item) {
            is Slot.App -> ctx.packageManager.getLaunchIntentForPackage(item.pkg)
            is Slot.Contact -> contactIntent(ctx, item)
            is Slot.Link -> linkIntent(ctx, item)
            is Slot.Folder -> null
        }
        if (intent == null) {
            val why = if (item is Slot.App) "${item.label} isn't installed" else "Can't do that on this phone"
            Toast.makeText(ctx, why, Toast.LENGTH_SHORT).show()
            return false
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { ctx.startActivity(intent, opts) }.isSuccess.also {
            if (!it) Toast.makeText(ctx, "Couldn't open that", Toast.LENGTH_SHORT).show()
        }
    }

    const val CHROME = "com.android.chrome"

    /** A link opened its chosen way: its app, a Chrome tab, or Chrome. */
    fun linkIntent(ctx: Context, l: Slot.Link): Intent? {
        val i = linkIntent(l.url) ?: return null
        if (l.open == Slot.Link.APP) return i
        val browser = if (installed(ctx, CHROME)) CHROME else browserPackage(ctx)
        if (browser != null) i.setPackage(browser)
        if (l.open == Slot.Link.TAB) {
            // A Custom Tab, asked for without any library: the "session" extra is what browsers look for.
            i.putExtras(Bundle().apply { putBinder("android.support.customtabs.extra.SESSION", null) })
            i.putExtra("android.support.customtabs.extra.SHARE_STATE", 1)
        }
        return i
    }

    /** Opens in the app that claims the link (Chat, Discord, …), else the browser. */
    fun linkIntent(url: String): Intent? = runCatching {
        val u = url.trim().let { if (it.contains("://")) it else "https://$it" }
        Intent(Intent.ACTION_VIEW, Uri.parse(u)).addCategory(Intent.CATEGORY_BROWSABLE)
    }.getOrNull()

    /** The app a link opens in (its icon goes on the link's picture); null for a plain browser. */
    fun linkPackage(ctx: Context, url: String): String? {
        val i = linkIntent(url) ?: return null
        val r = ctx.packageManager.resolveActivity(i, PackageManager.MATCH_DEFAULT_ONLY) ?: return null
        val pkg = r.activityInfo?.packageName ?: return null
        return if (pkg == "android") browserPackage(ctx) else pkg // "android" = the chooser
    }

    /** The app a link opens in, its way (for the badge). */
    fun linkPackage(ctx: Context, l: Slot.Link): String? =
        if (l.open == Slot.Link.APP) linkPackage(ctx, l.url)
        else if (installed(ctx, CHROME)) CHROME else browserPackage(ctx)

    private fun browserPackage(ctx: Context): String? =
        ctx.packageManager.resolveActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com")), 0)
            ?.activityInfo?.packageName?.takeIf { it != "android" }

    fun canCallDirectly(ctx: Context): Boolean =
        ctx.checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED

    private fun contactIntent(ctx: Context, c: Slot.Contact): Intent? = when (c.action) {
        // Straight into the call with the phone-call permission; otherwise the dialer, number ready.
        Slot.Contact.CALL -> Intent(
            if (canCallDirectly(ctx)) Intent.ACTION_CALL else Intent.ACTION_DIAL,
            Uri.fromParts("tel", c.number, null),
        )
        Slot.Contact.SMS -> Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", c.number, null))
        Slot.Contact.WHATSAPP -> {
            val digits = e164(ctx, c.number).removePrefix("+")
            val pkg = whatsAppPackage(ctx)
            Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$digits")).apply { if (pkg != null) setPackage(pkg) }
        }
        else -> null
    }

    /** "+14155550100" style, using the phone's country for numbers saved without one. */
    fun e164(ctx: Context, number: String): String {
        if (number.trim().startsWith("+")) return "+" + number.filter { it.isDigit() }
        val tm = ctx.getSystemService(TelephonyManager::class.java)
        val iso = (tm?.networkCountryIso?.takeIf { it.isNotBlank() } ?: tm?.simCountryIso?.takeIf { it.isNotBlank() }
            ?: Locale.getDefault().country).uppercase()
        return PhoneNumberUtils.formatNumberToE164(number, iso) ?: number.filter { it.isDigit() || it == '+' }
    }

    fun whatsAppPackage(ctx: Context): String? =
        listOf(WHATSAPP, WHATSAPP_BUSINESS).firstOrNull { installed(ctx, it) }

    /** The app that does a contact action, for its icon on the person's picture. */
    fun actionPackage(ctx: Context, action: String): String? = when (action) {
        Slot.Contact.CALL -> ctx.packageManager.resolveActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:1")), 0)
            ?.activityInfo?.packageName
        Slot.Contact.SMS -> Telephony.Sms.getDefaultSmsPackage(ctx)
        Slot.Contact.WHATSAPP -> whatsAppPackage(ctx)
        else -> null
    }

    fun actionVerb(action: String) = when (action) {
        Slot.Contact.CALL -> "Call"
        Slot.Contact.SMS -> "Text"
        Slot.Contact.WHATSAPP -> "WhatsApp"
        else -> action
    }

    private fun installed(ctx: Context, pkg: String) =
        runCatching { ctx.packageManager.getPackageInfo(pkg, 0); true }.getOrDefault(false)
}
