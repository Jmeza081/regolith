package com.regolith.ui.lightbox

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.regolith.data.fileops.FileOpsRepository
import com.regolith.data.pictures.PictureRepository
import com.regolith.data.pictures.PictureSaver
import com.regolith.domain.fileops.FileOpTarget
import com.regolith.domain.fileops.ReadOnlySource
import com.regolith.ui.util.FileActions
import com.regolith.data.prefs.AppPreferences
import com.regolith.data.repository.LibraryRepository
import com.regolith.data.spoof.SpoofMode
import com.regolith.data.spoof.spoofed
import com.regolith.domain.library.comparator
import com.regolith.ui.library.LibraryTile
import com.regolith.ui.library.PictureTile
import com.regolith.ui.library.inOrder
import com.regolith.ui.library.pictureTiles
import com.regolith.ui.library.tileName
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The lightbox's page: the pictures it pages through, in order, and what they are in. */
data class LightboxUiState(
    /** The album's name, for "3 of 86 · Lake house 2024". */
    val album: String = "",
    val pictures: List<PictureTile> = emptyList(),
    val loaded: Boolean = false,
    /**
     * Why the pictures cannot be changed here (spoof mode, the demo library),
     * or null when they can: Set as poster, Rename, Move, Save and Delete
     * stand down, and More says why.
     */
    val readOnly: String? = null,
)

/**
 * The pictures of one folder, for the lightbox to page through
 * ([LightboxScreen]), in exactly the order the screen it was opened from
 * showed them: the Images tab's ([com.regolith.domain.library.PictureOrder]),
 * or, for a picture lying loose beside albums, the wall's own ([onWall]).
 * Both are read from the settings rather than handed over, so the order
 * survives the app being killed and coming back here.
 */
@HiltViewModel(assistedFactory = LightboxViewModel.Factory::class)
class LightboxViewModel @AssistedInject constructor(
    @Assisted("folder") val folderId: Long,
    /** The picture it opened on. */
    @Assisted("picture") val pictureId: Long,
    @Assisted private val onWall: Boolean,
    library: LibraryRepository,
    prefs: AppPreferences,
    spoof: SpoofMode,
    private val pictures: PictureRepository,
    private val saver: PictureSaver,
    private val fileOps: FileOpsRepository,
    fileActionsFactory: FileActions.Factory,
) : ViewModel() {

    @AssistedFactory
    interface Factory {
        fun create(@Assisted("folder") folderId: Long, @Assisted("picture") pictureId: Long, onWall: Boolean): LightboxViewModel
    }

    private var measuring: Job? = null

    /**
     * Rename, Move and Delete for the picture on screen, as an album's
     * selection has them: the same dialogs, sheet and messages
     * ([FileActions.renameOne] and its kin act on the one picture and leave
     * any selection alone).
     */
    val fileActions: FileActions = fileActionsFactory.create(viewModelScope)

    val uiState: StateFlow<LightboxUiState> = run {
        val folder = library.observeFolder(folderId).spoofed(spoof) { it?.let(::folder) }
        val others = library.observeOtherFilesIn(folderId).spoofed(spoof) { otherFiles(it) }
        val videos = library.observeFilesIn(listOf(folderId)).spoofed(spoof) { files(it) }
        val order: kotlinx.coroutines.flow.Flow<(List<PictureTile>) -> List<PictureTile>> = if (onWall) {
            prefs.libraryOrder.map { o -> { tiles -> tiles.map { LibraryTile.Picture(it) }.sortedWith(o.comparator(addedByDay = true)).map { it.picture } } }
        } else {
            prefs.pictureOrder.map { o -> { tiles -> tiles.inOrder(o) } }
        }
        val readOnly = combine(spoof.state, folder) { spoofed, f ->
            when (if (spoofed != null) ReadOnlySource.SPOOF else f?.let { fileOps.readOnly(listOf(it.shareId)) }) {
                ReadOnlySource.SPOOF -> "Nothing can be changed while spoof mode is on"
                ReadOnlySource.DEMO -> "The demo library can't be changed"
                ReadOnlySource.PHONE -> "Pictures on this phone can't be changed here"
                null -> null
            }
        }
        combine(folder, others, videos, order, readOnly) { f, os, vs, sort, locked ->
            val shown = vs.associate { it.name to it.tileName() }
            LightboxUiState(album = f?.name.orEmpty(), pictures = sort(pictureTiles(os, shown)), loaded = true, readOnly = locked)
        }
            .onEach { state -> if (state.pictures.any { it.width == null }) measure() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LightboxUiState())
    }

    /** More › Save to phone: [picture] copied into the gallery, said when it is there. */
    fun save(picture: PictureTile) = saver.save(listOf(picture.pictureId))

    fun rename(picture: PictureTile) = fileActions.renameOne(FileOpTarget.other(picture.pictureId))

    fun move(picture: PictureTile) = fileActions.moveOne(FileOpTarget.other(picture.pictureId), here = folderId)

    fun delete(picture: PictureTile) = fileActions.deleteOne(FileOpTarget.other(picture.pictureId))

    /** Pictures not measured yet, measured now: the picture's box is the shape of the picture. */
    private fun measure() {
        if (measuring?.isActive == true) return
        measuring = viewModelScope.launch { pictures.measureFolder(folderId) }
    }
}
