package com.regolith.data.repository

import com.regolith.data.db.FolderEntity
import com.regolith.data.db.MediaFileEntity
import com.regolith.data.db.ShareFileEntity

/**
 * The folder and file reads a screen's file actions need
 * (`ui/util/FileActions`): names for the dialogs, what a delete would reach,
 * the move sheet's tree. All of it is already on the device; none of it goes
 * to the share.
 *
 * A seam, the way `ServerAccess` and `MomentFrames` are: [LibraryRepository]
 * is the app's, bound in `di/LibraryModule`, and a test builds one over an
 * in-memory database without the rest of the library behind it.
 */
interface FolderLookup {
    suspend fun folder(folderId: Long): FolderEntity?

    suspend fun file(fileId: Long): MediaFileEntity?

    /** A file that is not a video, as Browse lists it. */
    suspend fun other(otherId: Long): ShareFileEntity?

    /** Every file that is not a video directly in [folderId]: its pictures, among them the one it wears as its poster. */
    suspend fun othersIn(folderId: Long): List<ShareFileEntity>

    /** Every present video in the subtree of [folderId]. */
    suspend fun filesUnder(folderId: Long): List<MediaFileEntity>

    /** The folders directly inside [folderId], by name. */
    suspend fun subfolders(folderId: Long): List<FolderEntity>

    /** The row for a share's own root, made on first use. */
    suspend fun rootFolder(shareId: Long): FolderEntity

    /** "TOWER · media". */
    suspend fun shareLabel(shareId: Long): String

    /** How many companion files go with [fileIds] when they go together (`Companions`). */
    suspend fun companionCount(fileIds: Collection<Long>): Int
}
