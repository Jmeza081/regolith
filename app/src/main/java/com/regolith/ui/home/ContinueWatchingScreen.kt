package com.regolith.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.regolith.ui.components.LocalNavPillInsets
import com.regolith.ui.components.CardStyle
import com.regolith.ui.components.DisplayText
import com.regolith.ui.components.ResumeCard
import com.regolith.ui.components.SurfaceCard
import com.regolith.ui.components.TopBar
import com.regolith.ui.theme.RegolithTheme
import com.regolith.ui.theme.Spacing
import com.regolith.ui.theme.TextStyles
import com.regolith.ui.util.formatRemaining

/**
 * Everything part-watched: what "All" beside Home's Continue watching row
 * opens. The same [ResumeCard] as the Home row, two across instead of one
 * scrolling line, most recently played first.
 *
 * Tapping a card resumes where it was left, exactly as on Home — this
 * screen is the row unrolled, not a different way to open a title.
 */
@Composable
fun ContinueWatchingScreen(
    viewModel: ContinueWatchingViewModel,
    onBack: () -> Unit,
    onPlay: (fileId: Long, startMs: Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = RegolithTheme.colors

    Column(modifier.fillMaxSize().testTag("continue_watching_screen")) {
        TopBar(
            title = "Continue watching",
            onBack = onBack,
            subtitle = if (state.loaded) "${state.items.size} started" else null,
            subtitleMuted = true,
        )
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.fillMaxSize().testTag("continue_watching_grid"),
            contentPadding = PaddingValues(start = Spacing.s18, end = Spacing.s18, bottom = LocalNavPillInsets.current.calculateBottomPadding()),
            horizontalArrangement = Arrangement.spacedBy(Spacing.s8),
            verticalArrangement = Arrangement.spacedBy(Spacing.s18),
        ) {
            if (state.loaded && state.items.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    SurfaceCard(style = CardStyle.Empty, modifier = Modifier.fillMaxWidth().testTag("continue_watching_empty")) {
                        DisplayText("Nothing started", style = TextStyles.dialogTitle)
                        Spacer(Modifier.height(Spacing.s8))
                        Text("Play something and it waits here at the moment you left it.", style = TextStyles.body, color = colors.body)
                    }
                }
                return@LazyVerticalGrid
            }
            items(state.items, key = { it.fileId }) { item ->
                ResumeCard(
                    artwork = item.artwork,
                    title = item.name,
                    meta = item.meta,
                    timeLeft = formatRemaining(item.positionMs, item.durationMs),
                    progress = item.fraction,
                    onClick = { onPlay(item.fileId, item.positionMs) },
                    testTag = "continue_watching_${item.fileId}",
                    modifier = Modifier.padding(bottom = Spacing.s2),
                )
            }
        }
    }
}
