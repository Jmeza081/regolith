package com.regolith.domain.artwork

import com.regolith.domain.smb.SmbEntry

/** A cached picture, as far as checking it against the share needs: whose, where from, and its [ArtworkFreshness.stamp]. */
data class CachedPicture(val owner: ArtworkOwner, val source: ArtworkSource, val stamp: String?)

/**
 * Whether a cached picture still matches the share it came from.
 *
 * Artwork is copied into the app's own directory (guardrail G5), which is
 * what lets it survive offline — and what used to make it blind: a
 * folder.jpg replaced on the share with a different picture of the same
 * name was served from the old copy for ever. The fix is the web's: keep a
 * validator beside the cached copy and compare it when fresh information
 * arrives. The validator is a STAMP of the owner's candidate images (name,
 * size, modified time, in the resolver's order), and the fresh information
 * is a folder listing, which the app takes anyway whenever a folder is
 * opened, refreshed or scanned — so checking costs no extra network at all.
 *
 * Why every candidate, not only the one that was used: a film's picture
 * comes from the FIRST image that decodes, so a poster.jpg added beside a
 * folder.jpg must count as a change, and a broken image that was tried and
 * skipped must not — or the frame grab standing in for it would be thrown
 * away and grabbed again on every listing, for ever. The whole candidate
 * list covers both.
 */
object ArtworkFreshness {
    /** The stamp of an owner the share has no image for. */
    const val NO_IMAGES = ""

    /**
     * The fingerprint of [candidates] as [listing] describes them: one line
     * per image, `name<TAB>size<TAB>modified`, in candidate order. Readable on
     * purpose, so `sqlite3` answers "why did this not refresh?".
     */
    fun stamp(candidates: List<ArtworkCandidates.Candidate>, listing: List<SmbEntry>): String {
        if (candidates.isEmpty()) return NO_IMAGES
        val byName = listing.associateBy { it.name }
        return candidates.joinToString("\n") { candidate ->
            byName[candidate.name]?.let { "${it.name}\t${it.sizeBytes}\t${it.modifiedAtMs}" } ?: candidate.name
        }
    }

    /**
     * Whether a picture made under [recorded] should be made again, now that
     * the share's images for its owner stamp as [current].
     *
     * A known stamp is simply compared. An unknown one (null: a row from
     * before stamps, or a picture made from a copy on the phone) is judged by
     * where the picture came from: an image read off the share cannot be
     * checked, so it is read once more; a generated one (a frame, a mosaic,
     * cover art) only loses to a share image, so it goes only if the share
     * has one now; and a placeholder is left to expire on its own within the
     * day, rather than retrying a file that would not decode on every listing.
     */
    fun isStale(recorded: String?, current: String, source: ArtworkSource): Boolean = when {
        recorded != null -> recorded != current
        source == ArtworkSource.PLACEHOLDER -> false
        source == ArtworkSource.SIDECAR || source == ArtworkSource.BASENAME -> true
        else -> current != NO_IMAGES
    }

    /**
     * The owners whose pictures a listing of [folderId] shows to be out of
     * date: the folder itself (its sidecars) and the films directly in it
     * (their title-folder sidecar or basename image), each against its own
     * candidates in [listing]. [cached] holds every picture of the folder and
     * of [files]; an owner with none is not in the answer, since there is
     * nothing to throw away.
     */
    fun staleOwners(
        folderId: Long,
        files: List<Pair<Long, String>>,
        listing: List<SmbEntry>,
        cached: List<CachedPicture>,
    ): List<ArtworkOwner> {
        val byOwner = cached.groupBy { it.owner }
        val out = mutableListOf<ArtworkOwner>()
        val folder = ArtworkOwner.Folder(folderId)
        byOwner[folder]?.let { pictures ->
            val current = stamp(ArtworkCandidates.forFolder(listing), listing)
            if (pictures.any { isStale(it.stamp, current, it.source) }) out += folder
        }
        for ((id, name) in files) {
            val owner = ArtworkOwner.File(id)
            val pictures = byOwner[owner] ?: continue
            val current = stamp(ArtworkCandidates.forFile(name, listing), listing)
            if (pictures.any { isStale(it.stamp, current, it.source) }) out += owner
        }
        return out
    }
}
