package com.regolith.ui.home

import com.regolith.data.db.PlaybackProgressEntity
import com.regolith.data.repository.LibraryRepository
import com.regolith.domain.artwork.ArtworkKind
import com.regolith.domain.artwork.ArtworkOwner
import com.regolith.domain.artwork.ArtworkRequest
import com.regolith.domain.library.ParsedName
import com.regolith.domain.playback.VideoInfo
import com.regolith.ui.util.formatWhen
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map

/**
 * The "Continue watching" list, most recently played first.
 *
 * Two screens read it: Home's row (a short [limit]) and the "All" screen
 * behind it (a long one). It lives here rather than in either ViewModel so
 * the two can never disagree about what "in progress" means or how a title
 * is labelled.
 *
 * A file with no progress row is dropped: the query returns what has been
 * started, and progress is what says how far.
 */
@OptIn(ExperimentalCoroutinesApi::class)
fun LibraryRepository.observeResume(limit: Int): Flow<List<ResumeItem>> =
    observeContinueWatching(limit).flatMapLatest { files ->
        observeProgress(files.map { it.id }).map { progress ->
            val byId: Map<Long, PlaybackProgressEntity> = progress.associateBy { it.fileId }
            files.mapNotNull { f ->
                val p = byId[f.id] ?: return@mapNotNull null
                val parsed = ParsedName(f.titleParsed ?: f.name.substringBeforeLast('.'), f.year, f.season, f.episode)
                ResumeItem(
                    fileId = f.id,
                    name = if (parsed.matched) parsed.display else f.name.substringBeforeLast('.'),
                    artwork = ArtworkRequest(ArtworkOwner.File(f.id), ArtworkKind.THUMB),
                    positionMs = p.positionMs,
                    durationMs = p.durationMs,
                    meta = listOfNotNull(
                        VideoInfo.resolutionLabelFor(f.width, f.height).ifEmpty { null },
                        formatWhen(p.updatedAtMs),
                    ).joinToString(" · "),
                )
            }
        }
    }
