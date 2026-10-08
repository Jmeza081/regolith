package com.regolith.data.transfer

import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import androidx.core.net.toUri
import com.regolith.domain.fileops.FileNames
import com.regolith.domain.transfer.PickedFile
import com.regolith.domain.transfer.UploadNames
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The phone's side of an upload: reading files another app handed us.
 *
 * A picked file is not a path. The photo picker and the system file picker
 * hand back a `content://` address — an opaque handle, a bit like a blob
 * URL on the web — that only works through the app that owns the file, and
 * only while we hold permission to read it. An interface so the upload
 * engine is tested with byte arrays rather than a real ContentResolver.
 */
interface PhoneFiles {
    /** Name, size and date of a picked file, or null when it cannot be read at all. */
    fun describe(uri: String): PickedFile?

    /**
     * Open [uri] for reading from byte [offset]. Throws [SourceGone] when the
     * file, or our permission to read it, has gone, and [SourceChanged] when
     * it now holds fewer than [offset] bytes — it was replaced since the
     * upload began, so the bytes already sent belong to something else.
     */
    fun open(uri: String, offset: Long): InputStream

    /**
     * Keep read access after the app closes — the queue may run hours later,
     * or after a reboot. The pickers' grants last only until the app stops
     * by default. False when the provider will not allow it; the upload
     * still runs, it just cannot outlive the app.
     */
    fun keepAccess(uri: String): Boolean

    /** Hand access back once no upload needs it. Grants are capped per app, so they are not hoarded. */
    fun releaseAccess(uri: String)

    /** Every address we still hold read access to. */
    fun heldAccess(): List<String>
}

/** The file, or our permission to read it, is gone. Nothing to retry. */
class SourceGone(uri: String, cause: Throwable? = null) : IOException("No longer readable on the phone: $uri", cause)

/** The file is shorter than the bytes already sent: it was replaced mid-upload, and has to start over. */
class SourceChanged(uri: String) : IOException("Changed on the phone since the upload began: $uri")

@Singleton
class ContentPhoneFiles @Inject constructor(
    @ApplicationContext private val context: Context,
) : PhoneFiles {
    private val resolver get() = context.contentResolver

    override fun describe(uri: String): PickedFile? = try {
        val address = uri.toUri()
        var name: String? = null
        var size = -1L
        var modified: Long? = null
        var taken: Long? = null
        // No projection: providers disagree about which columns exist, and
        // asking for one a provider lacks can throw rather than return null.
        resolver.query(address, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                name = c.stringOrNull(OpenableColumns.DISPLAY_NAME)
                size = c.longOrNull(OpenableColumns.SIZE) ?: -1L
                // A document says when it changed (ms); a photo says when it
                // was taken (ms); a MediaStore row says when it changed, in SECONDS.
                taken = c.longOrNull(MediaStore.MediaColumns.DATE_TAKEN)
                modified = c.longOrNull(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                    ?: taken
                    ?: c.longOrNull(MediaStore.MediaColumns.DATE_MODIFIED)?.times(1000)
            }
        }
        val display = name ?: address.lastPathSegment?.substringAfterLast('/') ?: "upload"
        // The photo picker hides the real name behind a number of its own;
        // give a camera shot back the name its capture time made (P20).
        val named = if (uri.startsWith(PHOTO_PICKER)) UploadNames.fromPhotoPicker(display, taken, ZoneId.systemDefault()) else display
        PickedFile(uri, FileNames.sanitize(named), size, modified?.takeIf { it > 0 })
    } catch (e: SecurityException) {
        Log.w(TAG, "no access to describe $uri", e)
        null
    } catch (e: IllegalArgumentException) {
        Log.w(TAG, "could not describe $uri", e)
        null
    }

    override fun open(uri: String, offset: Long): InputStream {
        val stream = try {
            resolver.openInputStream(uri.toUri()) ?: throw SourceGone(uri)
        } catch (e: FileNotFoundException) {
            throw SourceGone(uri, e)
        } catch (e: SecurityException) {
            throw SourceGone(uri, e)
        }
        if (offset <= 0) return stream
        try {
            // Most providers hand over a real file descriptor, which seeks for
            // free; a cloud document may be a pipe, which has to be read past.
            if (stream is FileInputStream) {
                val channel = stream.channel
                val size = runCatching { channel.size() }.getOrDefault(0L)
                if (size > 0) {
                    if (offset > size) throw SourceChanged(uri)
                    channel.position(offset)
                    return stream
                }
            }
            skipFully(stream, offset, uri)
            return stream
        } catch (e: IOException) {
            runCatching { stream.close() }
            throw e
        }
    }

    /** `skip` may skip less than asked, or nothing, without being at the end; a read tells the two apart. */
    private fun skipFully(stream: InputStream, bytes: Long, uri: String) {
        var left = bytes
        while (left > 0) {
            val n = stream.skip(left)
            if (n > 0) {
                left -= n
                continue
            }
            if (stream.read() < 0) throw SourceChanged(uri)
            left--
        }
    }

    override fun keepAccess(uri: String): Boolean = try {
        resolver.takePersistableUriPermission(uri.toUri(), Intent.FLAG_GRANT_READ_URI_PERMISSION)
        true
    } catch (e: SecurityException) {
        Log.i(TAG, "access to $uri cannot outlive the app: ${e.message}")
        false
    }

    override fun releaseAccess(uri: String) {
        runCatching { resolver.releasePersistableUriPermission(uri.toUri(), Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }

    override fun heldAccess(): List<String> =
        resolver.persistedUriPermissions.filter { it.isReadPermission }.map { it.uri.toString() }

    private fun Cursor.stringOrNull(column: String): String? =
        getColumnIndex(column).takeIf { it >= 0 && !isNull(it) }?.let { getString(it) }

    private fun Cursor.longOrNull(column: String): Long? =
        getColumnIndex(column).takeIf { it >= 0 && !isNull(it) }?.let { getLong(it) }

    private companion object {
        const val TAG = "Regolith/Upload"

        /** What Android's photo picker hands back: `content://media/picker/0/…`. */
        const val PHOTO_PICKER = "content://media/picker"
    }
}
