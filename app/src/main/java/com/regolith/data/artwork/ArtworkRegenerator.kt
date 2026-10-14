package com.regolith.data.artwork

import com.regolith.data.db.ShareFileDao
import com.regolith.data.repository.LibraryRepository
import com.regolith.domain.artwork.ArtworkScope
import com.regolith.domain.artwork.ArtworkScopeContents
import com.regolith.domain.media.MediaFileTypes
import javax.inject.Inject
import javax.inject.Singleton

/**
 * "Regenerate artwork" for one collection or one video: throw its pictures
 * away, then have just those made again, in the background, with the
 * artwork walk's own progress and notification.
 *
 * Its own class, not a method on [ArtworkRepository], because working out
 * what a collection covers needs the library's folder tree
 * ([LibraryRepository.descendants]), and the library already depends on the
 * artwork repository: the two cannot both know each other.
 *
 * Web analogy: a "purge this path" on a CDN, followed by a warm-up of the
 * same paths.
 */
@Singleton
class ArtworkRegenerator @Inject constructor(
    private val library: LibraryRepository,
    private val artwork: ArtworkRepository,
    private val prefetcher: ArtworkPrefetcher,
    private val shareFiles: ShareFileDao,
) {
    /**
     * What [scope] covers: a collection's folder and every folder under it,
     * their videos and the pictures in them; or the one video. Null when the
     * folder or video is gone. The scoped walks ask this again when they
     * start, so a video added since the tap is covered too.
     */
    suspend fun contentsOf(scope: ArtworkScope): ArtworkScopeContents? = when (scope) {
        is ArtworkScope.File -> library.file(scope.fileId)?.let { ArtworkScopeContents(it.shareId, emptyList(), listOf(it.id), emptyList()) }
        is ArtworkScope.Folder -> {
            val folders = library.descendants(scope.folderId)
            folders.firstOrNull()?.let { root ->
                ArtworkScopeContents(
                    shareId = root.shareId,
                    folders = folders.map { it.id },
                    files = library.filesUnder(scope.folderId).map { it.id },
                    pictures = folders.flatMap { f -> shareFiles.inFolder(f.id).filter { MediaFileTypes.isPicture(it.name) }.map { it.id } },
                )
            }
        }
    }

    /**
     * Throw away [scope]'s pictures now, and queue them to be made again.
     * Returns what it covers, for the message that says so; null when it is
     * gone.
     */
    suspend fun regenerate(scope: ArtworkScope): ArtworkScopeContents? {
        val contents = contentsOf(scope) ?: return null
        artwork.forget(contents)
        prefetcher.regenerate(contents.shareId, scope)
        return contents
    }
}
