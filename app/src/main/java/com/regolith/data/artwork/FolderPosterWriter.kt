package com.regolith.data.artwork

import com.regolith.data.smb.writeReplacing
import com.regolith.domain.artwork.ExistingArtwork
import com.regolith.domain.artwork.FolderPoster
import com.regolith.domain.smb.SmbCredentials
import com.regolith.domain.smb.SmbFailure
import com.regolith.domain.smb.SmbGateway
import com.regolith.domain.smb.SmbHost
import javax.inject.Inject

/**
 * Puts a folder poster on the share (P19): [FolderPoster.NAME] in the folder
 * (or `poster.gif` for one that moves, [com.regolith.domain.artwork.PickedPoster]),
 * with the pictures it takes over from deleted or renamed out of its way.
 *
 * The steps are ordered so that a failure part-way loses nothing:
 *
 * - **Replace** writes the poster first, through [writeReplacing], so it
 *   goes over an old `poster.jpg` in one step and a dropped connection never
 *   leaves half a picture. Only then are the other pictures deleted. Failing
 *   before the swap leaves the folder as it was; failing after it leaves at
 *   worst an old `folder.jpg` beside the new poster, which still wins.
 * - **Keep** renames the old pictures first, since one of them may be called
 *   `poster.jpg` itself, then writes the poster. If the write fails the
 *   renamed pictures are put back, so the folder keeps the artwork it had.
 *
 * Web analogy: a small transaction against a file API that has none,
 * written as do-then-undo by hand.
 */
class FolderPosterWriter @Inject constructor(private val gateway: SmbGateway) {

    /** What the share looks like afterwards: the pictures renamed (old to new name) and deleted. */
    data class Done(val renamed: Map<String, String>, val deleted: List<String>)

    /**
     * Write [bytes] as the poster of the folder at [folderRelPath] (`""` for
     * the share root), called [name], doing [existing] with the pictures
     * already there.
     *
     * @throws SmbFailure when the share refuses or drops, after putting back
     *   anything it had already moved.
     */
    suspend fun write(
        host: SmbHost,
        credentials: SmbCredentials,
        share: String,
        folderRelPath: String,
        bytes: ByteArray,
        existing: ExistingArtwork,
        name: String = FolderPoster.NAME,
    ): Done {
        fun path(name: String) = if (folderRelPath.isEmpty()) name else "$folderRelPath/$name"
        val entries = gateway.list(host, credentials, share, folderRelPath)
        val old = FolderPoster.existing(entries).map { it.name }
        return when (existing) {
            ExistingArtwork.REPLACE -> {
                gateway.writeReplacing(host, credentials, share, path(name), bytes)
                // A picture of the same name is gone already: the write went over it.
                val others = old.filterNot { it.equals(name, ignoreCase = true) }
                for (name in others) gateway.delete(host, credentials, share, path(name))
                Done(renamed = emptyMap(), deleted = others)
            }
            ExistingArtwork.KEEP -> {
                val names = FolderPoster.keptNames(old, entries.map { it.name }, name)
                val moved = mutableListOf<Pair<String, String>>()
                try {
                    for ((from, to) in names) {
                        gateway.rename(host, credentials, share, path(from), path(to))
                        moved += from to to
                    }
                    gateway.writeReplacing(host, credentials, share, path(name), bytes)
                } catch (e: SmbFailure) {
                    // Newest first, so a chain of renames unwinds in order.
                    for ((from, to) in moved.asReversed()) {
                        runCatching { gateway.rename(host, credentials, share, path(to), path(from)) }
                    }
                    throw e
                }
                Done(renamed = names, deleted = emptyList())
            }
        }
    }
}
