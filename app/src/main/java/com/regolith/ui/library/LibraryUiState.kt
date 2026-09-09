package com.regolith.ui.library

import com.regolith.domain.artwork.ArtworkRequest
import com.regolith.domain.library.LibrarySort

/** One poster on the wall. */
sealed interface LibraryTile {
    val testTag: String
    val name: String
    val artwork: ArtworkRequest

    /** Sort keys, so the ViewModel orders without knowing the tile kind. */
    val addedAtMs: Long
    val sizeBytes: Long
    val durationMs: Long?
    val height: Int?

    /** `Films/`, `Series/`, a show: opens its own wall. */
    data class Collection(
        val folderId: Long,
        override val name: String,
        val fileCount: Int,
        override val artwork: ArtworkRequest,
        override val addedAtMs: Long,
        override val sizeBytes: Long,
        override val durationMs: Long?,
        override val height: Int?,
    ) : LibraryTile {
        override val testTag get() = "library_collection_$folderId"
    }

    /** A title (matched or not): opens Title Detail. */
    data class Title(
        val fileId: Long,
        override val name: String,
        /** "4K", "1080p"; empty until the file has been opened. */
        val resolutionLabel: String,
        val matched: Boolean,
        val unwatched: Boolean,
        /** "1h 56m", or the extension for an unmatched file. */
        val meta: String,
        override val artwork: ArtworkRequest,
        override val addedAtMs: Long,
        override val sizeBytes: Long,
        override val durationMs: Long?,
        override val height: Int?,
    ) : LibraryTile {
        override val testTag get() = "library_title_$fileId"
    }
}

data class LibraryUiState(
    /** "Library" at the root; the collection's name inside one. */
    val title: String = "Library",
    /** "TOWER · media · 1,284 files" */
    val meta: String? = null,
    val tiles: List<LibraryTile> = emptyList(),
    val sort: LibrarySort = LibrarySort.NAME,
    val sortSheetOpen: Boolean = false,
    val loaded: Boolean = false,
    /** No enabled share anywhere. */
    val noSource: Boolean = false,
    /** A scan is walking a share right now. */
    val scanning: Boolean = false,
    /** True when at least one share has completed a scan; false shows the "scan first" nudge. */
    val scannedOnce: Boolean = true,
)
