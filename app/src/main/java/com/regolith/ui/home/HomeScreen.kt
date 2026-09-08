package com.regolith.ui.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.regolith.ui.components.DisplayText
import com.regolith.ui.theme.RegolithTheme
import com.regolith.ui.theme.Spacing
import com.regolith.ui.theme.TextStyles

/** Home tab. Phase 0 placeholder: title only, to prove the nav shell. */
@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    Column(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(Spacing.s18)
            .testTag("home_screen"),
    ) {
        DisplayText(state.title)
        Text(
            text = "Coming in a later phase.",
            style = TextStyles.body,
            color = RegolithTheme.colors.body,
            modifier = Modifier.padding(top = Spacing.s4),
        )
    }
}
