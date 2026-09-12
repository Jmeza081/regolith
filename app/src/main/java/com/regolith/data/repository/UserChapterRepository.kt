package com.regolith.data.repository

import com.regolith.data.db.UserChapterDao
import com.regolith.data.db.UserChapterEntity
import com.regolith.domain.playback.Chapter
import com.regolith.domain.playback.ChapterMatch
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
) {
    /** Sorted by start; empty when the user has written nothing for this file. */
    fun observe(fileId: Long): Flow<List<Chapter>> =
        dao.observeForFile(fileId).map { rows -> rows.map { Chapter(it.startMs, it.title) } }

    /** Replace the file's set. An empty list is the same as [clear]. */
    suspend fun save(fileId: Long, chapters: List<Chapter>) {
        val now = System.currentTimeMillis()
        dao.replaceForFile(
            fileId,
            chapters.sortedBy { it.startMs }.map {
                UserChapterEntity(fileId = fileId, startMs = it.startMs, title = it.title?.takeIf { t -> t.isNotBlank() }, updatedAtMs = now)
            },
        )
    }

    /** Back to whatever the file would show on its own. */
    suspend fun clear(fileId: Long) = dao.deleteForFile(fileId)

    /** Settings › Chapters › Clear. */
    suspend fun clearAll() = dao.deleteAll()

    fun stats(): Flow<UserChapterStats> = dao.observeTally().map { UserChapterStats(chapters = it.chapters, files = it.files) }

    /** Prefix search over chapter names, the same words-to-MATCH rule as the file search. */
    fun search(query: String, limit: Int): Flow<List<ChapterMatch>> {
        val match = LibraryRepository.ftsMatch(query) ?: return flowOf(emptyList())
        return dao.search(match, limit).map { rows ->
            rows.map {
                ChapterMatch(
                    fileId = it.fileId, startMs = it.startMs, title = it.title,
                    fileName = it.fileName, fileTitle = it.fileTitle, shareId = it.shareId, fileRelPath = it.fileRelPath,
                )
            }
        }
    }
}
