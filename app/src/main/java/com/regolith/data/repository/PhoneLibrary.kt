package com.regolith.data.repository

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.IntentSender
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.ContextCompat
import com.regolith.data.db.FolderDao
import com.regolith.data.db.FolderEntity
import com.regolith.data.db.MediaFileDao
import com.regolith.data.db.MediaFileEntity
import com.regolith.data.db.ShareDao
import com.regolith.data.prefs.AppPreferences
import com.regolith.domain.library.TitleParser
import com.regolith.domain.media.DeviceSource
import com.regolith.domain.media.PhoneAccess
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The videos the phone already had: Camera, Movies, Download, Telegram.
 *
 * **Where they live in the model.** A second share, "Phone storage", on the
 * synthetic "This device" server ([DeviceSource.PHONE_SHARE]), with one
 * folder row per phone directory and one `media_files` row per video. So a
 * phone video has a `fileId` like any other, and resume points, chapters,
 * artwork, scrub thumbnails, Search and Continue watching all work without
 * learning a new kind of file. Rows are keyed by the absolute path minus
 * its leading slash, which is what uniquely names a file across the
 * internal storage and an SD card, and what the player opens: with
 * READ_MEDIA_VIDEO granted, Android lets an app read media files by path,
 * so [file] hands back a plain [File] and [com.regolith.player.LocalMedia]
 * plays it exactly as it plays a download.
 *
 * **Where they come from.** MediaStore — Android's own index of the media
 * on the phone, kept by the system — rather than walking the file system:
 * it is already there, it covers every storage volume, and it is what the
 * permission grants access to. One query sees the whole share, so a sync
 * is a diff against the rows already here: new and changed files are
 * written, the rest are left alone, and anything MediaStore no longer
 * lists is marked `missing` (never deleted — guardrail G3), which is also
 * how "Hide from Regolith" and a switched-off folder keep their resume
 * points for the day they come back.
 *
 * **When it syncs.** On app start, when access changes, when the device
 * tab comes back into view, and whenever MediaStore reports a change while
 * the app is running (a [ContentObserver], debounced).
 *
 * Web analogy: a sync worker mirroring a third-party index into your own
 * table, with soft deletes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class PhoneLibrary @Inject constructor(
    @ApplicationContext private val context: Context,
    private val shareDao: ShareDao,
    private val folderDao: FolderDao,
    private val mediaFileDao: MediaFileDao,
    private val deviceLibrary: DeviceLibrary,
    private val prefs: AppPreferences,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private var pending: Job? = null
    private var observing = false

    private val _access = MutableStateFlow(readAccess())

    /** What the person has allowed. Re-read by [refreshAccess]; the permission can change while the app is away. */
    val access: StateFlow<PhoneAccess> = _access

    private val shareId = MutableStateFlow<Long?>(null)

    init {
        scope.launch { shareId.value = shareDao.idByHost(DeviceSource.HOST, DeviceSource.PHONE_SHARE) }
    }

    /** The phone's folders, one row per directory that holds (or held) a video. Counts include hidden videos. */
    fun observeFolders(): Flow<List<FolderEntity>> = shareId.flatMapLatest { id ->
        if (id == null) flowOf(emptyList()) else folderDao.observeInShares(listOf(id)).map { list -> list.filter { it.parentId != null } }
    }

    /** Every phone video that is showing: not gone, not hidden. */
    fun observeFiles(): Flow<List<MediaFileEntity>> = shareId.flatMapLatest { id ->
        if (id == null) flowOf(emptyList()) else mediaFileDao.observeInShares(listOf(id))
    }

    /**
     * Read the permission again, and sync if it changed. Called on resume:
     * the system dialog, or Android's app settings, can change it while
     * Regolith is in the background, and nothing tells us.
     */
    fun refreshAccess() {
        val now = readAccess()
        if (now != _access.value) {
            _access.value = now
            requestSync()
        }
        if (now != PhoneAccess.NONE) observe()
    }

    /** Sync soon. Repeated calls inside [delayMs] collapse into one pass. */
    fun requestSync(delayMs: Long = 0) {
        pending?.cancel()
        pending = scope.launch {
            delay(delayMs)
            // A newer request cancelling this one is the debounce working, not a failure.
            runCatching { sync() }.onFailure { if (it !is kotlinx.coroutines.CancellationException) Log.w(TAG, "phone sync failed", it) }
        }
    }

    /** Mirror MediaStore into the phone share. Safe to call at any time; one pass at a time. */
    suspend fun sync() = mutex.withLock {
        withContext(Dispatchers.IO) {
            val access = readAccess()
            _access.value = access
            val existingShare = shareDao.idByHost(DeviceSource.HOST, DeviceSource.PHONE_SHARE)
            if (access == PhoneAccess.NONE) {
                // Unreadable now, so unplayable: hide the lot, keep the rows.
                existingShare?.let { mediaFileDao.markMissingExcept(it, emptyList()) }
                shareId.value = existingShare
                return@withContext
            }
            observe()
            val videos = query()
            if (videos.isEmpty() && existingShare == null) return@withContext

            val root = deviceLibrary.phoneRootFolder()
            shareId.value = root.shareId
            val hiddenFolders = prefs.hiddenPhoneFolders.first()
            val hiddenFiles = prefs.hiddenPhoneFiles.first()
            val existing = mediaFileDao.allInShare(root.shareId).associateBy { it.relPath }
            val now = System.currentTimeMillis()
            val keep = mutableListOf<Long>()
            val byDir = videos.groupBy { it.dir }

            for ((dir, inDir) in byDir) {
                val folder = folderDao.upsert(
                    FolderEntity(
                        shareId = root.shareId,
                        parentId = root.id,
                        relPath = dir,
                        name = inDir.first().bucket ?: dir.substringAfterLast('/'),
                        fileCount = inDir.size,
                        byteCount = inDir.sumOf { it.sizeBytes },
                        lastListedAtMs = now,
                    ),
                )
                if (dir in hiddenFolders) continue
                for (video in inDir) {
                    if (video.relPath in hiddenFiles) continue
                    val row = existing[video.relPath]
                    // Unchanged and showing: nothing to write. This is what
                    // keeps a sync of two thousand camera clips cheap.
                    if (row != null && !row.missing && row.sizeBytes == video.sizeBytes && row.modifiedAtMs == video.modifiedAtMs && row.folderId == folder.id) {
                        keep += row.id
                        continue
                    }
                    val parsed = TitleParser.parseVideoName(video.name)
                    keep += mediaFileDao.upsert(
                        MediaFileEntity(
                            shareId = root.shareId,
                            folderId = folder.id,
                            relPath = video.relPath,
                            name = video.name,
                            ext = video.name.substringAfterLast('.', ""),
                            sizeBytes = video.sizeBytes,
                            modifiedAtMs = video.modifiedAtMs,
                            durationMs = video.durationMs,
                            missing = false,
                            addedAtMs = video.addedAtMs,
                            lastSeenAtMs = now,
                            // MediaStore's own reading, good enough for the
                            // resolution chip until the probe measures it.
                            width = video.width,
                            height = video.height,
                            rotationDegrees = video.rotation,
                            titleParsed = parsed.title,
                            year = parsed.year,
                            season = parsed.season,
                            episode = parsed.episode,
                        ),
                    ).id
                }
            }
            // A directory MediaStore no longer lists keeps its row (its id is
            // what the hidden-folder choice and the files hang off) with
            // nothing in it; the UI draws only folders that hold something.
            for (folder in folderDao.children(root.id)) {
                if (folder.relPath !in byDir && folder.fileCount != 0) folderDao.update(folder.copy(fileCount = 0, byteCount = 0, lastListedAtMs = now))
            }
            mediaFileDao.markMissingExcept(root.shareId, keep)
            Log.i(TAG, "synced ${videos.size} videos in ${byDir.size} folders ($access), showing ${keep.size}")
        }
    }

    /** The bytes of a phone video, or null when [fileId] is not one. Blocking: the player's loader thread asks. */
    fun fileBlocking(fileId: Long): File? {
        val phoneShare = shareDao.idByHostBlocking(DeviceSource.HOST, DeviceSource.PHONE_SHARE) ?: return null
        val row = mediaFileDao.byIdBlocking(fileId) ?: return null
        return if (row.shareId == phoneShare) File("/" + row.relPath) else null
    }

    suspend fun file(fileId: Long): File? = withContext(Dispatchers.IO) { fileBlocking(fileId) }

    /** True when [fileId] is a phone video rather than a share file or a download. */
    suspend fun isPhoneFile(fileId: Long): Boolean {
        val phoneShare = shareDao.idByHost(DeviceSource.HOST, DeviceSource.PHONE_SHARE) ?: return false
        return mediaFileDao.byId(fileId)?.shareId == phoneShare
    }

    /** "Hide from Regolith": the file stays where it is; its row goes missing and keeps its resume point. */
    suspend fun hideFile(fileId: Long) {
        val row = mediaFileDao.byId(fileId) ?: return
        prefs.setPhoneFileHidden(row.relPath, true)
        mediaFileDao.update(row.copy(missing = true))
    }

    /** Settings › Phone folders. Syncs straight away so the folder's videos come or go as the switch moves. */
    suspend fun setFolderHidden(relPath: String, hidden: Boolean) {
        prefs.setPhoneFolderHidden(relPath, hidden)
        sync()
    }

    /**
     * The system's own "Allow Regolith to delete this video?" sheet.
     *
     * An app that did not create a file may not delete it directly under
     * scoped storage; it asks MediaStore for a request the person approves.
     * The screen launches the returned [IntentSender] and calls [sync] when
     * it comes back approved. Null when MediaStore no longer knows the file.
     */
    suspend fun deleteRequest(fileId: Long): IntentSender? = withContext(Dispatchers.IO) {
        val uri = contentUriFor(fileId) ?: return@withContext null
        MediaStore.createDeleteRequest(context.contentResolver, listOf(uri)).intentSender
    }

    @Suppress("DEPRECATION") // DATA is deprecated for writing, still the documented way to read a path.
    private suspend fun contentUriFor(fileId: Long): Uri? {
        val row = mediaFileDao.byId(fileId) ?: return null
        val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        context.contentResolver.query(
            collection, arrayOf(MediaStore.Video.Media._ID),
            "${MediaStore.Video.Media.DATA} = ?", arrayOf("/" + row.relPath), null,
        )?.use { c -> if (c.moveToFirst()) return ContentUris.withAppendedId(collection, c.getLong(0)) }
        return null
    }

    /** One video as MediaStore describes it, already in our path scheme. */
    private data class PhoneVideo(
        val relPath: String,
        val dir: String,
        val name: String,
        val bucket: String?,
        val sizeBytes: Long,
        val modifiedAtMs: Long,
        val addedAtMs: Long,
        val durationMs: Long?,
        val width: Int?,
        val height: Int?,
        val rotation: Int?,
    )

    @Suppress("DEPRECATION")
    private fun query(): List<PhoneVideo> {
        val projection = arrayOf(
            MediaStore.Video.Media.DATA,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DATE_MODIFIED,
            MediaStore.Video.Media.DATE_ADDED,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.WIDTH,
            MediaStore.Video.Media.HEIGHT,
            MediaStore.Video.Media.ORIENTATION,
        )
        val out = mutableListOf<PhoneVideo>()
        context.contentResolver.query(
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL), projection, null, null, null,
        )?.use { c ->
            fun long(i: Int) = if (c.isNull(i)) null else c.getLong(i)
            fun int(i: Int) = if (c.isNull(i)) null else c.getInt(i)
            while (c.moveToNext()) {
                val path = c.getString(0)?.takeIf { it.startsWith("/") } ?: continue
                val relPath = path.removePrefix("/")
                val dir = relPath.substringBeforeLast('/', "")
                if (dir.isEmpty()) continue
                out += PhoneVideo(
                    relPath = relPath,
                    dir = dir,
                    name = c.getString(1) ?: relPath.substringAfterLast('/'),
                    bucket = c.getString(2),
                    sizeBytes = long(3) ?: 0L,
                    // MediaStore dates are in SECONDS.
                    modifiedAtMs = (long(4) ?: 0L) * 1000,
                    addedAtMs = (long(5) ?: 0L) * 1000,
                    durationMs = long(6)?.takeIf { it > 0 },
                    width = int(7)?.takeIf { it > 0 },
                    height = int(8)?.takeIf { it > 0 },
                    rotation = int(9),
                )
            }
        }
        return out
    }

    private fun readAccess(): PhoneAccess {
        fun granted(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
        return when {
            granted(Manifest.permission.READ_MEDIA_VIDEO) -> PhoneAccess.FULL
            granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) -> PhoneAccess.PARTIAL
            else -> PhoneAccess.NONE
        }
    }

    /**
     * Listen for MediaStore changes while the process lives. A new camera
     * clip or a finished browser download shows up without a pull to refresh.
     * Registered once, only after access exists (before that there is nothing
     * we could read anyway).
     */
    @Synchronized
    private fun observe() {
        if (observing) return
        observing = true
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            // MediaStore fires several times for one new file (insert, then
            // the scan filling it in), so wait for it to settle.
            override fun onChange(selfChange: Boolean) = requestSync(delayMs = 1_500)
        }
        context.contentResolver.registerContentObserver(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL), true, observer)
    }

    private companion object {
        const val TAG = "Regolith/Phone"
    }
}
