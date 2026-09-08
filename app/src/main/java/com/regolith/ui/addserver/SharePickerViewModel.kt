package com.regolith.ui.addserver

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.regolith.data.repository.SourceRepository
import com.regolith.domain.model.Share
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SharePickerUiState(
    val serverName: String = "",
    val shares: List<Share> = emptyList(),
    val loaded: Boolean = false,
) {
    val selectedCount: Int get() = shares.count { it.enabled }
}

/**
 * "Choose a share" (design section 03). Toggling a share writes its
 * enabled flag straight away; "Scan N shares" only navigates on.
 *
 * The server id comes from the navigation key via assisted injection:
 * Hilt builds the ViewModel with the key's argument as a constructor
 * parameter, like a route param handed to a page component.
 */
@HiltViewModel(assistedFactory = SharePickerViewModel.Factory::class)
class SharePickerViewModel @AssistedInject constructor(
    @Assisted private val serverId: Long,
    private val sources: SourceRepository,
) : ViewModel() {

    @AssistedFactory
    interface Factory {
        fun create(serverId: Long): SharePickerViewModel
    }

    val uiState: StateFlow<SharePickerUiState> = combine(
        flow { emit(sources.server(serverId)?.name ?: "") },
        sources.observeShares(serverId),
    ) { name, shares -> SharePickerUiState(serverName = name, shares = shares, loaded = true) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SharePickerUiState())

    fun toggle(share: Share) {
        viewModelScope.launch { sources.setShareEnabled(share.id, !share.enabled) }
    }
}
