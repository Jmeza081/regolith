package com.regolith.ui.addserver

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.regolith.data.repository.SourceRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * "Name this server": the typed name, plus the two things that make the
 * step answerable — where the server actually is, and what it will be
 * called if nothing is typed.
 */
data class NameServerUiState(
    val name: String = "",
    /** "192.168.4.73", or "tower.local:1445" when the port is not the usual one. */
    val address: String = "",
    /** The name the app would pick on its own; shown as the field's placeholder. */
    val suggestion: String = "",
    val loaded: Boolean = false,
    /** Set once the name is stored; the screen navigates on and clears it. */
    val saved: Boolean = false,
)

/**
 * The naming step of Add Server (between connecting and choosing shares).
 *
 * The field starts EMPTY with the suggestion as its placeholder, rather
 * than pre-filled with it. A pre-filled field has to be cleared before it
 * can be typed in, which is work charged to the person who wanted to
 * rename; an empty one costs the person who did not want to rename
 * nothing at all — Continue on a blank field keeps the suggestion.
 */
@HiltViewModel(assistedFactory = NameServerViewModel.Factory::class)
class NameServerViewModel @AssistedInject constructor(
    @Assisted private val serverId: Long,
    private val sources: SourceRepository,
) : ViewModel() {

    @AssistedFactory
    interface Factory {
        fun create(serverId: Long): NameServerViewModel
    }

    private val _uiState = MutableStateFlow(NameServerUiState())
    val uiState: StateFlow<NameServerUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val server = sources.server(serverId) ?: return@launch
            val host = server.host
            val address = host.host + if (host.port != 445) ":${host.port}" else ""
            _uiState.update { it.copy(address = address, suggestion = server.name, loaded = true) }
        }
    }

    fun onNameChange(value: String) = _uiState.update { it.copy(name = value) }

    /** Continue: store the name (blank keeps the suggestion) and let the screen move on. */
    fun save() {
        viewModelScope.launch {
            sources.renameServer(serverId, _uiState.value.name)
            _uiState.update { it.copy(saved = true) }
        }
    }

    /** The screen calls this after navigating, so a back press does not re-navigate. */
    fun consumeNavigation() = _uiState.update { it.copy(saved = false) }
}
