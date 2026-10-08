package com.regolith.ui.poster

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.regolith.data.artwork.PosterRepository
import com.regolith.data.spoof.SpoofMode
import com.regolith.domain.artwork.AnimatedPoster
import com.regolith.domain.artwork.CropRect
import com.regolith.domain.artwork.FolderPoster
import com.regolith.domain.artwork.FolderPosterOutcome
import com.regolith.domain.artwork.PickedPoster
import com.regolith.domain.artwork.WholePoster
import com.regolith.domain.smb.SmbFailure
import com.regolith.ui.components.PosterMaking
import com.regolith.ui.components.PosterSwap
import com.regolith.ui.navigation.RegolithKey
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Set as poster's state and actions (see [SetPosterUiState]): a picture on
 * the share ([RegolithKey.SetPoster.pictureId]) or one picked on the phone
 * ([RegolithKey.SetPoster.uri]) made the folder's poster, cut 2:3 or whole.
 *
 * The picture is fetched whole and decoded once, at a size that still makes
 * a full-size poster from a crop ([PosterRepository.FRAMING_LONG_SIDE]).
 * Before anything is written, one listing of the folder says whether it has
 * a poster already; when it does, the sheet shows it and the dated name it
 * will be kept under, and nothing happens until Set as poster there.
 */
@HiltViewModel(assistedFactory = SetPosterViewModel.Factory::class)
class SetPosterViewModel @AssistedInject constructor(
    @Assisted private val key: RegolithKey.SetPoster,
    private val posters: PosterRepository,
    spoof: SpoofMode,
) : ViewModel() {

    @AssistedFactory
    interface Factory {
        fun create(key: RegolithKey.SetPoster): SetPosterViewModel
    }

    private val _state = MutableStateFlow(SetPosterUiState())
    val state: StateFlow<SetPosterUiState> = _state.asStateFlow()

    /** The crop waiting on the sheet's answer (null inside: the whole picture). */
    private var pending: Pending? = null

    private class Pending(val crop: CropRect?)

    init {
        if (spoof.current != null) _state.update { it.copy(readOnly = "Nothing can be changed while spoof mode is on") }
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        val pictureId = key.pictureId
        val uri = key.uri
        val decoded: Bitmap? = when {
            pictureId != null -> {
                val info = posters.picturePoster(pictureId)
                if (info == null) {
                    _state.update { it.copy(loading = false, readOnly = it.readOnly ?: NO_SHARE) }
                    return
                }
                _state.update {
                    it.copy(folderName = info.folderName, pictureTitle = info.name.substringBeforeLast('.'), pictureName = info.name, wholeHow = info.whole)
                }
                posters.decodePicture(pictureId)
            }
            uri != null -> {
                val info = posters.phonePoster(key.folderId, uri)
                if (info == null) {
                    _state.update { it.copy(loading = false, readOnly = it.readOnly ?: NO_SHARE) }
                    return
                }
                _state.update { it.copy(folderName = info.folderName, moves = info.picked == PickedPoster.ANIMATED) }
                posters.decodePhone(uri)
            }
            else -> null
        }
        _state.update { it.copy(picture = decoded, loading = false, unreadable = decoded == null) }
    }

    /** The choice under the picture: the 2:3 crop, or the whole picture. */
    fun setWhole(whole: Boolean) = _state.update { it.copy(whole = whole, error = null) }

    /**
     * Set as poster. [crop] is in the decoded picture's pixels, or null for
     * the whole picture. A folder with a poster already gets the sheet first.
     */
    fun save(crop: CropRect?) {
        val s = _state.value
        val picture = s.picture ?: return
        if (!s.canSave) return
        _state.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            val plan = plan(s, crop)
            val kept = try {
                posters.keptIn(key.folderId, plan.name, leave = s.pictureName)
            } catch (e: SmbFailure) {
                _state.update { it.copy(saving = false, error = UNREACHABLE) }
                return@launch
            }
            if (kept.isEmpty()) {
                write(picture, crop)
                return@launch
            }
            pending = Pending(crop)
            val preview = withContext(Dispatchers.Default) { PosterRepository.preview(picture, crop) }
            val swap = PosterSwap.of(
                folderId = key.folderId,
                folderName = s.folderName,
                kept = kept,
                next = preview.asImageBitmap(),
                nextName = plan.name,
                making = plan.making,
                source = s.pictureTitle.orEmpty(),
            )
            _state.update { it.copy(saving = false, swap = swap) }
        }
    }

    /** The sheet's Set as poster. */
    fun confirmSwap() {
        val waiting = pending ?: return
        val picture = _state.value.picture ?: return
        if (_state.value.saving) return
        _state.update { it.copy(saving = true) }
        viewModelScope.launch { write(picture, waiting.crop) }
    }

    /** The sheet's Cancel: nothing is written. */
    fun dismissSwap() {
        pending = null
        _state.update { it.copy(swap = null) }
    }

    /** What the poster will be called, and how it is made, for the sheet. */
    private class Plan(val name: String, val making: PosterMaking)

    private fun plan(s: SetPosterUiState, crop: CropRect?): Plan = when {
        key.pictureId == null && crop != null -> Plan(FolderPoster.NAME, PosterMaking.PHONE_CROP)
        key.pictureId == null -> Plan(if (s.moves) AnimatedPoster.NAME else FolderPoster.NAME, PosterMaking.PHONE_WHOLE)
        crop != null -> Plan(FolderPoster.NAME, PosterMaking.CROP)
        s.wholeHow == WholePoster.RENAME -> Plan(FolderPoster.renamedTo(s.pictureName.orEmpty()), PosterMaking.RENAMED)
        else -> Plan(FolderPoster.NAME, PosterMaking.COPY)
    }

    private suspend fun write(picture: Bitmap, crop: CropRect?) {
        val pictureId = key.pictureId
        val uri = key.uri
        val outcome = when {
            pictureId != null && crop == null && _state.value.wholeHow == WholePoster.RENAME -> posters.adoptPicture(pictureId)
            pictureId != null -> posters.posterFromPicture(pictureId, picture, crop)
            uri != null -> posters.posterFromPhone(key.folderId, uri, picture.takeIf { crop != null }, crop)
            else -> FolderPosterOutcome.FAILED
        }
        pending = null
        _state.update {
            when (outcome) {
                FolderPosterOutcome.SAVED, FolderPosterOutcome.SAVED_STILL_TOO_BIG, FolderPosterOutcome.SAVED_STILL_NESTED ->
                    it.copy(saving = false, swap = null, done = true)
                FolderPosterOutcome.UNREADABLE -> it.copy(saving = false, swap = null, error = "Couldn’t read that picture.")
                FolderPosterOutcome.READ_ONLY -> it.copy(saving = false, swap = null, error = "This share is read-only, so the poster can’t be saved there.")
                FolderPosterOutcome.UNREACHABLE -> it.copy(saving = false, swap = null, error = UNREACHABLE)
                FolderPosterOutcome.FAILED -> it.copy(saving = false, swap = null, error = "The poster couldn’t be saved. Try again.")
            }
        }
    }

    private companion object {
        const val UNREACHABLE = "Couldn’t reach the server. Check the connection and try again."
        const val NO_SHARE = "This folder isn’t on a share, so it can’t be given a poster here."
    }
}
