package com.regolith.data.artwork

import com.regolith.domain.smb.SmbEntry

/**
 * What a fresh listing of a folder tells the artwork cache
 * ([ArtworkRepository.onFolderListed]): whether the folder's pictures, or
 * its videos' own, changed on the share and must be made again.
 *
 * A seam, the way `MomentFrames` is: the file operations ask for this check
 * after they rename, move or delete a picture, without their tests needing
 * the whole artwork pipeline. Bound to [ArtworkRepository] in `di/ArtworkModule`.
 */
fun interface FolderPictures {
    suspend fun onFolderListed(folderId: Long, entries: List<SmbEntry>)
}
