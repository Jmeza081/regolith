package com.regolith.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.material3.pulltorefresh.pullToRefresh
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.regolith.R
import com.regolith.ui.theme.PillShape
import com.regolith.ui.theme.RegolithTheme
import com.regolith.ui.theme.Spacing
import com.regolith.ui.theme.TextStyles
import com.regolith.ui.theme.scaledDp

/**
 * A page's pull to refresh as the design draws it, for Home and the
 * Library alike: a deliberate pull ([PULL_THRESHOLD]), the page following
 * the finger at [PULL_FOLLOW] of the way and springing back, and the
 * [RefreshLine] waiting above it, which carries one line of live status
 * once released.
 *
 * Three pieces, so each page keeps its own layout. [rememberPagePull] holds
 * the state. [pagePull] goes on the page's outermost box, where it hears
 * the overscroll of any list inside it. [RefreshLine] is drawn behind the
 * page, and the page is moved down by [offset] to uncover it.
 *
 * Web analogy: a mobile browser's pull-to-reload, made of a touch handler
 * and a CSS transform on the page.
 */
@Immutable
class PagePull @OptIn(ExperimentalMaterial3Api::class) internal constructor(
    internal val state: PullToRefreshState,
    /**
     * How far down to draw the page, in pixels. Read it inside a
     * `graphicsLayer`, so a pull moves the page without laying it out again.
     */
    val offset: Float,
) {
    /** How far the pull has gone: 1 is far enough to refresh on release. */
    @OptIn(ExperimentalMaterial3Api::class)
    val fraction: Float get() = state.distanceFraction
}

/**
 * A page's pull. While [refreshing], the page settles back to where it was
 * rather than staying where the finger let go.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun rememberPagePull(refreshing: Boolean): PagePull {
    val state = rememberPullToRefreshState()
    val thresholdPx = with(LocalDensity.current) { PULL_THRESHOLD.toPx() }
    val drag = state.distanceFraction.coerceAtMost(MAX_PULL) * thresholdPx * PULL_FOLLOW
    val settling by animateFloatAsState(if (refreshing) 0f else drag, label = "pullSettle")
    return PagePull(state, if (refreshing) settling else drag)
}

/**
 * Pull to refresh on this page, at the design's threshold: [onRefresh] runs
 * when a pull is let go past it. On the page's outermost box, so the lists
 * inside it pass it their overscroll.
 */
@OptIn(ExperimentalMaterial3Api::class)
fun Modifier.pagePull(pull: PagePull, refreshing: Boolean, onRefresh: () -> Unit, enabled: Boolean = true): Modifier =
    pullToRefresh(isRefreshing = refreshing, state = pull.state, enabled = enabled, threshold = PULL_THRESHOLD, onRefresh = onRefresh)

/**
 * The refresh line (design: "Pull to refresh" / "Refreshing"): a 30dp
 * ring with a 22% hairline holding the arrow, and a tracked 10px label.
 * Released, the arrow becomes one line of live status, [status]. Nothing
 * at all until the pull has begun or a refresh is running.
 */
@Composable
fun RefreshLine(pull: PagePull, refreshing: Boolean, status: String?, modifier: Modifier = Modifier, testTag: String = "refresh_line") {
    val colors = RegolithTheme.colors
    val fraction = pull.fraction
    val visible = refreshing || fraction > 0.05f
    if (!visible) return
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.s8),
        modifier = modifier.padding(top = Spacing.s8).alpha(if (refreshing) 1f else fraction.coerceIn(0f, 1f)).testTag(testTag),
    ) {
        Box(Modifier.size(30.scaledDp()).border(1.dp, colors.onMediaBorder, PillShape), contentAlignment = Alignment.Center) {
            Icon(painterResource(if (refreshing) R.drawable.rg_ic_collapse else R.drawable.rg_ic_arrow_up), contentDescription = null, tint = colors.ink, modifier = Modifier.size(15.scaledDp()))
        }
        Text(
            (status ?: if (fraction >= 1f) "Release to refresh" else "Pull to refresh").uppercase(),
            style = TextStyles.refreshLabel, color = colors.navIdle,
        )
    }
}

/** The refresh line's status while a share is read: how many files have turned up so far. */
fun readingTheShare(filesSoFar: Int): String = "Reading the share · ${"%,d".format(filesSoFar)} files so far"

/** How far the finger travels before a release refreshes. Material's default is 80dp. */
private val PULL_THRESHOLD = 130.dp

/** Content moves this fraction of the pull, so the page trails the finger. */
private const val PULL_FOLLOW = 0.6f

/** Past this much of the threshold the page stops following: the rubber band is spent. */
private const val MAX_PULL = 1.6f
