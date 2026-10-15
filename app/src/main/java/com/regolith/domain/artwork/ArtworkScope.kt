package com.regolith.domain.artwork

/**
 * What one "Regenerate artwork" covers: a collection (its folder and every
 * folder under it) or a single video. The owner's reason: after changing
 * what is on the share (a new poster.jpg, re-encoded videos), make that
 * place's pictures again, without clearing the whole cache.
 */
sealed interface ArtworkScope {
    data class Folder(val folderId: Long) : ArtworkScope

    data class File(val fileId: Long) : ArtworkScope
}

/**
 * Everything a scope's pictures belong to, worked out by
 * `ArtworkRegenerator`: the [folders] (the collection first, then the ones under it),
 * their [files] and [pictures] (images in an album), on [shareId].
 */
data class ArtworkScopeContents(
    val shareId: Long,
    val folders: List<Long>,
    val files: List<Long>,
    val pictures: List<Long>,
) {
    /** The stills a walk remakes, folders first, as the share's own walk orders them. */
    val owners: List<ArtworkOwner>
        get() = folders.map { ArtworkOwner.Folder(it) } + files.map { ArtworkOwner.File(it) } + pictures.map { ArtworkOwner.Picture(it) }
}
