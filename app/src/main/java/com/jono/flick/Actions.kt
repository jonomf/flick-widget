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
    fun run(ctx: Context, item: Slot, opts: Bundle? = null): Boolean {
        val intent = when (item) {
            is Slot.App -> ctx.packageManager.getLaunchIntentForPackage(item.pkg)
            is Slot.Contact -> contactIntent(ctx, item)
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
