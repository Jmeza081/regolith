package com.regolith.ui.addserver

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.regolith.data.db.ScanRunEntity
import com.regolith.data.repository.SourceRepository
import com.regolith.data.scan.ScanRepository
import com.regolith.ui.components.DisplayText
import com.regolith.ui.components.ErrorCard
import com.regolith.ui.components.Eyebrow
import com.regolith.ui.components.PrimaryButton
import com.regolith.ui.components.TertiaryButton
import com.regolith.ui.theme.RegolithTheme
import com.regolith.ui.theme.Spacing
import com.regolith.ui.theme.TextStyles
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ScanningUiState(
    val serverName: String = "",
    val filesFound: Int = 0,
    val foldersDone: Int = 0,
    val currentPath: String = "",
    val running: Boolean = true,
    val failed: String? = null,
    val loaded: Boolean = false,
)

/**
 * Starts the scan of the server's enabled shares and mirrors `scan_runs`.
 * Leaving the screen does not stop anything: the work is WorkManager's.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel(assistedFactory = ScanningViewModel.Factory::class)
class ScanningViewModel @AssistedInject constructor(
    @Assisted private val serverId: Long,
    private val sources: SourceRepository,
    private val scans: ScanRepository,
) : ViewModel() {

    @AssistedFactory
    interface Factory {
        fun create(serverId: Long): ScanningViewModel
    }

    init {
        viewModelScope.launch { scans.scanAll(serverId) }
    }

    val uiState: StateFlow<ScanningUiState> = combine(
        flowOf(serverId).flatMapLatest { id -> kotlinx.coroutines.flow.flow { emit(sources.server(id)?.name ?: "") } },
        sources.observeShares(serverId).flatMapLatest { shares ->
            val enabled = shares.filter { it.enabled }.map { it.id }
            if (enabled.isEmpty()) flowOf(emptyList()) else scans.observeLatest(enabled)
        },
    ) { name, runs ->
        val running = runs.any { it.status == ScanRunEntity.RUNNING } || runs.isEmpty()
        ScanningUiState(
            serverName = name,
            filesFound = runs.sumOf { it.filesFound },
            foldersDone = runs.sumOf { it.foldersDone },
            currentPath = runs.firstOrNull { it.status == ScanRunEntity.RUNNING }?.currentPath ?: "",
            running = running,
            failed = runs.firstOrNull { it.status == ScanRunEntity.FAILED }?.error,
            loaded = true,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ScanningUiState())
}

/**
 * Scanning (design section 03): the hero figure in Michroma at 40px, the
 * one place a number gets display type; the path being read; "Run in the
 * background". The share's size is unknown until it has been walked, so
 * the bar is indeterminate rather than a made-up percentage.
 */
@Composable
fun ScanningScreen(
    viewModel: ScanningViewModel,
    onBackground: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = RegolithTheme.colors
    Column(modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = Spacing.s18).testTag("scanning_screen")) {
        Spacer(Modifier.height(Spacing.s56))
        DisplayText("%,d".format(state.filesFound), style = TextStyles.heroFigure, modifier = Modifier.testTag("scanning_count"))
        Spacer(Modifier.height(Spacing.s4))
        Text("files found on ${state.serverName}", style = TextStyles.body, color = colors.body)
        Spacer(Modifier.height(Spacing.s30))
        if (state.running) {
            LinearProgressIndicator(color = colors.accent, trackColor = colors.hairline, modifier = Modifier.fillMaxWidth().testTag("scanning_progress"))
            Spacer(Modifier.height(Spacing.s8))
            Text("${state.foldersDone} folders read", style = TextStyles.metadata, color = colors.metadata)
            Spacer(Modifier.height(Spacing.s30))
            Eyebrow("Reading")
            Spacer(Modifier.height(Spacing.s4))
            Text("/" + state.currentPath, style = TextStyles.body, color = colors.ink, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("scanning_path"))
        } else if (state.failed != null) {
            ErrorCard(message = "The scan stopped: ${state.failed}", testTag = "scanning_error_card")
        } else if (state.loaded) {
            Text("Done. Everything the share holds is listed on this device.", style = TextStyles.body, color = colors.ink, modifier = Modifier.testTag("scanning_done"))
        }
        Spacer(Modifier.weight(1f))
        if (state.running) {
            PrimaryButton(text = "Run in the background", onClick = onBackground, testTag = "scanning_background_button", modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(Spacing.s8))
            Text("Regolith keeps the list on this device. Nothing is copied off the share.", style = TextStyles.metadata, color = colors.metadata)
        } else {
            PrimaryButton(text = "Open the library", onClick = onDone, testTag = "scanning_done_button", modifier = Modifier.fillMaxWidth())
            TertiaryButton(text = "Back to Home", onClick = onBackground, testTag = "scanning_home_button", modifier = Modifier.fillMaxWidth())
        }
        Spacer(Modifier.height(Spacing.s18))
    }
}
