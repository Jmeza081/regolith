package com.regolith.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import com.regolith.R
import com.regolith.ui.theme.PillShape
import com.regolith.ui.theme.RegolithTheme
import com.regolith.ui.theme.Spacing
import com.regolith.ui.theme.TextStyles
import kotlin.math.roundToInt

/**
 * The search capsule that grows out of the nav while it is swiped: up off
 * the phone's pill, or across from the rail toward the page. [progress] is
 * how far the swipe has gone, 1 being far enough.
 *
 * - Below 1 it rises into place as it grows, narrower than it will be, fading
 *   in, and says how to go on ([prompt]).
 * - At 1 it pops (a quick swell and settle, with the nav graph's buzz), its
 *   rim lights and it says "Let go to search".
 * - Past 1 it stretches a little further with the thumb, so pulling on reads
 *   as resistance rather than a dead stop.
 * - Let go short of 1 and the nav graph springs [progress] back to 0, which
 *   sinks it back into the nav as it fades.
 *
 * Let go past 1 and it is handed to [SearchFlightCapsule], which flies it
 * into Search's field. Drawn by the nav graph beside the nav it grows from,
 * never inside it, so the pill's own frost does not clip it.
 */
@Composable
fun SearchPullCapsule(progress: Float, prompt: String, modifier: Modifier = Modifier) {
    val colors = RegolithTheme.colors
    val ready = progress >= 1f
    val pop = remember { Animatable(1f) }
    LaunchedEffect(ready) {
        if (ready) {
            pop.animateTo(1.06f, tween(90))
            pop.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium))
        }
    }
    val within = progress.coerceAtMost(1f)
    val beyond = (progress - 1f).coerceAtLeast(0f)
    Row(
        modifier
            .graphicsLayer {
                alpha = (progress * 1.6f).coerceIn(0f, 1f)
                translationY = (1f - within) * 28.dp.toPx() - beyond * 18.dp.toPx()
                scaleX = (0.86f + 0.14f * within) * pop.value
                scaleY = (0.92f + 0.08f * within) * pop.value + beyond * 0.04f
            }
            .height(CAPSULE_HEIGHT)
            .shadow(10.dp, PillShape, ambientColor = colors.ground, spotColor = colors.ground)
            .background(colors.lifted, PillShape)
            .border(1.dp, lerp(colors.liftedBorder, colors.ink, within * within), PillShape)
            .padding(horizontal = Spacing.s18)
            .testTag("search_pull_capsule"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(painterResource(R.drawable.rg_ic_search_alt), contentDescription = null, tint = colors.inkSoft, modifier = Modifier.size(18.dp))
        Text(if (ready) "Let go to search" else prompt, style = TextStyles.rowLabelMedium, color = if (ready) colors.ink else colors.body)
    }
}

/**
 * The capsule after the thumb lets go past the line: it flies from where it
 * was ([from], window pixels) into Search's field ([to]), its height and
 * corners easing to the field's and its words turning into the field's
 * placeholder, while Search fades in underneath. At [fraction] 1 it sits
 * exactly over the field, and the nav graph removes it once Search has
 * faded in, so the capsule appears to become the field.
 */
@Composable
fun SearchFlightCapsule(from: Rect, to: Rect, fraction: Float, placeholder: String) {
    val colors = RegolithTheme.colors
    val density = LocalDensity.current
    val left = lerp(from.left, to.left, fraction)
    val top = lerp(from.top, to.top, fraction)
    val width = with(density) { lerp(from.width, to.width, fraction).toDp() }
    val height = with(density) { lerp(from.height, to.height, fraction).toDp() }
    Box(
        Modifier
            .offset { IntOffset(left.roundToInt(), top.roundToInt()) }
            .size(width, height)
            .shadow(lerp(10f, 0f, fraction).dp, PillShape, ambientColor = colors.ground, spotColor = colors.ground)
            .background(lerp(colors.lifted, colors.surface, fraction), PillShape)
            .border(1.dp, lerp(colors.ink, colors.raised, fraction), PillShape)
            .padding(horizontal = lerp(Spacing.s18.value, Spacing.s12.value, fraction).dp)
            .testTag("search_flight_capsule"),
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(lerp(10f, 8f, fraction).dp)) {
            Icon(painterResource(R.drawable.rg_ic_search_alt), contentDescription = null, tint = lerp(colors.inkSoft, colors.body, fraction), modifier = Modifier.size(lerp(18f, 16f, fraction).dp))
            Box {
                Text("Let go to search", style = TextStyles.rowLabelMedium, color = colors.ink, modifier = Modifier.graphicsLayer { alpha = 1f - (fraction * 2f).coerceAtMost(1f) })
                Text(placeholder, style = TextStyles.body, color = colors.metadata, maxLines = 1, modifier = Modifier.graphicsLayer { alpha = (fraction * 2f - 1f).coerceIn(0f, 1f) })
            }
        }
    }
}

/** The capsule's height while it is swiped: a touch taller than the field it becomes. */
private val CAPSULE_HEIGHT = 52.dp

/** How far a swipe on the nav must travel to open Search: a deliberate move, well past a tap's wobble. */
val SEARCH_PULL_DISTANCE = 72.dp

/** Room Search's Cancel takes beside its field, so the capsule lands on the field and not under the word. */
val SEARCH_CANCEL_WIDTH = 58.dp

/** How long the capsule takes to fly into the field. */
const val SEARCH_FLIGHT_MS = 300
