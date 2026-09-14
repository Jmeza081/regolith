package com.regolith.data.repository

import com.regolith.data.db.MediaFileEntity
import com.regolith.data.db.UserChapterDao
import com.regolith.data.db.UserChapterHitRow
import com.regolith.data.db.UserChapterEntity
import com.regolith.data.media.ChapterSyncRepository
import com.regolith.domain.playback.Chapter
import com.regolith.domain.playback.ChapterMatch
import com.regolith.domain.playback.ChapterFacet
import com.regolith.domain.playback.UserChapterStats
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The chapters the user wrote (schema v8). Web analogy: a tiny store over
 * one table, with a search endpoint.
 *
 * Everything is a Flow on the way out, because the player keeps a film
 * loaded while the editor saves, Settings clears, or the sheet reverts —
 * and the scrubber has to follow without anyone reloading anything.
 */
@Singleton
class UserChapterRepository @Inject constructor(
    private val dao: UserChapterDao,
    private val sync: ChapterSyncRepository,
) {
    /** Sorted by start; empty when the user has written nothing for this file. */
    fun observe(fileId: Long): Flow<List<Chapter>> =
        dao.observeForFile(fileId).map { rows -> rows.map { Chapter(it.startMs, it.title) } }

    /** Replace the file's set, then have the sidecar on the share catch up (P10). */
    suspend fun save(fileId: Long, chapters: List<Chapter>) {
        val now = System.currentTimeMillis()
        dao.replaceForFile(
            fileId,
            chapters.sortedBy { it.startMs }.map {
                UserChapterEntity(fileId = fileId, startMs = it.startMs, title = it.title?.takeIf { t -> t.isNotBlank() }, updatedAtMs = now)
            },
        )
        sync.markDirty(fileId)
    }

    /** Back to whatever the file would show on its own: the rows go, and the sidecar on the share with them. */
    suspend fun clear(fileId: Long) = sync.revert(fileId)

    /** Settings › Chapters › Clear: the phone's cache only; the share is never touched from Settings. */
    suspend fun clearAll() = sync.clearLocal()

    /** Save's own write to the share, now, with the answer for the line under the button. */
    suspend fun writeNow(fileId: Long) = sync.writeNow(fileId)

    /** The sheet's sync state for one film, live. */
    fun observeSync(fileId: Long) = sync.observe(fileId)

    suspend fun clearNote(fileId: Long) = sync.clearNote(fileId)

    fun stats(): Flow<UserChapterStats> = dao.observeTally().map { UserChapterStats(chapters = it.chapters, files = it.files) }

    /**
     * The points of interest to filter by: every chapter name in the
     * library, commonest first. Free of the share — the sidecar import
     * already put every name in this table when the folder was listed (see
     * `ChapterSyncRepository.onFolderListed`).
     */
    fun facets(limit: Int): Flow<List<ChapterFacet>> =
        dao.observeFacets(limit).map { rows -> rows.map { ChapterFacet(it.title, it.films) } }

    /**
     * Names to offer while a chapter is being named: the ones matching what
     * has been typed so far, or the library's commonest when nothing has.
     *
     * The point is consistency — "The heist" and "the heist" and "Heist"
     * are three filters in Search and should have been one — so the
     * matching goes through `user_chapter_fts`, the same index the chapter
     * search uses, and an empty box still gets the popular names rather
     * than nothing.
     */
    fun nameSuggestions(query: String, limit: Int): Flow<List<ChapterFacet>> {
        val match = LibraryRepository.ftsMatch(query) ?: return facets(limit)
        return dao.observeNameMatches(match, limit).map { rows -> rows.map { ChapterFacet(it.title, it.films) } }
    }

    /** The films carrying a point of interest, for a chip standing on its own. */
    fun filesWith(title: String, limit: Int): Flow<List<MediaFileEntity>> = dao.filesWithChapter(title, limit)

    /** Every occurrence of one point of interest, newest-listed film first. */
    fun occurrences(title: String, limit: Int): Flow<List<ChapterMatch>> =
        dao.occurrencesOf(title, limit).map { rows -> rows.map { it.toMatch() } }

    /** Prefix search over chapter names, the same words-to-MATCH rule as the file search. */
    fun search(query: String, limit: Int): Flow<List<ChapterMatch>> {
        val match = LibraryRepository.ftsMatch(query) ?: return flowOf(emptyList())
        return dao.search(match, limit).map { rows -> rows.map { it.toMatch() } }
    }

    private fun UserChapterHitRow.toMatch() = ChapterMatch(
        fileId = fileId, startMs = startMs, title = title,
        fileName = fileName, fileTitle = fileTitle, shareId = shareId, fileRelPath = fileRelPath,
    )
}
