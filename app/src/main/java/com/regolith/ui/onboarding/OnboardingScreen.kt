package com.regolith.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.regolith.ui.components.DisplayText
import com.regolith.ui.components.Eyebrow
import com.regolith.ui.components.PrimaryButton
import com.regolith.ui.theme.RegolithTheme
import com.regolith.ui.theme.Spacing
import com.regolith.ui.theme.TextStyles

/**
 * Onboarding (design section 02). Phase 0 ships only the last screen's shape
 * so the gate works end to end; the three-page pager and lunar photography
 * arrive in Phase 6.
 */
@Composable
fun OnboardingScreen(
    onFinish: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(Spacing.s18)
            .testTag("onboarding_screen"),
        verticalArrangement = Arrangement.Bottom,
    ) {
        Eyebrow("Off the network")
        Spacer(Modifier.height(Spacing.s8))
        DisplayText("Take files\nwith you")
        Spacer(Modifier.height(Spacing.s12))
        Text(
            text = "Keep anything on the phone and it plays with the share unreachable. " +
                "On a plane, on a train, anywhere the network is not.",
            style = TextStyles.body,
            color = RegolithTheme.colors.body,
        )
        Spacer(Modifier.height(Spacing.s30))
        PrimaryButton(
            text = "Find my server",
            onClick = onFinish,
            testTag = "onboarding_finish_button",
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
