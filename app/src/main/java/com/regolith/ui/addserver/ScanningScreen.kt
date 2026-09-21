package com.regolith.ui.addserver

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.width
import androidx.compose.ui.unit.em
import com.regolith.ui.components.Eyebrow
import com.regolith.ui.components.ProgressBar
import com.regolith.ui.components.SecondaryButton
import com.regolith.ui.components.SweepBar
import com.regolith.ui.theme.PillShape
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
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
import com.regolith.ui.components.PrimaryButton
import com.regolith.ui.components.TertiaryButton
import com.regolith.ui.theme.RegolithTheme
import com.regolith.ui.theme.Spacing
import com.regolith.ui.theme.TextStyles
import com.regolith.ui.theme.designSp
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
 * Scanning (design section 03): the hero figure in Michroma 40 with
 * `-.02em` tracking, the one place a number gets display type; a 6dp red
 * bar; READING with the path at 500 13/19; "Run in the background" as a
 * frosted secondary with the promise underneath. The share's size is
 * unknown until it has been walked, so the bar sweeps rather than
 * reporting a made-up percentage, and the two 12px lines carry the live
 * folder and file counts.
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
    // The scan runs as a foreground job with a notification; Android 13+ only shows it once the
    // user allows notifications. Ask here, where the reason is on screen, and carry on either way.
    val context = androidx.compose.ui.platform.LocalContext.current
    val askNotifications = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) {}
    androidx.compose.runtime.LaunchedEffect(Unit) {
        if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            askNotifications.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    Column(
        modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(start = Spacing.s18, end = Spacing.s18, top = Spacing.s30).testTag("scanning_screen"),
        verticalArrangement = Arrangement.spacedBy(Spacing.s30),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.s8)) {
            DisplayText("%,d".format(state.filesFound), style = TextStyles.heroFigure.copy(lineHeight = 44.designSp(), letterSpacing = (-0.02).em), modifier = Modifier.testTag("scanning_count"))
            Text("files found on ${state.serverName}", style = TextStyles.body.copy(lineHeight = 20.designSp()), color = colors.metadata)
        }
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.s8)) {
            if (state.running) SweepBar(modifier = Modifier.testTag("scanning_progress")) else ProgressBar(fraction = if (state.failed == null) 1f else 0f)
            Row(Modifier.fillMaxWidth()) {
                Text("${state.foldersDone} folder" + (if (state.foldersDone == 1) "" else "s") + " read", style = TextStyles.meta12.copy(fontWeight = FontWeight.Medium), color = colors.metadata)
                Spacer(Modifier.weight(1f))
                Text(
                    when {
                        state.running -> "still reading"
                        state.failed != null -> "stopped"
                        else -> "done"
                    },
                    style = TextStyles.meta12.copy(fontWeight = FontWeight.Medium), color = colors.metadata,
                )
            }
        }
        if (state.running) {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.s8)) {
                Eyebrow("Reading", muted = true)
                Text("/" + state.currentPath, style = TextStyles.notice, color = colors.inkSoft, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("scanning_path"))
            }
        } else if (state.failed != null) {
            ErrorCard(message = "The scan stopped: ${state.failed}", testTag = "scanning_error_card")
        } else if (state.loaded) {
            Text("Done. Everything the share holds is listed on this device.", style = TextStyles.body, color = colors.inkSoft, modifier = Modifier.testTag("scanning_done"))
        }
        Spacer(Modifier.weight(1f))
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.s12)) {
            if (state.running) {
                SecondaryButton(text = "Run in the background", onClick = onBackground, testTag = "scanning_background_button", modifier = Modifier.fillMaxWidth())
                Text(
                    "Regolith keeps the list on this device. Nothing is copied off the share.",
                    style = TextStyles.settingMeta.copy(lineHeight = 17.designSp()), color = colors.metadata, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
                )
            } else {
                PrimaryButton(text = "Open the library", onClick = onDone, testTag = "scanning_done_button", modifier = Modifier.fillMaxWidth())
                TertiaryButton(text = "Back to Home", onClick = onBackground, testTag = "scanning_home_button", modifier = Modifier.fillMaxWidth())
            }
        }
        Spacer(Modifier.height(Spacing.s8))
    }
}
