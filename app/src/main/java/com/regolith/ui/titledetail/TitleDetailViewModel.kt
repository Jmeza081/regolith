package com.regolith.ui.titledetail

import com.regolith.data.spoof.spoofed
import com.regolith.data.spoof.SpoofMode
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import com.regolith.data.db.FolderEntity
import com.regolith.data.db.MediaFileEntity
import com.regolith.data.fileops.FileOpsRepository
import com.regolith.data.repository.LibraryRepository
import com.regolith.domain.playback.Chapter
import com.regolith.domain.playback.ChapterStills
import com.regolith.data.repository.UserChapterRepository
import com.regolith.data.repository.PhoneLibrary
import com.regolith.data.repository.PlaybackRepository
import com.regolith.domain.media.PhonePaths
import com.regolith.data.transfer.TransferRepository
import com.regolith.data.transfer.TransferRepository.Companion.causeEnum
import com.regolith.data.transfer.TransferRepository.Companion.statusEnum
import com.regolith.domain.artwork.ArtworkKind
import com.regolith.domain.artwork.ArtworkOwner
import com.regolith.domain.artwork.ArtworkRequest
import com.regolith.domain.media.MediaInfo
import com.regolith.domain.playback.VideoInfo
import com.regolith.domain.smb.SmbFailure
import com.regolith.player.MediaProbe
import com.regolith.domain.fileops.FileOpTarget
import com.regolith.ui.components.PathCrumb
import com.regolith.ui.util.FileOpMessages
import com.regolith.ui.util.formatBytes
import com.regolith.ui.util.formatDurationShort
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Title Detail: one file, its facts, the one red action. The facts come
 * from the file row; the first visit runs the container probe once and
 * stores what it finds, so every later visit (and the Browse chips) is
 * instant.
 */
@UnstableApi
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel(assistedFactory = TitleDetailViewModel.Factory::class)
class TitleDetailViewModel @AssistedInject constructor(
    @Assisted private val fileId: Long,
    private val library: LibraryRepository,
    private val playback: PlaybackRepository,
    private val probe: MediaProbe,
    private val transfers: TransferRepository,
    private val fileOps: FileOpsRepository,
    private val phone: PhoneLibrary,
    private val spoof: SpoofMode,
    private val chapters: UserChapterRepository,
    private val artwork: com.regolith.data.artwork.ArtworkRepository,
) : ViewModel() {

    @AssistedFactory
    interface Factory {
        fun create(fileId: Long): TitleDetailViewModel
    }

    // The hero runs the full width of the window — 2076px unfolded — so it
    // takes the 1280x720 backdrop, not the 320x180 tile thumb. Known from the
    // id alone, so it is there from the first frame: a poster flying into it
    // lands on a picture, not on "no picture" while the row loads.
    private val _uiState = MutableStateFlow(
        TitleDetailUiState(fileId = fileId, artwork = ArtworkRequest(ArtworkOwner.File(fileId), ArtworkKind.BACKDROP)),
    )
    val uiState: StateFlow<TitleDetailUiState> = _uiState

    private var probed = false

    init {
        viewModelScope.launch {
            // Its chapters, live: marking or naming one in the player and
            // coming back shows it here. The same list and pictures as the
            // player's sheet, worked out the way the chapter-picture walk does
            // (ArtworkRepository.shownChapters), so a film it has been through
            // costs no read of the share. Names through spoof mode, which
            // makes them up, as it does everywhere else.
            var making: Job? = null
            combine(
                chapters.observe(fileId),
                library.observeFile(fileId).map { it?.durationMs ?: 0L }.distinctUntilChanged(),
                spoof.state,
            ) { user, durationMs, spoofed -> Triple(user, durationMs, spoofed) }
                .mapLatest { (user, durationMs, spoofed) ->
                    val shown = artwork.shownChapters(fileId, user, durationMs)
                    val frames = ChapterStills.timesFor(shown.chapters, durationMs, shown.source)
                    titleChapters(spoofed?.chapters(shown.chapters) ?: shown.chapters, frames)
                }
                .collect { list ->
                    _uiState.update { it.copy(chapters = list) }
                    // One batch for any pictures the walk has not made yet,
                    // which each tile then joins, rather than a tile at a time.
                    making?.cancel()
                    making = launch { artwork.makeMoments(fileId, list.map { it.frameMs }) }
                }
        }
        viewModelScope.launch {
            transfers.observeForFile(fileId).collect { row ->
                _uiState.update { it.copy(transfer = row?.let { r -> TransferView(r.statusEnum(), r.bytesDone, r.totalBytes, r.causeEnum(), r.causeBytes) }) }
            }
        }
        viewModelScope.launch {
            // The name through spoof mode; the path's folders below are made
            // up one by one, each as its own tile is.
            library.observeFile(fileId).spoofed(spoof) { it?.let(::file) }.collect { file ->
                if (file == null) return@collect
                val progress = playback.progress(fileId)?.takeUnless { it.completed }
                val share = library.shareLabel(file.shareId).substringAfter(" · ")
                val isPhone = phone.isPhoneFile(fileId)
                val realFolder = file.relPath.substringBeforeLast('/', "")
                val spoofed = spoof.current
                val phoneFolderShown = spoofed?.phonePath(realFolder) ?: PhonePaths.display(realFolder)
                // A phone video's path is where it sits on the phone, "DCIM/Camera",
                // not the absolute path it is keyed by.
                val crumbs = if (isPhone) {
                    phoneCrumbs(phoneFolderShown, file.folderId)
                } else {
                    shareCrumbs(folderChain(file.folderId), share) { name -> spoofed?.folderName(name) ?: name }
                }
                _uiState.update {
                    it.copy(
                        loaded = true,
                        title = file.name.substringBeforeLast('.'),
                        chips = chipsFor(file),
                        progressMs = progress?.positionMs?.takeIf { p -> p > 0 },
                        durationMs = progress?.durationMs ?: file.durationMs,
                        pathCrumbs = crumbs,
                        phone = isPhone,
                        spoofed = spoof.current != null,
                        phoneFolder = if (isPhone) phoneFolderShown.substringAfterLast('/') else "",
                        videoLine = if (file.probedAtMs != null) infoOf(file).videoLine else null,
                        audioLine = if (file.probedAtMs != null) infoOf(file).audioLine else null,
                        modifiedAtMs = file.modifiedAtMs,
                        fileName = file.name,
                        sizeLabel = formatBytes(file.sizeBytes),
                    )
                }
                if (file.probedAtMs == null && !probed) runProbe()
            }
        }
    }

    /** "Keep on this device" and "Try again". */
    fun keepOnDevice() = viewModelScope.launch { transfers.start(fileId) }.let { }

    /** Cancel a transfer in flight, or remove the finished copy. The share is untouched either way. */
    fun removeFromDevice() = viewModelScope.launch { transfers.remove(fileId) }.let { }

    // ── Managing the file on the share (P12) ───────────────────────────
    //
    // Both verbs go through a dialog, and both report back onto this state
    // rather than throwing: the share refusing is an ordinary thing that
    // the screen has a sentence for.

    // Both dialogs count the files that go with this one, from the last
    // listing of its folder, so they open at once.

    fun startRename() {
        if (spoof.current != null) return
        viewModelScope.launch {
            val companions = library.companionCount(listOf(fileId))
            _uiState.update { it.copy(renaming = true, companions = companions, fileOpError = null) }
        }
    }

    fun startDelete() {
        if (spoof.current != null) return
        viewModelScope.launch {
            val companions = library.companionCount(listOf(fileId))
            _uiState.update { it.copy(confirmingDelete = true, companions = companions, fileOpError = null) }
        }
    }

    fun dismissFileOp() = _uiState.update { it.copy(renaming = false, confirmingDelete = false) }

    fun dismissFileOpError() = _uiState.update { it.copy(fileOpError = null) }

    /** [newBaseName] is what the field holds: the name without its extension. */
    fun rename(newBaseName: String) {
        _uiState.update { it.copy(renaming = false) }
        viewModelScope.launch {
            val result = fileOps.rename(FileOpTarget.file(fileId), newBaseName)
            // The row keeps its id, so the screen's own flows redraw the new
            // name; only a failure needs saying.
            result.failures.firstOrNull()?.let { f ->
                _uiState.update { it.copy(fileOpError = FileOpMessages.forFailure(f, "rename")) }
            }
        }
    }

    fun confirmDelete() {
        _uiState.update { it.copy(confirmingDelete = false) }
        viewModelScope.launch {
            val result = fileOps.delete(listOf(FileOpTarget.file(fileId)))
            if (result.ok) {
                _uiState.update { it.copy(deleted = true) }
            } else {
                _uiState.update { it.copy(fileOpError = FileOpMessages.forFailure(result.failures.first(), "delete")) }
            }
        }
    }

    // ── A phone video (Phone storage) ──────────────────────────────────

    /** "Hide from Regolith": the file stays in its folder, and the screen leaves as if it were gone. */
    fun hideFromRegolith() {
        viewModelScope.launch {
            phone.hideFile(fileId)
            _uiState.update { it.copy(deleted = true) }
        }
    }

    /** Ask MediaStore for the system's delete sheet; the screen launches it. */
    fun startPhoneDelete() {
        viewModelScope.launch {
            val request = phone.deleteRequest(fileId)
            _uiState.update {
                if (request == null) it.copy(fileOpError = "This video is no longer on the phone.") else it.copy(phoneDeleteRequest = request)
            }
        }
    }

    /** The sheet is up; forget the request so a recomposition cannot launch it twice. */
    fun phoneDeleteLaunched() = _uiState.update { it.copy(phoneDeleteRequest = null) }

    /** The sheet came back. Approved means the file is gone; re-read the phone, then leave. */
    fun phoneDeleteAnswered(approved: Boolean) {
        if (!approved) return
        viewModelScope.launch {
            phone.sync()
            _uiState.update { it.copy(deleted = true) }
        }
    }

    private fun runProbe() {
        probed = true
        viewModelScope.launch {
            _uiState.update { it.copy(probing = true, probeError = null) }
            try {
                library.saveProbe(fileId, probe.probe(fileId))
                _uiState.update { it.copy(probing = false) }
            } catch (e: SmbFailure) {
                _uiState.update { it.copy(probing = false, probeError = "Couldn't reach the share to read the file.") }
            } catch (e: Exception) {
                _uiState.update { it.copy(probing = false, probeError = "Couldn't read this file's details.") }
            }
        }
    }

    /** [folderId] and every folder above it, the share's root first. */
    private suspend fun folderChain(folderId: Long): List<FolderEntity> {
        val chain = ArrayDeque<FolderEntity>()
        var next: Long? = folderId
        // A loop in the rows would be a bug elsewhere; it must not hang this page.
        while (next != null && chain.size < MAX_PATH_DEPTH) {
            val folder = library.folder(next) ?: break
            chain.addFirst(folder)
            next = folder.parentId
        }
        return chain
    }

    private fun chipsFor(file: MediaFileEntity): List<String> = listOfNotNull(
        VideoInfo.resolutionLabelFor(file.width, file.height).ifEmpty { null },
        if (file.hdr == true) "HDR" else null,
        file.durationMs?.takeIf { it > 0 }?.let { formatDurationShort(it) },
        formatBytes(file.sizeBytes),
        VideoInfo.codecLabelFor(file.videoCodec).ifEmpty { null },
    )

    private fun infoOf(file: MediaFileEntity) = MediaInfo(
        durationMs = file.durationMs,
        width = file.width,
        height = file.height,
        rotationDegrees = file.rotationDegrees,
        frameRate = file.frameRate,
        videoMimeType = file.videoCodec,
        hdr = file.hdr ?: false,
        audioMimeType = file.audioCodec,
        audioChannels = file.audioChannels,
        audioSampleRate = file.audioSampleRate,
    )
}

/**
 * A share video's path as crumbs: [chain] is its folder and every folder
 * above it, the share's root first. The root reads as the share's name,
 * [shareName], and every other folder as [nameOf] its name (spoof mode makes
 * them up, as it does their tiles). Each opens its own folder.
 */
internal fun shareCrumbs(chain: List<FolderEntity>, shareName: String, nameOf: (String) -> String): List<PathCrumb> {
    val crumbs = chain.map { f -> PathCrumb(if (f.parentId == null) shareName else nameOf(f.name), f.id) }
    // A chain cut short by a missing row still starts at the share, which then cannot be opened.
    return if (chain.firstOrNull()?.parentId == null) crumbs else listOf(PathCrumb(shareName, null)) + crumbs
}

/**
 * A phone video's path as crumbs: [shown] is where it sits as the app shows
 * it ("DCIM/Camera", "SD card · Movies"). Only the last step, the album the
 * video is in, is a folder of the library's ([folderId]); the phone's
 * folders above it are not.
 */
internal fun phoneCrumbs(shown: String, folderId: Long): List<PathCrumb> {
    val parts = shown.split('/').filter { it.isNotEmpty() }
    return parts.mapIndexed { i, part -> PathCrumb(part, folderId.takeIf { i == parts.lastIndex }) }
}

/**
 * A video's chapters for its page, in the order they play: each named as the
 * player's sheet names it ([Chapter.label]: its name, else "Part n"), with the
 * time its picture is taken from, [framesMs] in the same order.
 */
internal fun titleChapters(chapters: List<Chapter>, framesMs: List<Long>): List<TitleChapter> =
    chapters.mapIndexed { i, c -> TitleChapter(c.startMs, c.label(i).trim(), framesMs.getOrElse(i) { c.startMs }) }

/** Deeper than any real share; past it the path stops, rather than following a loop in the rows. */
private const val MAX_PATH_DEPTH = 64
