package com.regolith.data.demo

import android.content.Context
import com.regolith.R
import com.regolith.data.artwork.ArtworkRepository
import com.regolith.data.db.FolderDao
import com.regolith.data.db.FolderEntity
import com.regolith.data.db.MediaFileDao
import com.regolith.data.db.MediaFileEntity
import com.regolith.data.db.PlaybackProgressDao
import com.regolith.data.db.PlaybackProgressEntity
import com.regolith.data.db.ServerDao
import com.regolith.data.db.ServerEntity
import com.regolith.data.db.ShareDao
import com.regolith.data.db.ShareEntity
import com.regolith.domain.library.FolderKind
import com.regolith.domain.library.ParsedName
import com.regolith.domain.library.TitleParser
import com.regolith.domain.media.DemoSource
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

/**
 * A library with no server behind it, for looking at the app away from a
 * share (on a train, on a plane, in a review).
 *
 * It writes the same Room rows a real scan would — servers, shares,
 * folders, files, playback progress — and copies a handful of bundled
 * clips into [DemoStore], one per file. Because the player, the artwork
 * pipeline and the probe all prefer a local copy when there is one
 * ([com.regolith.player.LocalMedia]), everything downstream behaves as it
 * does on a real share: titles play, scrubbing shows real frames, posters
 * are real frame grabs, Title Detail's codec rows are read off the
 * container.
 *
 * What it deliberately does NOT do: pretend to be a download. Demo files
 * live in their own directory, so "On this device" stays a true statement
 * about what has actually been copied off a share.
 *
 * Web analogy: seed data for a local dev database, plus the fixture files
 * the seeded rows point at.
 */
@Singleton
class DemoLibrary @Inject constructor(
    @ApplicationContext private val context: Context,
    private val serverDao: ServerDao,
    private val shareDao: ShareDao,
    private val folderDao: FolderDao,
    private val mediaFileDao: MediaFileDao,
    private val progressDao: PlaybackProgressDao,
    private val artwork: ArtworkRepository,
    private val store: DemoStore,
) {

    /** True while the demo server exists, so Settings can offer the opposite action. */
    val installed: Flow<Boolean> = serverDao.observeAll().map { servers -> servers.any { it.host == DemoSource.HOST } }

    /** Roughly what the demo occupies on the device, for the Settings row. */
    fun usedBytes(): Long = store.usedBytes()

    /**
     * Create the demo library, replacing any earlier one. Safe to call
     * twice; the second call rebuilds from scratch so a half-finished
     * install cannot leave rows pointing at files that were never written.
     */
    suspend fun install() = withContext(Dispatchers.IO) {
        remove()
        val now = System.currentTimeMillis()
        val serverId = serverDao.insert(
            ServerEntity(
                name = DEMO_SERVER_NAME,
                host = DemoSource.HOST,
                port = 445,
                authMode = "GUEST",
                username = null,
                lastSeenAtMs = now,
                createdAtMs = now,
            ),
        )
        val shareId = shareDao.insert(
            ShareEntity(
                serverId = serverId,
                name = DEMO_SHARE_NAME,
                enabled = true,
                freeBytes = 2_140_000_000_000L,
                totalBytes = 8_000_000_000_000L,
                lastScanAtMs = now,
            ),
        )
        val root = folderDao.insert(folder(shareId, parentId = null, relPath = "", name = DEMO_SHARE_NAME, kind = FolderKind.ROOT, now = now))

        // One `random` with a fixed seed: the demo is the same library every
        // time it is installed, which is what makes "it looked wrong here"
        // reproducible.
        val random = Random(SEED)
        var addedAt = now - 40 * DAY

        for (collection in CONTENT) {
            val collectionId = folderDao.insert(
                folder(shareId, root, collection.name, collection.name, FolderKind.COLLECTION, now),
            )
            for (group in collection.groups) {
                val parentId = if (group.name == null) {
                    collectionId
                } else {
                    folderDao.insert(
                        folder(shareId, collectionId, "${collection.name}/${group.name}", group.name, group.kind, now, TitleParser.parseFolderName(group.name)),
                    )
                }
                var seasonId = parentId
                if (group.season != null) {
                    seasonId = folderDao.insert(
                        folder(shareId, parentId, "${collection.name}/${group.name}/${group.season}", group.season, FolderKind.SEASON, now),
                    )
                }
                for (fileName in group.files) {
                    addedAt += random.nextLong(4 * HOUR, 3 * DAY)
                    val clip = group.clip
                    val relPath = listOfNotNull(collection.name, group.name, group.season, fileName).joinToString("/")
                    val parsed = TitleParser.parseVideoName(fileName)
                    val fileId = mediaFileDao.insert(
                        MediaFileEntity(
                            shareId = shareId,
                            folderId = seasonId,
                            relPath = relPath,
                            name = fileName,
                            ext = fileName.substringAfterLast('.', "mp4"),
                            // A plausible size for the resolution, not the
                            // clip's real 200 KB: the size is what the rows
                            // and Title Detail show, and 200 KB films look
                            // like a bug rather than a demo.
                            sizeBytes = clip.plausibleBytes(random),
                            modifiedAtMs = addedAt,
                            durationMs = clip.durationMs,
                            missing = false,
                            addedAtMs = addedAt,
                            lastSeenAtMs = now,
                            width = clip.width,
                            height = clip.height,
                            frameRate = 12f,
                            videoCodec = "video/avc",
                            hdr = false,
                            audioCodec = "audio/mp4a-latm",
                            audioChannels = 1,
                            audioSampleRate = 44_100,
                            probedAtMs = now,
                            titleParsed = parsed.title,
                            year = parsed.year,
                            season = parsed.season,
                            episode = parsed.episode,
                        ),
                    )
                    copyClip(clip, fileId)
                }
            }
        }

        // A few part-watched titles so Continue watching, the resume chips
        // and the progress bars have something to show, spread over the last
        // few days so "today" / "last night" / a weekday all appear.
        val playable = mediaFileDao.observeInShares(listOf(shareId)).first()
        PROGRESS.forEachIndexed { index, (nameFragment, fraction) ->
            val file = playable.firstOrNull { it.name.contains(nameFragment, ignoreCase = true) } ?: return@forEachIndexed
            val duration = file.durationMs ?: return@forEachIndexed
            progressDao.upsert(
                PlaybackProgressEntity(
                    fileId = file.id,
                    positionMs = (duration * fraction).toLong(),
                    durationMs = duration,
                    completed = false,
                    updatedAtMs = now - index * 26 * HOUR,
                ),
            )
        }
    }

    /**
     * Delete the demo library. The server row cascades to its shares,
     * folders and files; the clips and the artwork extracted from them are
     * ours to clean up.
     */
    suspend fun remove() = withContext(Dispatchers.IO) {
        val server = serverDao.byHost(DemoSource.HOST, 445) ?: run { store.deleteAll(); return@withContext }
        serverDao.delete(server.id)
        store.deleteAll()
        // Artwork is keyed by file id, and those ids are now free for a real
        // scan to reuse. Clearing the cache is cheaper than tracking which
        // rows were the demo's, and it refills itself on the next screen.
        artwork.clearAll()
    }

    private fun folder(
        shareId: Long,
        parentId: Long?,
        relPath: String,
        name: String,
        kind: FolderKind,
        now: Long,
        parsed: ParsedName? = null,
    ) = FolderEntity(
        shareId = shareId,
        parentId = parentId,
        relPath = relPath,
        name = name,
        fileCount = 0,
        byteCount = 0,
        lastListedAtMs = now,
        kind = kind.name,
        titleParsed = parsed?.title,
        year = parsed?.year,
    )

    /** One bundled clip becomes this file's bytes. Cheap: the largest is 316 KB. */
    private fun copyClip(clip: Clip, fileId: Long) {
        context.resources.openRawResource(clip.resId).use { input ->
            store.createFor(fileId).outputStream().use { output -> input.copyTo(output) }
        }
    }

    /** A bundled clip and the metadata the rows should claim about it. */
    private enum class Clip(val resId: Int, val width: Int, val height: Int, val durationMs: Long, private val bytesPerSecond: Long) {
        HD(R.raw.demo_hd, 1280, 720, 45_000, 1_100_000),
        FHD(R.raw.demo_fhd, 1920, 1080, 30_000, 2_400_000),
        WIDE(R.raw.demo_wide, 1280, 536, 60_000, 900_000),
        UHD(R.raw.demo_uhd, 3840, 2160, 20_000, 9_000_000),
        ;

        /** What a real file of this shape would weigh, give or take. */
        fun plausibleBytes(random: Random): Long =
            (durationMs / 1000) * bytesPerSecond * random.nextLong(80, 140) / 100 * 40
    }

    private data class Group(
        val name: String?,
        val kind: FolderKind,
        val files: List<String>,
        val clip: Clip,
        val season: String? = null,
    )

    private data class Collection(val name: String, val groups: List<Group>)

    private companion object {
        const val DEMO_SERVER_NAME = "DEMO NAS"
        const val DEMO_SHARE_NAME = "media"
        const val SEED = 20260909L
        const val HOUR = 3_600_000L
        const val DAY = 24 * HOUR

        /** Which titles start part-watched, and how far in. */
        val PROGRESS = listOf(
            "Arrival" to 0.42f,
            "S01E03" to 0.68f,
            "Heat" to 0.11f,
            "GH010423" to 0.85f,
        )

        /**
         * The shape of a share people actually have: a couple of collections,
         * title folders beside loose files, a show with seasons, and a folder
         * of camera files whose names parse into nothing — that last one is
         * the "No match" tile, which is the case most worth looking at.
         */
        val CONTENT = listOf(
            Collection(
                "Films",
                listOf(
                    Group("Arrival (2016)", FolderKind.TITLE, listOf("Arrival.2016.2160p.mkv"), Clip.UHD),
                    Group("Blade Runner 2049 (2017)", FolderKind.TITLE, listOf("Blade.Runner.2049.2017.1080p.mkv"), Clip.FHD),
                    Group("Dune (2021)", FolderKind.TITLE, listOf("Dune.2021.2160p.mkv"), Clip.UHD),
                    Group(
                        null, FolderKind.COLLECTION,
                        listOf(
                            "Heat.1995.1080p.mkv",
                            "The.Thing.1982.720p.mkv",
                            "Sicario.2015.1080p.mkv",
                            "Le.Samourai.1967.1080p.mkv",
                        ),
                        Clip.WIDE,
                    ),
                ),
            ),
            Collection(
                "Series",
                listOf(
                    Group(
                        "Severance", FolderKind.SHOW,
                        listOf("Severance.S01E01.1080p.mkv", "Severance.S01E02.1080p.mkv", "Severance.S01E03.1080p.mkv", "Severance.S01E04.1080p.mkv"),
                        Clip.FHD, season = "Season 01",
                    ),
                    Group(
                        "The Bear", FolderKind.SHOW,
                        listOf("The.Bear.S02E01.1080p.mkv", "The.Bear.S02E02.1080p.mkv", "The.Bear.S02E03.1080p.mkv"),
                        Clip.FHD, season = "Season 02",
                    ),
                ),
            ),
            Collection(
                "Documentaries",
                listOf(
                    Group(
                        null, FolderKind.COLLECTION,
                        listOf("Planet.Earth.II.S01E01.2160p.mkv", "Free.Solo.2018.1080p.mkv"),
                        Clip.UHD,
                    ),
                ),
            ),
            Collection(
                "Home videos",
                listOf(
                    Group(
                        null, FolderKind.COLLECTION,
                        listOf("GH010423.MP4", "IMG_4821.mov", "DSC_0099.mp4", "VID_20260714_183355.mp4"),
                        Clip.HD,
                    ),
                ),
            ),
        )
    }
}
