package com.regolith.data.repository

import android.util.Log
import com.regolith.data.db.FolderDao
import com.regolith.data.db.ServerDao
import com.regolith.data.db.ShareDao
import com.regolith.domain.library.MixMarker
import com.regolith.domain.library.MixOutcome
import com.regolith.domain.media.LocalSource
import com.regolith.domain.smb.ServerAccess
import com.regolith.domain.smb.SmbFailure
import com.regolith.domain.smb.SmbGateway
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Marks a folder as a mix, or back to a collection, by putting the marker
 * file on the share or taking it away ([MixMarker]). The share is the truth,
 * the way it is for posters; the folder's row is updated straight after so
 * the screen follows at once, and the next listing reads the same answer
 * back off the share.
 */
@Singleton
class MixRepository @Inject constructor(
    private val folderDao: FolderDao,
    private val shareDao: ShareDao,
    private val serverDao: ServerDao,
    private val access: ServerAccess,
    private val gateway: SmbGateway,
) {
    private companion object {
        const val TAG = "Regolith/Mix"
    }

    /** Whether [folderId] is marked as a mix, live. False for a folder that is gone. */
    fun observeIsMix(folderId: Long): Flow<Boolean> =
        folderDao.observe(folderId).map { it?.mix == true }.distinctUntilChanged()

    /**
     * Mark [folderId] as a mix ([mix] true: write the empty marker, hidden)
     * or as a collection again ([mix] false: delete it). Both are safe to
     * repeat: writing over an empty marker, or deleting one that is not
     * there, changes nothing.
     */
    suspend fun setMix(folderId: Long, mix: Boolean): MixOutcome {
        val folder = folderDao.byId(folderId) ?: return MixOutcome.FAILED
        val share = shareDao.byId(folder.shareId) ?: return MixOutcome.FAILED
        val server = serverDao.byId(share.serverId) ?: return MixOutcome.FAILED
        // The phone's own videos and the demo library have no share to write to.
        if (LocalSource.isLocal(server.host)) return MixOutcome.FAILED
        val host = access.hostFor(server.id)
        val creds = access.credentialsFor(server.id)
        val path = MixMarker.pathIn(folder.relPath)
        return try {
            if (mix) {
                gateway.write(host, creds, share.name, path, ByteArray(0))
                gateway.hide(host, creds, share.name, path)
            } else {
                gateway.delete(host, creds, share.name, path)
            }
            folderDao.setMix(folderId, mix)
            Log.i(TAG, "${folder.relPath.ifEmpty { "/" }} marked as ${if (mix) "a mix" else "a collection"}")
            MixOutcome.DONE
        } catch (e: SmbFailure.Forbidden) {
            MixOutcome.READ_ONLY
        } catch (e: SmbFailure.AuthFailed) {
            // Signed in fine, refused the write: the same thing to the user.
            MixOutcome.READ_ONLY
        } catch (e: SmbFailure.Unreachable) {
            MixOutcome.UNREACHABLE
        } catch (e: SmbFailure) {
            Log.w(TAG, "mix mark for ${folder.relPath.ifEmpty { "/" }} not changed: $e")
            MixOutcome.FAILED
        }
    }
}
