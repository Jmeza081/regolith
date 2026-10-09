package com.regolith.data.artwork

import com.regolith.data.smb.writeReplacing
import com.regolith.domain.artwork.FolderPoster
import com.regolith.domain.smb.SmbCredentials
import com.regolith.domain.smb.SmbFailure
import com.regolith.domain.smb.SmbGateway
import com.regolith.domain.smb.SmbHost
import java.time.LocalDate
import javax.inject.Inject

/**
 * Puts a folder poster on the share: [FolderPoster.NAME] in the folder (or
 * `poster.gif` for one that moves, [com.regolith.domain.artwork.PickedPoster]),
 * with the pictures it takes over from kept under dated names
 * ([FolderPoster.keptNames]: `poster.jpg` becomes `poster (8 Oct).jpg`).
 * Nothing on the share is ever deleted to make room for a poster (the
 * owner's call, 2026-10-08).
 *
 * The old pictures are renamed first, since one of them may be called
 * `poster.jpg` itself, and only then is the new poster put in place: written
 * through [writeReplacing], so a dropped connection never leaves half a
 * picture, or — for a picture already in the folder used whole — renamed
 * into place ([adopt]). If that last step fails the renamed pictures are put
 * back, so the folder keeps the artwork it had.
 *
 * Web analogy: a small transaction against a file API that has none,
 * written as do-then-undo by hand.
 */
class FolderPosterWriter @Inject constructor(private val gateway: SmbGateway) {

    /** What the share looks like afterwards: the pictures renamed out of the way, old name to new. */
    data class Done(val renamed: Map<String, String>)

    /**
     * Write [bytes] as the poster of the folder at [folderRelPath] (`""` for
     * the share root), called [name]. [on] is the day the kept pictures are
     * named for; [leave] is a picture the poster was made from, which stays
     * as it is ([FolderPoster.keeping]).
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
        on: LocalDate,
        name: String = FolderPoster.NAME,
        leave: String? = null,
    ): Done = keepingOld(host, credentials, share, folderRelPath, on, name, leave) { path ->
        gateway.writeReplacing(host, credentials, share, path(name), bytes)
    }

    /**
     * Make [pictureName], a picture already in the folder, its poster by
     * renaming it [name] — `poster` with the picture's own extension
     * ([FolderPoster.renamedTo]). The pictures already acting as artwork are
     * kept as [write] keeps them.
     *
     * @throws SmbFailure as [write] does.
     */
    suspend fun adopt(
        host: SmbHost,
        credentials: SmbCredentials,
        share: String,
        folderRelPath: String,
        pictureName: String,
        on: LocalDate,
        name: String = FolderPoster.renamedTo(pictureName),
    ): Done = keepingOld(host, credentials, share, folderRelPath, on, name, leave = pictureName) { path ->
        gateway.rename(host, credentials, share, path(pictureName), path(name))
    }

    /**
     * Rename the folder's artwork out of [name]'s way, then [put] the new
     * poster in place; undo the renames if [put] fails.
     */
    private suspend fun keepingOld(
        host: SmbHost,
        credentials: SmbCredentials,
        share: String,
        folderRelPath: String,
        on: LocalDate,
        name: String,
        leave: String?,
        put: suspend (path: (String) -> String) -> Unit,
    ): Done {
        fun path(file: String) = if (folderRelPath.isEmpty()) file else "$folderRelPath/$file"
        val names = FolderPoster.keeping(gateway.list(host, credentials, share, folderRelPath), name, on, leave)
        val moved = mutableListOf<Pair<String, String>>()
        try {
            for ((from, to) in names) {
                gateway.rename(host, credentials, share, path(from), path(to))
                moved += from to to
            }
            put(::path)
        } catch (e: SmbFailure) {
            // Newest first, so a chain of renames unwinds in order.
            for ((from, to) in moved.asReversed()) {
                runCatching { gateway.rename(host, credentials, share, path(to), path(from)) }
            }
            throw e
        }
        return Done(names)
    }
}
