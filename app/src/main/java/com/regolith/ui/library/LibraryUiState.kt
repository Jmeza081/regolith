package com.regolith.ui.library

import com.regolith.domain.display.PostersPerRow
import com.regolith.domain.artwork.ArtworkRequest
import com.regolith.domain.library.LibraryOrder
import com.regolith.domain.library.LibrarySort
import com.regolith.domain.library.MomentOrder
import com.regolith.domain.library.MomentSort
import com.regolith.domain.library.PictureOrder
import com.regolith.domain.library.SortDirection
import com.regolith.domain.library.SortKeys
import com.regolith.domain.library.comparator
import com.regolith.domain.library.ViewMode
import com.regolith.domain.media.PhoneAccess
import com.regolith.domain.playback.ChapterMatch
import com.regolith.domain.transfer.TransferCause
import com.regolith.domain.transfer.TransferStatus
import com.regolith.ui.util.SelectionUiState

/**
 * Which kind of thing a wall shows: the chips under the Library's tabs
 * (Videos · Moments · Images). A collection opened from a wall opens on the
 * same one: a profile on that tab, a wall of collections with that chip lit.
 * Rides on the route ([com.regolith.ui.navigation.RegolithKey.Library]).
 */
enum class LibraryFilter(val label: String) {
    /** Collections and the videos in them: the wall as it always was. */
    VIDEOS("Videos"),

    /** Collections whose videos have named moments, opening on their Moments tab. */
    MOMENTS("Moments"),

    /** Albums, the folders that hold pictures, and any pictures lying loose on the wall. */
    IMAGES("Images"),
}

/** One poster on the wall. Its [SortKeys] let the wall be ordered without knowing the tile kind. */
sealed interface LibraryTile : SortKeys {
    val testTag: String
    val artwork: ArtworkRequest

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
        /** The newest file beneath it, by the file's own date. */
        override val fileDateMs: Long = 0,
        val shareId: Long = 0,
        /** `/`-joined path inside the share; what a download pick is keyed on. */
        val relPath: String = "",
        /** Direct files only, for the selection tally. `fileCount` counts the whole subtree. */
        val directFileCount: Int = 0,
        val directByteCount: Long = 0,
        val listed: Boolean = true,
        /** Named moments in the videos beneath it: the Moments chip's "6 moments". */
        val momentCount: Int = 0,
        /** Pictures beneath it, and how many of the folders directly in it hold any: the Images chip's line. */
        val pictureCount: Int = 0,
        val albumCount: Int = 0,
    ) : LibraryTile {
        override val testTag get() = "library_collection_$folderId"
    }

    /** A video: opens Title Detail. */
    data class Title(
        val fileId: Long,
        /** The parsed title when the name has a year or an episode ("Arrival (2016)"), else the file's own name without its extension. */
        override val name: String,
        /** "4K", "1080p"; empty until the file has been opened. */
        val resolutionLabel: String,
        val unwatched: Boolean,
        /** The raw filename, drawn inside the art when there is no picture to show. */
        val fileName: String,
        /** 0..1 watched, or null when never started. */
        val progress: Float?,
        /** "1h 56m", "11m 04s": the runtime, or the size before the file has been probed. */
        val meta: String,
        override val artwork: ArtworkRequest,
        override val addedAtMs: Long,
        override val sizeBytes: Long,
        override val durationMs: Long?,
        override val height: Int?,
        override val fileDateMs: Long = 0,
        val shareId: Long = 0,
        /** The folder holding it, so a selection can tell if an ancestor is picked. */
        val folderRelPath: String = "",
        /** Named moments in it, which is what puts it on the Moments chip's wall. */
        val momentCount: Int = 0,
    ) : LibraryTile {
        override val testTag get() = "library_title_$fileId"
    }

    /**
     * A picture lying loose on a wall, beside the albums, under the Images
     * chip: cut to the wall's 2:3 like a poster. Opens the lightbox over the
     * pictures of the folder it is in.
     */
    data class Picture(val picture: PictureTile) : LibraryTile {
        override val testTag get() = picture.testTag
        override val artwork get() = picture.artwork
        override val name get() = picture.title
        override val addedAtMs get() = picture.addedAtMs ?: picture.modifiedAtMs
        override val fileDateMs get() = picture.takenAtMs ?: picture.modifiedAtMs
        override val sizeBytes get() = picture.sizeBytes
        override val durationMs: Long? get() = null
        override val height get() = picture.height
    }
}

data class LibraryUiState(
    /** "Library" at the root; the collection's name inside one. */
    val title: String = "Library",
    /** "TOWER · media · 1,284 files" */
    val meta: String? = null,
    val tiles: List<LibraryTile> = emptyList(),
    /** The chip lit on a wall ([LibraryFilter]); a profile opens on the matching tab instead. */
    val filter: LibraryFilter = LibraryFilter.VIDEOS,
    /**
     * Which chips have anything behind them on this wall. A library with no
     * moments and no pictures shows no chips at all: there is nothing to
     * choose between.
     */
    val filtersWithTiles: Set<LibraryFilter> = setOf(LibraryFilter.VIDEOS),
    /**
     * Set when this wall is a leaf collection's and so opens as its profile
     * page: videos only, no collections inside. Null on every other wall.
     */
    val profile: CollectionProfile? = null,
    val order: LibraryOrder = LibraryOrder(),
    /** A collection profile's Moments tab; its own list, so its own order. */
    val momentOrder: MomentOrder = MomentOrder(),
    /** A profile's Images tab, and the lightbox over it: newest taken first to start. */
    val pictureOrder: PictureOrder = PictureOrder(),
    /** How many columns the Images tab's mosaic has on a phone, and on a wide window ([com.regolith.domain.display.PicturesAcross]). */
    val picturesAcrossPhone: Int = 3,
    val picturesAcrossWide: Int = 5,
    /** Open over whichever list is on screen: it shows that list's choices ([LibraryScreen]). */
    val sortSheetOpen: Boolean = false,
    /** Poster wall or rows. Remembered across launches. */
    val viewMode: ViewMode = ViewMode.GRID,
    /** Settings › Display › Posters per row: how many across a wall on the inner display. */
    val postersPerRow: PostersPerRow = PostersPerRow.DEFAULT,
    /**
     * Non-null while a multi-selection is running (the contextual bar is up).
     * Renaming, moving and deleting the picks is `LibraryViewModel.fileActions`,
     * with state of its own.
     */
    val selection: SelectionUiState? = null,
    /** The collection this wall was showing has been deleted; the screen should pop. */
    val gone: Boolean = false,
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

/** One row on the device tab. Its [SortKeys] are what the tab's own order reads. */
data class DeviceRow(
    val fileId: Long,
    override val name: String,
    val status: TransferStatus,
    val cause: TransferCause?,
    val causeBytes: Long?,
    val bytesDone: Long,
    val totalBytes: Long,
    /** "TOWER · 1080p · 4.0 GB · 15m left" for a finished copy. */
    val meta: String,
    /**
     * A video the phone already had, not a copy. It plays like a finished
     * download but is not one: it cannot be picked for "Remove download",
     * because removing it would delete someone's only copy.
     */
    val phone: Boolean = false,
    /** When it arrived here: the copy finishing, or the phone's own date for a video it already had. */
    override val addedAtMs: Long = 0,
    override val fileDateMs: Long = 0,
    override val durationMs: Long? = null,
    override val height: Int? = null,
) : SortKeys {
    override val sizeBytes: Long get() = totalBytes
    val testTag get() = "device_row_$fileId"
    val fraction: Float get() = if (totalBytes > 0) (bytesDone.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f
}

data class DeviceUiState(
    /** Tiles or rows. Its own choice, remembered apart from the network wall's. */
    val viewMode: ViewMode = ViewMode.ROWS,
    /**
     * The order [ready] and the phone's videos are in, remembered apart from
     * the wall's. The queue and the failures keep theirs: what downloads next
     * is not a sort.
     */
    val order: LibraryOrder = LibraryOrder.DEVICE_DEFAULT,
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
    // --- Phone storage: the videos the phone already had (PhoneLibrary).
    /** What the person has allowed; NONE draws the ask card in place of the section. */
    val phoneAccess: PhoneAccess = PhoneAccess.NONE,
    /** One entry per phone folder with something showing in it, in [order] ([inOrder]). */
    val phoneFolders: List<PhoneFolder> = emptyList(),
    /** Which origin the chips narrow the page to. */
    val filter: DeviceFilter = DeviceFilter.All,
) {
    /** Every phone video showing, for the tab's count and the header. */
    val phoneCount: Int get() = phoneFolders.sumOf { it.videos.size }

    /** What the "On this device" tab counts: copies that play, plus the phone's own videos. */
    val playableCount: Int get() = ready.size + phoneCount

    /** No copies anywhere in any state. The downloads half of the page has nothing to say. */
    val noDownloads: Boolean get() = ready.isEmpty() && inFlight.isEmpty() && failed.isEmpty()

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

/**
 * A phone directory with videos in it: "Camera · DCIM/Camera".
 *
 * [videos] are [DeviceRow]s with status DONE, so the same row and tile
 * renderers draw a phone video and a finished download — the difference
 * the page makes is WHERE they sit and the origin their meta line names,
 * not a second visual language.
 */
data class PhoneFolder(
    val folderId: Long,
    /** The path the folder is keyed by, which Settings' hide switch stores. */
    val relPath: String,
    val name: String,
    /** "DCIM/Camera", or "SD card · Movies". */
    val path: String,
    val videos: List<DeviceRow>,
) {
    val testTag get() = "device_phone_folder_$folderId"
}

/**
 * The chip row on the device tab: everything, only the copies that came off
 * a share, or one phone folder. A filter rather than a pushed screen,
 * because a phone folder is a flat list with nowhere further to walk.
 */
sealed interface DeviceFilter {
    data object All : DeviceFilter
    data object Downloads : DeviceFilter
    data class Folder(val folderId: Long) : DeviceFilter
}

/** Which copies a confirm dialog on the device page is about. */
enum class RemoveTarget { PICKED, EVERYTHING }

/**
 * No two videos on one wall wear the same name. Where two parse to the same
 * title (one film in two qualities, say), each is named by its own file
 * instead, which a share keeps unique within a folder. Every video in a
 * collection is its own thing, and two tiles reading alike say otherwise.
 */
internal fun List<LibraryTile>.withUniqueTitles(): List<LibraryTile> {
    val counts = filterIsInstance<LibraryTile.Title>().groupingBy { it.name }.eachCount()
    if (counts.values.none { it > 1 }) return this
    return map { tile ->
        if (tile is LibraryTile.Title && (counts[tile.name] ?: 0) > 1) tile.copy(name = tile.fileName.substringBeforeLast('.')) else tile
    }
}

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
    filtersWithTiles = built.filtersWithTiles,
    profile = built.profile,
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
    ready = built.ready.sortedWith(order.comparator()),
    inFlight = built.inFlight,
    failed = built.failed,
)

/**
 * The device tab in [next] order: the finished copies, each phone folder's
 * videos, and the folders themselves. By name, the folders go by their own
 * names; by anything else, the folder holding the first video comes first,
 * the rule "the folder with the newest clip first" always followed.
 */
fun DeviceUiState.inOrder(next: LibraryOrder = order): DeviceUiState {
    val byOrder = next.comparator<DeviceRow>()
    val folders = phoneFolders.map { it.copy(videos = it.videos.sortedWith(byOrder)) }
    val folderOrder: Comparator<PhoneFolder> = if (next.sort == LibrarySort.NAME) {
        compareBy<PhoneFolder, String>(String.CASE_INSENSITIVE_ORDER) { it.name }
            .let { if (next.direction == SortDirection.ASCENDING) it else it.reversed() }
    } else {
        // Every folder listed has at least one video ([PhoneFolder]).
        Comparator { a, b -> byOrder.compare(a.videos.first(), b.videos.first()) }
    }
    return copy(order = next, ready = ready.sortedWith(byOrder), phoneFolders = folders.sortedWith(folderOrder))
}

/**
 * A leaf collection's page, read as a profile (the canvas "Collection View
 * Refinements", boards C + A): its poster lighting the top of the page, a
 * line of stats, Play all, and Videos / Moments / Images tabs over the wall.
 *
 * Only a collection holding videos or pictures and no collections gets one
 * ([collectionProfile]): a collection inside a collection is a stop on the
 * way, and only the last stop is a profile (the owner's rule, 2026-10-06).
 * A folder of pictures alone is one too: an album.
 */
data class CollectionProfile(
    /** What it sits in, for the line over its name: "Home videos", or the share's name at the top of one. */
    val parentName: String?,
    /** Its poster, moving where the first wall lets a poster move. */
    val poster: ArtworkRequest,
    /** The same poster held still, for the light behind the page: a GIF re-blurred on every frame would cost more than the page. */
    val light: ArtworkRequest,
    val videoCount: Int,
    /** Every video's runtime added up, or null while any is still unknown: a total that is quietly short is worse than none. */
    val runtimeMs: Long?,
    val sizeBytes: Long,
    /** Videos played to the end. */
    val watchedCount: Int,
    /** The named chapters in its videos, by video in name order and then by time ([inOrder] re-orders them). */
    val moments: List<CollectionMoment>,
    /** The pictures in it, the poster and the videos' own among them, in no particular order ([inOrder] orders them). */
    val pictures: List<PictureTile> = emptyList(),
) {
    /** Its tabs, in order: Videos and Moments where it holds videos, Images where it holds pictures. */
    internal val tabs: List<ProfileTab>
        get() = buildList {
            if (videoCount > 0) {
                add(ProfileTab.VIDEOS)
                add(ProfileTab.MOMENTS)
            }
            if (pictures.isNotEmpty()) add(ProfileTab.IMAGES)
        }

    /** Every picture's size added up, for the Size cell beside the videos'. */
    val pictureBytes: Long get() = pictures.sumOf { it.sizeBytes }
}

/** One point of interest on a profile's Moments tab: a chapter someone named, played from where it starts. */
data class CollectionMoment(
    val fileId: Long,
    val startMs: Long,
    /** The chapter's name. */
    val title: String,
    /** The video it is in, named as its tile names it. */
    val videoName: String,
    /** When it was named or last renamed. */
    val namedAtMs: Long = 0,
) {
    val testTag get() = "library_moment_${fileId}_$startMs"
}

/**
 * The profile for a collection's wall, or null when the page stays a wall:
 * a wall holding any collection is not a leaf ([hasCollections]: under any
 * chip), and a collection with no video and no picture in it has nothing to
 * show a profile of.
 *
 * [tiles] are the wall's tiles as built (names already made unique), so a
 * moment names its video exactly as the video's tile does. [completed] is
 * the ids of videos played to the end. [marks] are the named chapters in the
 * folder; only those on the wall's own videos are kept, since a hidden or
 * vanished video's marks have nothing to play. [pictures] are the pictures
 * directly in it.
 */
internal fun collectionProfile(
    tiles: List<LibraryTile>,
    parentName: String?,
    poster: ArtworkRequest,
    completed: Set<Long>,
    marks: List<ChapterMatch>,
    pictures: List<PictureTile> = emptyList(),
    hasCollections: Boolean = tiles.any { it is LibraryTile.Collection },
): CollectionProfile? {
    if (hasCollections) return null
    val videos = tiles.filterIsInstance<LibraryTile.Title>()
    if (videos.isEmpty() && pictures.isEmpty()) return null
    val names = videos.associate { it.fileId to it.name }
    val durations = videos.map { it.durationMs }
    return CollectionProfile(
        parentName = parentName,
        poster = poster,
        light = poster.copy(animated = false),
        videoCount = videos.size,
        runtimeMs = if (durations.all { it != null }) durations.sumOf { it ?: 0L } else null,
        sizeBytes = videos.sumOf { it.sizeBytes },
        watchedCount = videos.count { it.fileId in completed },
        moments = marks.mapNotNull { mark ->
            names[mark.fileId]?.let { name -> CollectionMoment(mark.fileId, mark.startMs, mark.title, name, mark.namedAtMs) }
        },
        pictures = pictures,
    )
}

/**
 * The moments in [order]. [tiles] are the wall's, in the wall's own order:
 * [MomentSort.VIDEO] follows it, so sorting the Videos tab sorts the
 * moments with it, and it settles every tie in the other two, a video's
 * moments always in the order they play.
 */
internal fun List<CollectionMoment>.inOrder(order: MomentOrder, tiles: List<LibraryTile>): List<CollectionMoment> {
    val position = HashMap<Long, Int>()
    tiles.forEachIndexed { index, tile -> if (tile is LibraryTile.Title) position[tile.fileId] = index }
    val video = compareBy<CollectionMoment> { position[it.fileId] ?: Int.MAX_VALUE }
    val inPlay = compareBy<CollectionMoment> { it.startMs }
    val ascending = order.direction == SortDirection.ASCENDING
    val comparator = when (order.sort) {
        // Reversed, the videos run backwards but a video's moments still play forwards.
        MomentSort.VIDEO -> (if (ascending) video else video.reversed()).then(inPlay)
        MomentSort.NAME -> compareBy<CollectionMoment, String>(String.CASE_INSENSITIVE_ORDER) { it.title }
            .let { if (ascending) it else it.reversed() }.then(video).then(inPlay)
        MomentSort.DATE_NAMED -> compareBy<CollectionMoment> { it.namedAtMs }
            .let { if (ascending) it else it.reversed() }.then(video).then(inPlay)
    }
    return sortedWith(comparator)
}
