package com.regolith.data.repository

import com.regolith.data.db.MediaFileEntity
import com.regolith.data.db.UserChapterDao
import com.regolith.data.db.UserChapterHitRow
import com.regolith.data.db.UserChapterEntity
import com.regolith.data.media.ChapterSyncRepository
import com.regolith.domain.artwork.MomentFrames
import com.regolith.domain.playback.Chapter
import com.regolith.domain.playback.ChapterMatch
import com.regolith.domain.playback.ChapterDraft
import com.regolith.domain.playback.ChapterSource
import com.regolith.domain.playback.ChapterWriteOutcome
import com.regolith.domain.playback.ChapterStills
import com.regolith.domain.playback.MomentCover
import com.regolith.domain.playback.ChapterFacet
import com.regolith.domain.playback.UserChapterStats
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
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
    private val moments: MomentFrames,
) {
    /** Sorted by start; empty when the user has written nothing for this file. */
    fun observe(fileId: Long): Flow<List<Chapter>> =
        dao.observeForFile(fileId).map { rows -> rows.map { Chapter(it.startMs, it.title) } }

    /**
     * Replace the file's set, then have the sidecar on the share catch up
     * (P10). [durationMs] is the film's runtime, which places the last
     * chapter's picture ([ChapterStills]).
     */
    suspend fun save(fileId: Long, chapters: List<Chapter>, durationMs: Long) {
        val now = System.currentTimeMillis()
        val sorted = chapters.sortedBy { it.startMs }
        dao.replaceForFile(
            fileId,
            sorted.map {
                UserChapterEntity(fileId = fileId, startMs = it.startMs, title = it.title?.takeIf { t -> t.isNotBlank() }, updatedAtMs = now)
            },
        )
        // Every chapter's picture is a frame cached under the time it was taken
        // from (ChapterStills). A mark that MOVED leaves its old frame behind
        // with nothing pointing at it, and the file's own markers or the even
        // split stop showing at all, so the set is collected down to the
        // pictures these chapters use. A rename keeps a named mark's time and
        // costs no frame; naming or un-naming one moves its picture.
        moments.pruneMoments(fileId, ChapterStills.timesFor(sorted, durationMs, ChapterSource.USER))
        sync.markDirty(fileId)
    }

    /**
     * Give the chapter at [atMs] the name [title], and write the set to the
     * share: the quick name sheet's save, from the video page, the player's
     * Chapters panel and its Mark pill.
     *
     * [shown] is the list on screen, whatever made it: the user's own, the
     * file's markers or the even split. Only the user's own are stored, so
     * naming one part of a split keeps the whole split as the user's
     * chapters, exactly as opening the editor on it and saving would. A time
     * with no chapter at it gets a new mark ([ChapterDraft.nameAt]).
     */
    suspend fun nameAt(fileId: Long, shown: List<Chapter>, durationMs: Long, atMs: Long, title: String): ChapterWriteOutcome {
        val draft = ChapterDraft.seed(fileId, shown, durationMs).nameAt(atMs, title)
        save(fileId, draft.chapters, durationMs)
        return runCatching { writeNow(fileId) }.getOrDefault(ChapterWriteOutcome.FAILED)
    }

    /**
     * Undo for [nameAt]: put back the user's chapters as they were
     * ([before], read just before the save). None before means the set came
     * from the file's markers or the even split, so the rows go again, and
     * the chapter file on the share with them.
     */
    suspend fun restore(fileId: Long, before: List<Chapter>, durationMs: Long) {
        if (before.isEmpty()) {
            clear(fileId)
        } else {
            save(fileId, before, durationMs)
            runCatching { writeNow(fileId) }
        }
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
     * The points of interest: every chapter name in the library, commonest
     * first, the first [limit] of them. Search's moment sheet filters by
     * them; Home lists them all, A to Z. Free of the share — the sidecar
     * import already put every name in this table when the folder was
     * listed (see `ChapterSyncRepository.onFolderListed`).
     */
    fun facets(limit: Int): Flow<List<ChapterFacet>> =
        dao.observeFacets(limit).map { rows -> rows.map { ChapterFacet(it.title, it.films) } }

    /**
     * For every name [facets] lists, the mark whose frame stands for the name
     * on Home ([withCovers] pairs them up): the most recent from a wide video
     * when there is one, so the frame fills its tile.
     */
    fun momentCovers(): Flow<List<MomentCover>> =
        dao.observeMomentCovers().map { rows -> rows.map { MomentCover(it.title, it.fileId, it.startMs) } }

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

    /**
     * The chips under a name field, following what is typed into it
     * ([typed]; null while no field is open, which offers nothing).
     *
     * The query waits for a short pause in typing ([SUGGEST_DEBOUNCE_MS]),
     * but the one name it drops, the one already in the box (tapping that
     * chip would do nothing), follows every keystroke. Shared by the chapter
     * editor and the quick name sheet, so both offer the same names.
     */
    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    fun suggestionsFor(typed: Flow<String?>): Flow<List<String>> {
        val text = typed.distinctUntilChanged()
        val found = text.debounce(SUGGEST_DEBOUNCE_MS).flatMapLatest { t ->
            if (t == null) flowOf(emptyList()) else nameSuggestions(t, SUGGEST_QUERY_LIMIT)
        }
        return combine(found, text) { names, now ->
            if (now == null) emptyList() else names.map { it.title }.filter { !it.equals(now.trim(), ignoreCase = true) }
        }
    }

    /**
     * Every film carrying a point of interest: a chip's results. With words
     * typed after the chip ([query]), only those whose filename, title or path
     * matches them too, by Search's own rule.
     */
    fun filesWith(title: String, query: String = ""): Flow<List<MediaFileEntity>> {
        val match = LibraryRepository.ftsMatch(query) ?: return dao.filesWithChapter(title)
        return dao.filesWithChapterMatching(title, match)
    }

    /**
     * Every occurrence of one point of interest in the films [filesWith]
     * finds for the same [title] and [query], by film name and then time:
     * the moments above those films, one for one, since both ask the same
     * question of the same rows.
     */
    fun occurrences(title: String, query: String = ""): Flow<List<ChapterMatch>> {
        val match = LibraryRepository.ftsMatch(query)
        val rows = if (match == null) dao.occurrencesOf(title) else dao.occurrencesMatching(title, match)
        return rows.map { list -> list.map { it.toMatch() } }
    }

    /**
     * The named marks on the videos directly in [folderId], for a collection
     * profile's Moments tab: the points of interest Search finds, gathered by
     * where they are rather than by what they are called.
     */
    /** How many named moments each film on [shareIds] has, by file id: the Library's Moments chip. */
    fun observeNamedCounts(shareIds: List<Long>): Flow<Map<Long, Int>> =
        if (shareIds.isEmpty()) flowOf(emptyMap()) else dao.observeNamedCounts(shareIds).map { rows -> rows.associate { it.fileId to it.count } }

    fun observeNamedInFolder(folderId: Long): Flow<List<ChapterMatch>> =
        dao.observeNamedInFolder(folderId).map { rows -> rows.map { it.toMatch() } }

    /** Prefix search over chapter names, the same words-to-MATCH rule as the file search. */
    fun search(query: String, limit: Int): Flow<List<ChapterMatch>> {
        val match = LibraryRepository.ftsMatch(query) ?: return flowOf(emptyList())
        return dao.search(match, limit).map { rows -> rows.map { it.toMatch() } }
    }

    private fun UserChapterHitRow.toMatch() = ChapterMatch(
        fileId = fileId, startMs = startMs, title = title,
        fileName = fileName, fileTitle = fileTitle, shareId = shareId, fileRelPath = fileRelPath,
        namedAtMs = updatedAtMs,
    )

    companion object {
        /** A pause in typing before the suggestions re-query: short, it is a local table. */
        const val SUGGEST_DEBOUNCE_MS = 120L

        /**
         * How many names the chips can offer. The row scrolls, and the point
         * is to show what the share HAS, so this is a safety rail against a
         * pathological library, not an editorial cut. Same number as the
         * moment filter's, for the same reason.
         */
        const val SUGGEST_QUERY_LIMIT = 50
    }
}
