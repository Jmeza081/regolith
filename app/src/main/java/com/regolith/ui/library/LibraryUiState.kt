package com.regolith.ui.library

import com.regolith.domain.artwork.ArtworkRequest
import com.regolith.domain.library.LibrarySort
import com.regolith.domain.library.ViewMode
import com.regolith.domain.transfer.TransferCause
import com.regolith.domain.transfer.TransferStatus
import com.regolith.ui.util.SelectionUiState

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
        /** Best resolution beneath it, for the chip ("4K"). */
        val resolutionLabel: String,
        override val artwork: ArtworkRequest,
        override val addedAtMs: Long,
        override val sizeBytes: Long,
        override val durationMs: Long?,
        override val height: Int?,
        val shareId: Long = 0,
        /** `/`-joined path inside the share; what a download pick is keyed on. */
        val relPath: String = "",
        /** Direct files only, for the selection tally. `fileCount` counts the whole subtree. */
        val directFileCount: Int = 0,
        val directByteCount: Long = 0,
        val listed: Boolean = true,
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
        /** The raw filename, drawn inside the art when unmatched. */
        val fileName: String,
        /** 0..1 watched, or null when never started. */
        val progress: Float?,
        /** "1h 56m", or "11m 04s" for an unmatched file. */
        val meta: String,
        override val artwork: ArtworkRequest,
        override val addedAtMs: Long,
        override val sizeBytes: Long,
        override val durationMs: Long?,
        override val height: Int?,
        val shareId: Long = 0,
        /** The folder holding it, so a selection can tell if an ancestor is picked. */
        val folderRelPath: String = "",
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
    /** Poster wall or rows. Remembered across launches. */
    val viewMode: ViewMode = ViewMode.GRID,
    /** Non-null while a multi-selection is running (the contextual bar is up). */
    val selection: SelectionUiState? = null,
    val loaded: Boolean = false,
    /** No enabled share anywhere. */
    val noSource: Boolean = false,
    /** A scan is walking a share right now. */
    val scanning: Boolean = false,
    /** True when at least one share has completed a scan; false shows the "scan first" nudge. */
    val scannedOnce: Boolean = true,
    /** Servers that cannot be reached right now (design: "TOWER is out of reach"). */
    val unreachable: List<UnreachableServer> = emptyList(),
    val checkingReachability: Boolean = false,
    /** "On this device" tab. */
    val device: DeviceUiState = DeviceUiState(),
)

data class UnreachableServer(val serverId: Long, val name: String, val lastSeenAtMs: Long?)

/** One row on the device tab. */
data class DeviceRow(
    val fileId: Long,
    val name: String,
    val status: TransferStatus,
    val cause: TransferCause?,
    val causeBytes: Long?,
    val bytesDone: Long,
    val totalBytes: Long,
    /** "1080p · 4.0 GB · 15m left" for a finished copy. */
    val meta: String,
) {
    val testTag get() = "device_row_$fileId"
    val fraction: Float get() = if (totalBytes > 0) (bytesDone.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f
}

data class DeviceUiState(
    val usedBytes: Long = 0,
    val totalBytes: Long = 0,
    val ready: List<DeviceRow> = emptyList(),
    val inFlight: List<DeviceRow> = emptyList(),
    val failed: List<DeviceRow> = emptyList(),
    /** "Show all 30" expands the failed list past its three-row preview. */
    val showAllFailed: Boolean = false,
    /**
     * Copies picked for removal. Null means not selecting.
     *
     * A different selection from the download picks, and deliberately not
     * the same machinery: that one is app-scoped because a pick three
     * folders deep has to survive walking the tree, while this one lives
     * and dies on this one tab. Sharing a store would also let a batch mean
     * "download these" and "delete these" at the same moment.
     */
    val picked: Set<Long>? = null,
    /**
     * What the open confirm dialog would remove, or null when it is closed.
     *
     * The intent is stored rather than inferred from whether anything is
     * picked: "no picks" and "everything" are one step apart, and a version
     * that read an empty selection as "clear all" would wipe the device for
     * anyone who deselected their last row and tapped through.
     */
    val confirmRemove: RemoveTarget? = null,
) {
    /** Everything the page lists, for "Select all". */
    val allFileIds: List<Long> get() = (ready + inFlight + failed).map { it.fileId }

    /** How many copies the open dialog is asking about. */
    val confirmCount: Int
        get() = when (confirmRemove) {
            RemoveTarget.PICKED -> picked?.size ?: 0
            RemoveTarget.EVERYTHING -> allFileIds.size
            null -> 0
        }

    /** Bytes the picked copies occupy — what the bar promises to give back. */
    fun pickedBytes(): Long {
        val ids = picked ?: return 0
        return (ready + inFlight + failed).filter { it.fileId in ids }
            .sumOf { if (it.status == TransferStatus.DONE) it.totalBytes else it.bytesDone }
    }
}

/** Which copies a confirm dialog on the device page is about. */
enum class RemoveTarget { PICKED, EVERYTHING }

/**
 * Install a freshly built wall onto the live state.
 *
 * Copies the BUILT fields onto the existing state, not the other way
 * round. The first version did the opposite — took the fresh build and
 * copied an allowlist of transient fields back onto it — and every field
 * not on that list was silently reset each time Room re-emitted the wall,
 * which is constantly: a listing lands, an artwork row arrives, progress
 * ticks. `selection` was not on the list, so a multi-selection vanished
 * moments after every hold. This direction cannot lose a field it does
 * not know about, which is the property that matters.
 */
fun LibraryUiState.withWall(built: LibraryUiState, sortedTiles: List<LibraryTile>): LibraryUiState = copy(
    title = built.title,
    meta = built.meta,
    tiles = sortedTiles,
    loaded = built.loaded,
    noSource = built.noSource,
    scanning = built.scanning,
    scannedOnce = built.scannedOnce,
    unreachable = built.unreachable,
)

/**
 * Install freshly built device rows onto the live device state, keeping
 * what the user is in the middle of. Same shape as [withWall], for the same
 * reason: an in-flight download re-emits every 500 ms, and a device
 * selection that reset on each tick could never be completed.
 */
fun DeviceUiState.withRows(built: DeviceUiState): DeviceUiState = copy(
    usedBytes = built.usedBytes,
    totalBytes = built.totalBytes,
    ready = built.ready,
    inFlight = built.inFlight,
    failed = built.failed,
)
