package com.regolith.data.connection

import com.regolith.data.db.FolderDao
import com.regolith.data.db.MediaFileDao
import com.regolith.data.db.ServerDao
import com.regolith.data.db.ShareDao
import com.regolith.data.db.ShareEntity
import com.regolith.data.prefs.AppPreferences
import com.regolith.domain.media.LocalSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Which share the connection dot watches: "the one you're browsing" (the
 * owner's rule). That is the share behind the last folder opened in Browse
 * or the Library, or the last video opened, remembered across launches.
 * Before anything has been opened it is the first enabled share on a real
 * server. A source on the phone (the demo library, "On this device") is
 * never watched: it has no network behind it, so it has no dot.
 *
 * Screens report what they open with [onFolder] and [onFile]; nothing else
 * changes the focus, so a background scan of another server never moves it.
 */
@Singleton
class ConnectionFocus @Inject constructor(
    private val prefs: AppPreferences,
    private val serverDao: ServerDao,
    private val shareDao: ShareDao,
    private val folderDao: FolderDao,
    private val fileDao: MediaFileDao,
) {
    /** The share being watched, or null when there is no share on the network at all. */
    val share: Flow<ShareEntity?> = combine(prefs.focusShareId, shareDao.observeEvery(), serverDao.observeAll()) { id, shares, servers ->
        val networked = servers.filterNot { LocalSource.isLocal(it.host) }.map { it.id }.toSet()
        val candidates = shares.filter { it.enabled && it.serverId in networked }
        candidates.firstOrNull { it.id == id } ?: candidates.firstOrNull()
    }.distinctUntilChanged()

    /** A folder was opened: watch its share. */
    suspend fun onFolder(folderId: Long) {
        folderDao.byId(folderId)?.let { focus(it.shareId) }
    }

    /** A video was opened: watch its share. */
    suspend fun onFile(fileId: Long) {
        fileDao.byId(fileId)?.let { focus(it.shareId) }
    }

    private suspend fun focus(shareId: Long) {
        val share = shareDao.byId(shareId) ?: return
        val server = serverDao.byId(share.serverId) ?: return
        if (LocalSource.isLocal(server.host)) return
        prefs.setFocusShareId(shareId)
    }
}
