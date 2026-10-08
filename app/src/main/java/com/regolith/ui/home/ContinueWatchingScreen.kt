package com.regolith.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.regolith.ui.components.LocalNavPillInsets
import com.regolith.ui.components.EmptyState
import com.regolith.ui.components.Ghost
import com.regolith.ui.components.ResumeCard
import com.regolith.ui.components.TopBar
import com.regolith.ui.theme.Spacing
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
                    EmptyState(
                        title = "Nothing started yet",
                        body = "Play something and it waits here at the moment you left it.",
                        ghost = Ghost.Frames(rows = 2, columns = 2),
                        modifier = Modifier.padding(top = Spacing.s12),
                        testTag = "continue_watching_empty",
                    )
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
                    // Its picture flies into the player it opens.
                    flight = item.artwork.owner,
                )
            }
        }
    }
}
