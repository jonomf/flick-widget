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
            if (intent.getBooleanExtra(EXTRA_PHOTO_ONLY, false)) {
                choosePicture()
            } else {
                val pick = Intent(Intent.ACTION_PICK).setType(Phone.CONTENT_TYPE)
                runCatching { startActivityForResult(pick, REQ_PICK) }.onFailure { done(null) }
            }
        }
    }

    @Deprecated("Activity result API without androidx")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_IMAGE) {
            val img = data?.data
            val saved = if (resultCode == RESULT_OK && img != null) savePhoto(img) else null
            val c = picked
            if (photoOnly) return photoDone(saved)
            return done(if (c != null && saved != null) Slot.Contact(c.name, c.number, c.action, saved) else c)
        }
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
                if (c.action == Slot.Contact.CALL && !Actions.canCallDirectly(this)) askCallPermission(c) else finishPerson(c)
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
            .setNegativeButton("Use the dialer") { _, _ -> finishPerson(c) }
            .setOnCancelListener { finishPerson(c) }
            .show()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_CALL) picked?.let { finishPerson(it) } ?: done(null)
    }

    /** Last step: no photo from the contact → offer to pick one (or use their initials). */
    private fun finishPerson(c: Slot.Contact) {
        if (c.photo != null) return done(c)
        picked = c
        AlertDialog.Builder(this)
            .setTitle("Picture for ${c.name}")
            .setMessage("Their contact photo isn't available to Flick. Pick a picture, or use their initials.")
            .setPositiveButton("Choose picture") { _, _ -> openImagePicker() }
            .setNegativeButton("Use initials") { _, _ -> done(c) }
            .setOnCancelListener { done(c) }
            .show()
    }

    private val photoOnly get() = intent.getBooleanExtra(EXTRA_PHOTO_ONLY, false)

    private fun choosePicture() = openImagePicker()

    /** Android's photo picker (no permission needed); the older file picker if it's missing. */
    private fun openImagePicker() {
        val picker = Intent(android.provider.MediaStore.ACTION_PICK_IMAGES).setType("image/*")
        val fallback = Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE)
        runCatching { startActivityForResult(picker, REQ_IMAGE) }.recoverCatching { startActivityForResult(fallback, REQ_IMAGE) }
            .onFailure { if (photoOnly) photoDone(null) else done(picked) }
    }

    private fun photoDone(name: String?) {
        val cb = pendingPhoto
        pendingPhoto = null
        cb?.invoke(name)
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    /** Copies the contact's thumbnail into Flick's files (if Android lets us read it). */
    private fun savePhoto(uri: Uri): String? = runCatching {
        val raw = contentResolver.openInputStream(uri)?.use { decodeSmall(it, uri) } ?: return null
        // Centre square, 256 px: enough for every size it's drawn at.
        val side = minOf(raw.width, raw.height)
        val sq = Bitmap.createBitmap(raw, (raw.width - side) / 2, (raw.height - side) / 2, side, side)
        val bmp = Bitmap.createScaledBitmap(sq, 256, 256, true)
        val dir = File(filesDir, "people").apply { mkdirs() }
        val name = UUID.randomUUID().toString() + ".png"
        File(dir, name).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        name
    }.getOrNull()

    /** Decodes at a reduced size (camera photos are huge). */
    private fun decodeSmall(first: java.io.InputStream, uri: Uri): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeStream(first, null, bounds)
        var sample = 1
        while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 256) sample *= 2
        return contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        }
    }

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
        private const val REQ_IMAGE = 3
        private const val EXTRA_PHOTO_ONLY = "photo_only"
        private var pendingPhoto: ((String?) -> Unit)? = null

        /** Picks a picture (for an existing person); [onDone] gets the saved file name, or null. */
        fun pickPhoto(ctx: Context, onDone: (String?) -> Unit) {
            pendingPhoto = onDone
            val i = Intent(ctx, PickContactActivity::class.java).putExtra(EXTRA_PHOTO_ONLY, true)
            if (ctx !is Activity) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(i)
        }
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
