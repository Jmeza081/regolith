package com.regolith.data.spoof

import com.regolith.domain.media.PhonePaths
import com.regolith.data.db.FolderEntity
import com.regolith.data.db.MediaFileEntity
import com.regolith.data.prefs.AppPreferences
import com.regolith.domain.artwork.ArtworkKind
import com.regolith.domain.artwork.ArtworkOwner
import com.regolith.domain.artwork.ArtworkRequest
import com.regolith.domain.model.BrowseItem
import com.regolith.domain.playback.Chapter
import com.regolith.domain.playback.ChapterMatch
import com.regolith.domain.spoof.SpoofNames
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.runBlocking
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Settings › Demo › Spoof mode, as the rest of the app reads it: [state] is
 * a [Spoof] while it is on and null while it is off.
 *
 * Spoof mode changes what the PHONE shows and nothing else: titles, file
 * and folder names, paths and chapter names are made up ([SpoofNames]), and
 * every picture of the library is a stock photo instead ([SpoofImage]). The
 * rows in Room stay real, the share is never written, and the background
 * work (scans, artwork, transfers) keeps reading real names, because it is
 * fed straight from the database rather than through a screen.
 *
 * How a screen uses it: its ViewModel passes the rows it reads through
 * [spoofed] before building anything from them. A row that went through it
 * has made-up names and the real ids and paths, so playing, downloading and
 * picking still find the real file. While it is on the library can't be
 * changed at all (`ReadOnlySource.SPOOF`), so nothing made up can ever be
 * written back.
 *
 * The first value is read synchronously, once, when this is first created:
 * otherwise the first screen after a launch would draw the real names for a
 * frame before the setting arrived, which is exactly what spoof mode is for
 * preventing. It is one small read of the settings file, at start.
 */
@Singleton
class SpoofMode @Inject constructor(prefs: AppPreferences) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val state: StateFlow<Spoof?> = prefs.spoofSalt
        .map { salt -> salt?.let(::Spoof) }
        .stateIn(scope, SharingStarted.Eagerly, runBlocking { prefs.spoofSalt.first() }?.let(::Spoof))

    /** Spoof mode right now, for code that is not a flow: a worker's notification, the player's media item. */
    val current: Spoof? get() = state.value
}

/**
 * [this] flow with [transform] applied while spoof mode is on, and again
 * each time it is switched, so a screen changes the moment it does.
 *
 * `rows.spoofed(spoof) { files(it) }` is the whole of what a ViewModel adds.
 */
fun <T> Flow<T>.spoofed(mode: SpoofMode, transform: Spoof.(T) -> T): Flow<T> =
    combine(this, mode.state) { value, spoof -> if (spoof == null) value else spoof.transform(value) }

/**
 * Spoof mode, switched on, with the [salt] every made-up name and photo is
 * worked out from. Each function returns a copy with the names made up and
 * everything else (ids, paths, sizes, years) as it was.
 */
class Spoof(val salt: Long) {

    /** A made-up title for a free-standing name: a chapter, a server-less label. */
    fun title(real: String): String = SpoofNames.title(real, salt)

    fun fileName(real: String): String = SpoofNames.fileName(real, salt)

    fun folderName(real: String): String = SpoofNames.folderName(real, salt)

    /** A path shown as text ("Films/Arrival (2016)/"): every part made up as its own tile is. */
    fun path(real: String): String = SpoofNames.path(real, salt)

    /**
     * Where a phone video sits, as the app shows it ("DCIM/Camera", "SD card ·
     * Movies"), with its folders made up. Made readable first and made up
     * second: the other way round, the storage prefix it is read by would be
     * made up too, and every phone folder would read as an SD card.
     */
    fun phonePath(relPath: String): String {
        val shown = PhonePaths.display(relPath)
        return when {
            shown.startsWith(SD_CARD) -> SD_CARD + path(shown.removePrefix(SD_CARD))
            shown == "Internal storage" || shown == "SD card" -> shown
            else -> path(shown)
        }
    }

    /**
     * A video with a made-up name. Its parsed title (when it has one) is the
     * same words as its file name, so "Arrival (2016)" on the tile and
     * "Arrival.2016.mkv" in Title Detail become "Quiet Lantern (2016)" and
     * "Quiet Lantern.mkv". The year and episode are kept: they read as a
     * library and say nothing about what is in it.
     */
    fun file(file: MediaFileEntity): MediaFileEntity = file.copy(
        name = fileName(file.name),
        titleParsed = file.titleParsed?.let { SpoofNames.titleOfFile(file.name, salt) },
    )

    fun files(files: List<MediaFileEntity>): List<MediaFileEntity> = files.map(::file)

    fun folder(folder: FolderEntity): FolderEntity = folder.copy(
        name = folderName(folder.name),
        titleParsed = folder.titleParsed?.let { folderName(folder.name) },
    )

    fun folders(folders: List<FolderEntity>): List<FolderEntity> = folders.map(::folder)

    /** A named chapter found by Search or gathered for a profile: its name and its film's, made up. */
    fun match(match: ChapterMatch): ChapterMatch = match.copy(
        title = title(match.title),
        fileName = fileName(match.fileName),
        fileTitle = match.fileTitle?.let { SpoofNames.titleOfFile(match.fileName, salt) },
    )

    fun matches(matches: List<ChapterMatch>): List<ChapterMatch> = matches.map(::match)

    /** The player's chapters: a named one made up, an unnamed one left to read "Part n". */
    fun chapters(chapters: List<Chapter>): List<Chapter> = chapters.map { c -> c.copy(title = c.title?.let(::title)) }

    fun browseItems(items: List<BrowseItem>): List<BrowseItem> = items.map { item ->
        when (item) {
            is BrowseItem.Folder -> item.copy(name = folderName(item.name))
            is BrowseItem.File -> item.copy(name = fileName(item.name))
            is BrowseItem.Other -> item.copy(name = fileName(item.name))
        }
    }

    /**
     * The stand-in photo for [artwork]: picked by the owner's database id,
     * never its name, and sized for the kind of picture it stands in for.
     */
    fun image(artwork: ArtworkRequest): SpoofImage {
        val (width, height) = when (artwork.kind) {
            ArtworkKind.POSTER -> 200 to 300
            ArtworkKind.THUMB -> 320 to 180
            ArtworkKind.BACKDROP -> 640 to 360
        }
        return SpoofImage(SpoofNames.imageSeed(ownerKey(artwork.owner), salt), width, height)
    }

    private fun ownerKey(owner: ArtworkOwner): String = "${owner.typeName}:${owner.id}:${owner.variant}"

    private companion object {
        const val SD_CARD = "SD card · "
    }
}
