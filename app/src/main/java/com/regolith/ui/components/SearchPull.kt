package com.regolith.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.regolith.R
import com.regolith.ui.theme.PillShape
import com.regolith.ui.theme.RegolithTheme
import com.regolith.ui.theme.Spacing
import com.regolith.ui.theme.TextStyles

/**
 * The search capsule that grows out of the nav while it is swiped: up off
 * the phone's pill, or across from the rail toward the page. [progress] is
 * how far the swipe has gone, 1 being far enough: below it the capsule fades
 * and slides in and says how to go on; at it, "Let go to search". Letting go
 * there opens Search with the keyboard up (the nav graph does that, and the
 * buzz at the threshold).
 *
 * Drawn by the nav graph beside the nav it grows from, never inside it, so
 * the pill's own frost does not clip it.
 */
@Composable
fun SearchPullCapsule(progress: Float, prompt: String, modifier: Modifier = Modifier) {
    val colors = RegolithTheme.colors
    val ready = progress >= 1f
    Row(
        modifier
            .graphicsLayer {
                alpha = (progress * 1.6f).coerceIn(0f, 1f)
                translationY = (1f - progress.coerceAtMost(1f)) * 24.dp.toPx()
            }
            .height(52.dp)
            .shadow(10.dp, PillShape, ambientColor = colors.ground, spotColor = colors.ground)
            .background(colors.lifted, PillShape)
            .border(1.dp, if (ready) colors.ink else colors.liftedBorder, PillShape)
            .padding(horizontal = Spacing.s18)
            .testTag("search_pull_capsule"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(painterResource(R.drawable.rg_ic_search_alt), contentDescription = null, tint = colors.inkSoft, modifier = Modifier.size(18.dp))
        Text(if (ready) "Let go to search" else prompt, style = TextStyles.rowLabelMedium, color = if (ready) colors.ink else colors.body)
    }
}

/** How far a swipe on the nav must travel to open Search: a deliberate move, well past a tap's wobble. */
val SEARCH_PULL_DISTANCE = 72.dp
