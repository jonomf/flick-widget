package com.jono.flick

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract.CommonDataKinds.Phone
import java.io.File
import java.util.UUID

/**
 * Adds a person: opens the system contact picker (Flick gets only the one number you pick, no
 * contacts permission), then asks Call / Text / WhatsApp. Calling straight away needs Android's
 * phone-call permission; it's asked here, with an explanation, and is optional.
 */
class PickContactActivity : Activity() {
    private var picked: Slot.Contact? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            val pick = Intent(Intent.ACTION_PICK).setType(Phone.CONTENT_TYPE)
            runCatching { startActivityForResult(pick, REQ_PICK) }.onFailure { done(null) }
        }
    }

    @Deprecated("Activity result API without androidx")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (requestCode != REQ_PICK || resultCode != RESULT_OK || uri == null) return done(null)
        var name = ""
        var number = ""
        var photoUri: String? = null
        runCatching {
            contentResolver.query(uri, arrayOf(Phone.DISPLAY_NAME, Phone.NUMBER, Phone.PHOTO_THUMBNAIL_URI), null, null, null)
                ?.use { c ->
                    if (c.moveToFirst()) {
                        name = c.getString(0).orEmpty()
                        number = c.getString(1).orEmpty()
                        photoUri = c.getString(2)
                    }
                }
        }
        if (number.isBlank()) return done(null)
        val photo = photoUri?.let { savePhoto(Uri.parse(it)) }
        chooseAction(name.ifBlank { number }, number, photo)
    }

    private fun chooseAction(name: String, number: String, photo: String?) {
        val actions = mutableListOf(Slot.Contact.CALL, Slot.Contact.SMS)
        if (Actions.whatsAppPackage(this) != null) actions += Slot.Contact.WHATSAPP
        AlertDialog.Builder(this)
            .setTitle(name)
            .setItems(actions.map { "${Actions.actionVerb(it)} $name" }.toTypedArray()) { _, which ->
                val c = Slot.Contact(name, number, actions[which], photo)
                if (c.action == Slot.Contact.CALL && !Actions.canCallDirectly(this)) askCallPermission(c) else done(c)
            }
            .setOnCancelListener { done(null) }
            .show()
    }

    private fun askCallPermission(c: Slot.Contact) {
        AlertDialog.Builder(this)
            .setTitle("Call straight away?")
            .setMessage(
                "To start the call as soon as you flick, Android needs to let Flick make phone calls. " +
                    "Its prompt says \"Allow Flick to make and manage phone calls?\". Flick only uses it to " +
                    "call the person you flick to.\n\nWithout it, the dialer opens with the number ready " +
                    "and you tap call.",
            )
            .setPositiveButton("Allow calls") { _, _ ->
                picked = c
                requestPermissions(arrayOf(Manifest.permission.CALL_PHONE), REQ_CALL)
            }
            .setNegativeButton("Use the dialer") { _, _ -> done(c) }
            .setOnCancelListener { done(c) }
            .show()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_CALL) done(picked)
    }

    /** Copies the contact's thumbnail into Flick's files (if Android lets us read it). */
    private fun savePhoto(uri: Uri): String? = runCatching {
        val bmp = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) } ?: return null
        val dir = File(filesDir, "people").apply { mkdirs() }
        val name = UUID.randomUUID().toString() + ".png"
        File(dir, name).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        name
    }.getOrNull()

    private fun done(c: Slot.Contact?) {
        val cb = pending
        pending = null
        cb?.invoke(c)
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    companion object {
        private const val REQ_PICK = 1
        private const val REQ_CALL = 2
        private var pending: ((Slot.Contact?) -> Unit)? = null

        /** Picks a person and action, then calls [onDone] with it (null if cancelled). */
        fun start(ctx: Context, onDone: (Slot.Contact?) -> Unit) {
            pending = onDone
            val i = Intent(ctx, PickContactActivity::class.java)
            if (ctx !is Activity) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(i)
        }

        fun photoFile(ctx: Context, name: String) = File(File(ctx.filesDir, "people"), name)
    }
}
