package com.regolith.ui.story

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.regolith.data.pictures.PictureOriginals
import com.regolith.data.prefs.AppPreferences
import com.regolith.domain.media.GifTiming
import com.regolith.domain.playback.Story
import com.regolith.ui.library.AlbumPictures
import com.regolith.ui.navigation.RegolithKey
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A collection's pictures played as a story ([StoryScreen], the canvas
 * "Images on the share", Story-Play): which picture is up, and moving on.
 *
 * The running order is taken once, as the story starts ([AlbumPictures],
 * shuffled by [RegolithKey.Story.shuffleSeed] when there is one): a story
 * is a showing, and a picture renamed elsewhere meanwhile should not make
 * it jump. While a picture shows, the next one is fetched off the share, so
 * none arrives late; a moving GIF has its own length read, so it can play
 * through once ([GifTiming]).
 */
@HiltViewModel(assistedFactory = StoryViewModel.Factory::class)
class StoryViewModel @AssistedInject constructor(
    @Assisted private val key: RegolithKey.Story,
    albums: AlbumPictures,
    prefs: AppPreferences,
    private val originals: PictureOriginals,
) : ViewModel() {

    @AssistedFactory
    interface Factory {
        fun create(key: RegolithKey.Story): StoryViewModel
    }

    private val _state = MutableStateFlow(StoryUiState(folderId = key.folderId))
    val state: StateFlow<StoryUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val album = albums.observe(key.folderId, key.onWall).first()
            val byId = album.pictures.associateBy { it.pictureId }
            val order = Story.order(album.pictures.map { it.pictureId }, key.shuffleSeed)
            val start = Story.startIndex(order, key.startPictureId)
            _state.update { it.copy(album = album.name, pictures = order.mapNotNull(byId::get), index = start, loaded = true) }
            prepare(start)
        }
        viewModelScope.launch { prefs.storyPace.collect { pace -> _state.update { it.copy(pace = pace) } } }
    }

    /** On to the next picture; past the last, the story is over. */
    fun next() {
        val s = _state.value
        if (s.index + 1 >= s.pictures.size) {
            _state.update { it.copy(finished = true) }
            return
        }
        _state.update { it.copy(index = it.index + 1, step = it.step + 1) }
        prepare(s.index + 1)
    }

    /** Back a picture, or, on the first, that picture from its start. */
    fun previous() {
        _state.update { it.copy(index = (it.index - 1).coerceAtLeast(0), step = it.step + 1) }
    }

    fun togglePause() = _state.update { it.copy(paused = !it.paused) }

    /** For the picture at [index]: its GIF length when it moves, and the next picture fetched meanwhile. */
    private fun prepare(index: Int) {
        val pictures = _state.value.pictures
        val current = pictures.getOrNull(index) ?: return
        if (current.gif && current.pictureId !in _state.value.gifMs) {
            viewModelScope.launch {
                val file = originals.file(current.pictureId) ?: return@launch
                val ms = withContext(Dispatchers.IO) { runCatching { GifTiming.durationMs(file.readBytes()) }.getOrNull() } ?: return@launch
                _state.update { it.copy(gifMs = it.gifMs + (current.pictureId to ms)) }
            }
        }
        pictures.getOrNull(index + 1)?.let { next -> viewModelScope.launch { originals.file(next.pictureId) } }
    }
}
