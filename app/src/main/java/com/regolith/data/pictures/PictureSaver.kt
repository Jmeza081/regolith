package com.regolith.data.pictures

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.regolith.data.db.ShareFileDao
import com.regolith.domain.media.PictureSaves
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Save to phone (P20): pictures from the share copied into the phone's own
 * gallery, in Pictures › Regolith, where every other app can use them — the
 * owner's call over Regolith's downloads, which keep videos to watch away
 * from the share.
 *
 * Through MediaStore, Android's index of the phone's media (web analogy: a
 * shared, system-owned bucket every app can list). Since Android 10 an app
 * may add its own files there with no permission at all. Each one is written
 * "pending", invisible to other apps, and published only once all of it is
 * there, so the gallery never shows half a picture. The bytes are the
 * picture's own, fetched whole ([PictureOriginals]): nothing is re-encoded,
 * and its name and date come along.
 *
 * App-scoped rather than screen-scoped, so a big batch carries on when the
 * album is left, and says how it went wherever the user is by then
 * ([notices]).
 */
@Singleton
class PictureSaver @Inject constructor(
    @ApplicationContext private val context: Context,
    private val originals: PictureOriginals,
    private val shareFileDao: ShareFileDao,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _notices = MutableSharedFlow<PictureSaves.Message>(extraBufferCapacity = 4)

    /** One line per batch, for the app's message capsule. */
    val notices: SharedFlow<PictureSaves.Message> = _notices.asSharedFlow()

    /** Copy [pictureIds] into the gallery, one after another, and say how it went. */
    fun save(pictureIds: List<Long>) {
        if (pictureIds.isEmpty()) return
        scope.launch {
            val saved = pictureIds.count { saveOne(it) }
            PictureSaves.message(saved, pictureIds.size)?.let { _notices.tryEmit(it) }
        }
    }

    private suspend fun saveOne(pictureId: Long): Boolean {
        val row = shareFileDao.byId(pictureId) ?: return false
        val file = originals.file(pictureId) ?: return false
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, row.name)
            PictureOriginalFetcher.mimeTypeOf(file.extension)?.let { put(MediaStore.Images.Media.MIME_TYPE, it) }
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/${PictureSaves.ALBUM}")
            row.takenAtMs?.let { put(MediaStore.Images.Media.DATE_TAKEN, it) }
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        // A name already in the album is given a number by Android, not written over.
        val uri = resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values) ?: return false
        return try {
            val out = resolver.openOutputStream(uri) ?: throw IOException("no stream for $uri")
            out.use { stream -> file.inputStream().use { it.copyTo(stream) } }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            true
        } catch (e: Exception) {
            Log.w(TAG, "${row.name} not saved to the gallery: $e")
            runCatching { resolver.delete(uri, null, null) }
            false
        }
    }

    private companion object {
        const val TAG = "Regolith/Pictures"
    }
}
