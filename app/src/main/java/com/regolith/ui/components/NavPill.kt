package com.regolith.ui.components

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import kotlin.math.roundToInt
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.Animatable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import com.regolith.domain.connection.ShareLink
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.regolith.R
import com.regolith.ui.navigation.MainTab
import com.regolith.ui.theme.PillShape
import com.regolith.ui.theme.RegolithTheme
import com.regolith.ui.theme.Spacing
import com.regolith.ui.theme.TextStyles
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import com.regolith.ui.theme.scaledDp

/**
 * The floating frosted nav pill (design: every tab frame). 62dp tall,
 * 18dp from each side and 26dp from the bottom, `rgba(0,0,0,.52)` under
 * a 20dp blur with a `rgba(255,255,255,.16)` hairline and a soft drop
 * shadow. Five equal cells: a 19dp glyph over a 10px tracked uppercase
 * label, white when selected, #8A8A8A otherwise, and 22% white when
 * [dimmed] (a tab with nothing behind it yet).
 *
 * [vertical] turns the same pill on its side for a wide window (a
 * foldable's inner display, a tablet): a [NAV_RAIL_WIDTH] column of the
 * same five cells hugging the start edge, where the thumbs rest on a
 * book-sized device. Same blur, same tokens, same test tags; only the
 * axis changes, which is why it is a flag and not a second composable.
 *
 * Drawn exactly once, by the nav graph, over the NavDisplay. This is the
 * one blurred material in the app — see [navChromeFrost], which the pill,
 * the cancel circle and the tiers above it all share.
 */
@Composable
fun NavPill(
    /**
     * The tab you are on, or null on a screen that is no tab: Search, where
     * the pill only ever appears as a selection's toolbar.
     */
    selected: MainTab?,
    onSelect: (MainTab) -> Unit,
    hazeState: HazeState,
    modifier: Modifier = Modifier,
    dimmed: Set<MainTab> = emptySet(),
    /**
     * Tabs carrying a notification dot — today, Settings while downloads are
     * live or a failure is unacknowledged. A fourth channel beside the
     * cell's three inks, because "there is something here" is a different
     * statement from "you are here" and "there is nothing behind this".
     */
    dots: Set<MainTab> = emptySet(),
    vertical: Boolean = false,
    /**
     * Non-null while something is selected: the pill stops being a nav and
     * becomes that selection's toolbar, in both shapes, the pill on a phone
     * and the rail on a wide window.
     */
    selection: SelectionChromeState? = null,
    /**
     * The share's connection, drawn as a dot in the upper-right corner of the
     * tab you are on ([ShareStatusDot]): green while it answers, a grey ring
     * when it cannot be reached. Null draws nothing (Settings › Display ›
     * Share connection is off, or there is no share on the network).
     */
    connection: ShareLink? = null,
    /** A long press on the tab you are on: the connection sheet. Null leaves the hold doing nothing. */
    onHoldSelected: (() -> Unit)? = null,
    /**
     * The swipe that opens Search: up off the phone's pill, across from the
     * rail toward the page. Reported as it goes, as a fraction of
     * [SEARCH_PULL_DISTANCE] (1 is far enough), then [onSearchRelease] when
     * the finger lifts; the nav graph draws the capsule and decides. Null
     * turns the swipe off (while selecting, the pill is a toolbar).
     */
    onSearchPull: ((Float) -> Unit)? = null,
    onSearchRelease: () -> Unit = {},
    /**
     * How far that swipe has gone, read as the pill draws (1 is far enough).
     * On a phone the pill BECOMES the search bar, the owner's pick of six
     * variants ("Pull to Search"): the tabs fold away one after another, the
     * pill rises with the thumb and lights its rim, and its search label
     * fades in, popping at the line. The rail keeps the capsule beside it.
     */
    searchProgress: () -> Float = { 0f },
    /** The pill is flying into Search's field as an overlay ([SearchFlightCapsule]): this one steps aside. */
    searchFlying: Boolean = false,
    /** Where the pill sits on screen, in window pixels: where the flight starts. */
    onPillBounds: (Rect) -> Unit = {},
) {
    val colors = RegolithTheme.colors
    val frosted = Modifier.navChromeFrost(hazeState, PillShape)
    // The swipe for Search. A drag is only claimed past the touch slop, so a
    // tap on a tab is still a tap; the travel is measured toward Search (up,
    // or across toward the page), and a drag the other way reads as none.
    val distance = with(LocalDensity.current) { SEARCH_PULL_DISTANCE.toPx() }
    val searchSwipe = if (onSearchPull == null || selection != null) Modifier else Modifier.pointerInput(vertical, onSearchPull) {
        var travel = 0f
        val report = { onSearchPull((travel / distance).coerceIn(0f, 1.5f)) }
        if (vertical) {
            detectHorizontalDragGestures(
                onDragStart = { travel = 0f },
                onHorizontalDrag = { change, dx -> travel = (travel + dx).coerceAtLeast(0f); change.consume(); report() },
                onDragEnd = { onSearchRelease() },
                onDragCancel = { travel = 0f; report() },
            )
        } else {
            detectVerticalDragGestures(
                onDragStart = { travel = 0f },
                onVerticalDrag = { change, dy -> travel = (travel - dy).coerceAtLeast(0f); change.consume(); report() },
                onDragEnd = { onSearchRelease() },
                onDragCancel = { travel = 0f; report() },
            )
        }
    }
    // The pill becoming the bar (phone only): its pop at the line.
    val ready by remember { derivedStateOf { searchProgress() >= 1f } }
    val pop = remember { Animatable(1f) }
    LaunchedEffect(ready) {
        if (ready && !vertical) {
            pop.animateTo(1.05f, tween(90))
            pop.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium))
        }
    }
    val cell: @Composable (MainTab, Modifier) -> Unit = { tab, cellModifier ->
        val here = tab == selected
        NavCell(
            tab, selected = here, dimmed = tab in dimmed, dot = tab in dots, onSelect = onSelect, modifier = cellModifier,
            connection = connection.takeIf { here },
            onHold = onHoldSelected.takeIf { here },
        )
    }
    if (vertical) {
        // The rail: one cell high per tab, 10dp of inner padding top and
        // bottom so the end labels clear the 50% radius, as on the pill.
        Column(
            modifier = modifier
                .width(NAV_RAIL_WIDTH)
                .then(searchSwipe)
                .then(frosted)
                .padding(vertical = 10.dp)
                .testTag("nav_pill"),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // The rail morphs exactly as the pill does — same cross-fade, same
            // argument. It ends up one cell shorter while selecting (four verbs
            // against five tabs) and simply settles there: the rail is centred
            // on its edge, so it closes evenly rather than jumping.
            Crossfade(targetState = selection, animationSpec = tween(MODE_MS), label = "navRailMode") { mode ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (mode == null) {
                        MainTab.entries.forEach { tab -> cell(tab, Modifier.fillMaxWidth().height(NAV_RAIL_CELL_HEIGHT)) }
                    } else {
                        mode.verbs.forEach { verb ->
                            PillCell(
                                icon = verb.icon,
                                label = verb.label,
                                tint = if (verb.destructive) colors.accent else colors.inkSoft,
                                enabled = verb.enabled,
                                onClick = verb.onClick,
                                testTag = verb.testTag,
                                modifier = Modifier.fillMaxWidth().height(NAV_RAIL_CELL_HEIGHT),
                            )
                        }
                    }
                }
            }
        }
        return
    }
    Row(
        // The swipe lives on this row, which stays put: the pill inside it
        // rises with the thumb, and a moving target would read the finger as
        // hardly moving at all.
        modifier = modifier.then(searchSwipe).padding(horizontal = Spacing.s18),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selection != null) {
            CancelCircle(onClick = selection.onCancel, hazeState = hazeState)
            Spacer(Modifier.width(Spacing.s8))
        }
        InnerPill(
            modifier = Modifier
            // Capped rather than full-width. The pill was 89% of a 320px
            // frame and 91% of the phone -- proportionally right, but four
            // weighted slots across 375dp put 94dp around labels that need
            // ~67dp, and the gaps read as slack. This is a judgement call
            // against the frame, not a correction of it.
                .weight(1f)
                .widthIn(max = NAV_PILL_MAX_WIDTH)
                .height(NAV_PILL_HEIGHT)
                // Rises with the swipe. An offset, not a layer translation:
                // the frost samples what is behind the pill's placed bounds.
                .offset {
                    val p = searchProgress()
                    IntOffset(0, -(PILL_RISE.toPx() * p.coerceIn(0f, 1f) + PILL_OVERPULL.toPx() * (p - 1f).coerceAtLeast(0f)).roundToInt())
                }
                .graphicsLayer {
                    scaleX = pop.value; scaleY = pop.value
                    alpha = if (searchFlying) 0f else 1f
                }
                .onGloballyPositioned { onPillBounds(it.boundsInWindow()) }
                .then(frosted)
                // Its rim lights as it becomes the bar.
                .drawWithContent {
                    drawContent()
                    val k = searchProgress().coerceIn(0f, 1f)
                    if (k > 0f) {
                        val w = 1.dp.toPx()
                        drawRoundRect(
                            color = colors.ink.copy(alpha = .85f * k * k),
                            topLeft = androidx.compose.ui.geometry.Offset(w / 2, w / 2),
                            size = androidx.compose.ui.geometry.Size(size.width - w, size.height - w),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2),
                            style = Stroke(w),
                        )
                    }
                }
                // 10dp, not 4: the end labels sit against a 50% radius, and
                // a 4dp inset put SETTINGS right on the curve once the pill
                // stopped stretching.
                .padding(horizontal = 10.dp)
                .testTag("nav_pill"),
        ) {
        // Tabs and verbs are siblings replacing siblings, so they CROSS-FADE
        // rather than slide — the same argument Transitions.kt makes for the
        // tab switch itself, at the same 140ms. Nothing moves and nothing
        // scales, so the pill stays exactly where the thumb left it.
        Box(Modifier.weight(1f).fillMaxHeight()) {
        Crossfade(targetState = selection, animationSpec = tween(MODE_MS), label = "navPillMode") { mode ->
            Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                if (mode == null) {
                    MainTab.entries.forEachIndexed { index, tab ->
                        cell(
                            tab,
                            Modifier.weight(1f).fillMaxHeight().graphicsLayer {
                                // One after another, left to right, as the swipe goes on.
                                val k = ((searchProgress().coerceIn(0f, 1f) - index * TAB_FOLD_STAGGER) / TAB_FOLD_SPAN).coerceIn(0f, 1f)
                                alpha = 1f - k
                                translationY = k * 10.dp.toPx()
                                scaleX = 1f - .5f * k
                                scaleY = 1f - .5f * k
                            },
                        )
                    }
                } else {
                    // Cancel is NOT a cell: it is the circle beside the pill.
                    // Five labels in five cells left "Download" wrapping, and
                    // a way out is a different kind of thing from a verb
                    // anyway — so it gets its own object and the verbs get
                    // the whole pill.
                    mode.verbs.forEach { verb ->
                        PillCell(
                            icon = verb.icon,
                            label = verb.label,
                            tint = if (verb.destructive) colors.accent else colors.inkSoft,
                            enabled = verb.enabled,
                            onClick = verb.onClick,
                            testTag = verb.testTag,
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                        )
                    }
                }
            }
        }
        // The bar it becomes: the search label, in as the tabs go out.
        if (selection == null) {
            Row(
                Modifier.fillMaxSize().padding(start = Spacing.s8).graphicsLayer {
                    alpha = ((searchProgress().coerceIn(0f, 1f) - .45f) / .4f).coerceIn(0f, 1f)
                },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(painterResource(R.drawable.rg_ic_search_alt), contentDescription = null, tint = colors.inkSoft, modifier = Modifier.size(18.dp))
                Text(if (ready) "Let go to search" else "Swipe up to search", style = TextStyles.rowLabelMedium, color = if (ready) colors.ink else colors.body)
            }
        }
        }
    }
}
}

/** The pill proper: its own Row so the cancel circle can sit outside the frost. */
@Composable
private fun InnerPill(modifier: Modifier, content: @Composable RowScope.() -> Unit) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically, content = content)
}

/**
 * The way out of a selection: a 62dp circle in the chrome's own material,
 * sitting just outside the pill. Same height as the pill, so the two read as
 * one object with a gap rather than two unrelated controls.
 */
@Composable
private fun CancelCircle(onClick: () -> Unit, hazeState: HazeState) {
    val colors = RegolithTheme.colors
    Box(
        modifier = Modifier
            .size(62.dp)
            .navChromeFrost(hazeState, PillShape)
            .clickable(interactionSource = null, indication = null, role = Role.Button, onClick = onClick)
            .testTag("nav_selection_cancel"),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(R.drawable.rg_ic_close),
            contentDescription = "Cancel selection",
            tint = colors.inkSoft,
            modifier = Modifier.size(20.scaledDp()),
        )
    }
}

/**
 * The app's one blurred material: a 20dp haze over the ground, tinted with
 * [RegolithColors.pillBg], a hairline and a soft drop shadow.
 *
 * Named once and shared, because the nav chrome is several objects — the
 * pill, the cancel circle beside it and the selection and background-work
 * tiers above it — and they have to be the SAME glass. (Messages are the
 * exception on purpose: see `MessageCapsule`.) The poster editor's panels use it too, over a
 * moving picture, which is the other place text must stay readable on glass. A tier that was merely translucent read as a
 * different surface docked to the pill rather than part of it.
 */
@Composable
fun Modifier.navChromeFrost(hazeState: HazeState, shape: Shape): Modifier {
    val colors = RegolithTheme.colors
    val style = HazeStyle(
        backgroundColor = colors.ground,
        tints = listOf(HazeTint(colors.pillBg)),
        blurRadius = 20.dp,
        noiseFactor = 0f,
    )
    return this
        .shadow(elevation = 12.dp, shape = shape, ambientColor = colors.ground, spotColor = colors.ground)
        .clip(shape)
        .hazeEffect(state = hazeState, style = style)
        .background(colors.pillBg)
        .border(1.dp, colors.pillBorder, shape)
}

/**
 * One verb in the pill, built to the same anatomy as [NavCell]: a 19dp glyph
 * over a 10px tracked label. Same cell, different job — which is the whole
 * point of the morph, and why five verbs fit where five tabs did.
 */
@Composable
private fun PillCell(
    icon: Int,
    label: String,
    tint: Color,
    enabled: Boolean,
    onClick: () -> Unit,
    testTag: String,
    modifier: Modifier,
) {
    val colors = RegolithTheme.colors
    val ink = if (enabled) tint else colors.disabledInk
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = modifier
            .clickable(interactionSource = null, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .testTag(testTag),
    ) {
        Icon(painter = painterResource(icon), contentDescription = label, tint = ink, modifier = Modifier.size(19.scaledDp()))
        Spacer(Modifier.height(Spacing.s4))
        // A point smaller than a tab's label: see TextStyles.navLabelSelection.
        Text(label.uppercase(), style = TextStyles.navLabelSelection, color = ink, maxLines = 1)
    }
}

/** The tab cross-fade's own duration (Transitions.kt TAB_MS), because this is that same swap. */
private const val MODE_MS = 140

/**
 * The rail retracted (F6): a [NAV_RAIL_SPINE_WIDTH] column of one dot per
 * tab on the start edge, coloured exactly as the rail's labels are — white
 * for the tab you are on, #8A8A8A otherwise, 22% white when dimmed.
 * Tapping anywhere on it brings the rail back.
 *
 * It is deliberately the *same* nav in a smaller form rather than a generic
 * "menu" button: the lit dot still answers "where am I", which a hamburger
 * would not. Same frosted treatment as the pill, so the app still has only
 * one blurred surface.
 *
 * WHAT YOU TAP IS NOT WHAT YOU SEE. The dots stay 14dp wide, but they sit
 * inside an invisible [NAV_RAIL_SPINE_TOUCH_WIDTH] x
 * [NAV_RAIL_SPINE_TOUCH_HEIGHT] target that starts at the screen edge, and
 * that target — not the dots — carries the click and the test tag. 14dp is
 * under a third of the 48dp Android asks of a touch target, and on a
 * foldable's inner display it is about three millimetres of glass.
 *
 * It also calls [systemGestureExclusion]. Android reserves a strip down each
 * edge for the back gesture and the SYSTEM WINS TIES, so a thumb aimed at a
 * spine sitting 8dp from the edge was being read as "go back" instead. This
 * modifier is the app asking for that rectangle back. Android grants each
 * side a budget (200dp total, most recent callers first) and silently
 * ignores anything past it; this is the app's only caller, so it always
 * fits. Web analogy: `touch-action` on an element that overlaps a browser's
 * own edge-swipe.
 */
@Composable
fun NavRailSpine(
    /** As on the pill: null on a screen that is no tab. */
    selected: MainTab?,
    onExpand: () -> Unit,
    hazeState: HazeState,
    modifier: Modifier = Modifier,
    dimmed: Set<MainTab> = emptySet(),
    /** As on the pill. A badged dot goes accent and grows, since 4dp has no room for a second mark. */
    dots: Set<MainTab> = emptySet(),
) {
    val colors = RegolithTheme.colors
    val style = HazeStyle(
        backgroundColor = colors.ground,
        tints = listOf(HazeTint(colors.pillBg)),
        blurRadius = 20.dp,
        noiseFactor = 0f,
    )
    Box(
        modifier = modifier
            .width(NAV_RAIL_SPINE_TOUCH_WIDTH)
            .heightIn(min = NAV_RAIL_SPINE_TOUCH_HEIGHT)
            .systemGestureExclusion()
            .clickable(interactionSource = null, indication = null, onClick = onExpand)
            .testTag("nav_rail_spine"),
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.CenterStart)
                // The gutter the spine floats in, moved INSIDE the target so the
                // target itself begins at x=0. The edge is where a thumb sliding
                // in from off-screen arrives, and it was the one part of the
                // reach that the app was not listening to at all.
                .padding(start = Spacing.s8)
                .width(NAV_RAIL_SPINE_WIDTH)
                .shadow(elevation = 12.dp, shape = PillShape, ambientColor = colors.ground, spotColor = colors.ground)
                .clip(PillShape)
                .hazeEffect(state = hazeState, style = style)
                .background(colors.pillBg)
                .border(1.dp, colors.pillBorder, PillShape)
                .padding(vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.s8),
        ) {
            MainTab.entries.forEach { tab ->
                val badged = tab in dots
                val ink = when {
                    badged -> colors.accent
                    tab == selected -> colors.ink
                    tab in dimmed -> colors.navDimmed
                    else -> colors.navIdle
                }
                Box(
                    Modifier
                        .size(if (badged) 6.dp else 4.dp)
                        .background(ink, PillShape)
                        .then(if (badged) Modifier.testTag("${tab.testTag}_dot") else Modifier),
                )
            }
        }
    }
}

/** One tab cell: glyph over label, the whole cell tappable. Shared by the pill and the rail. */
@Composable
private fun NavCell(
    tab: MainTab,
    selected: Boolean,
    dimmed: Boolean,
    dot: Boolean,
    onSelect: (MainTab) -> Unit,
    modifier: Modifier,
    connection: ShareLink? = null,
    onHold: (() -> Unit)? = null,
) {
    val colors = RegolithTheme.colors
    val haptics = LocalHapticFeedback.current
    val ink = when {
        selected -> colors.ink
        dimmed -> colors.navDimmed
        else -> colors.navIdle
    }
    val interaction = remember { MutableInteractionSource() }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = modifier
            .combinedClickable(
                interactionSource = interaction, indication = null, role = Role.Tab,
                onClick = { onSelect(tab) },
                // Holding the tab you are on opens the connection sheet. A
                // buzz says the hold took, since nothing on screen says a
                // hold does anything.
                onLongClick = onHold?.let { hold -> { haptics.performHapticFeedback(HapticFeedbackType.LongPress); hold() } },
                onLongClickLabel = onHold?.let { "Connection details" },
            )
            .semantics {
                this.selected = selected
                connection?.let { stateDescription = it.spoken() }
            }
            .testTag(tab.testTag),
    ) {
        Box {
            Icon(painter = painterResource(tab.icon), contentDescription = tab.label, tint = ink, modifier = Modifier.size(19.scaledDp()))
            if (connection != null) {
                // The share's connection, in the corner a notification dot
                // takes. The dot's own box is 2.5× the dot for its glow, so
                // the offset puts the dot's centre where the red dot's is.
                ShareStatusDot(
                    connection,
                    size = CONNECTION_DOT,
                    modifier = Modifier.align(Alignment.TopEnd).offset(x = 9.75.dp, y = (-7.75).dp).testTag("${tab.testTag}_connection"),
                )
            }
            if (dot) {
                // Red, not white: on a nav cell white is what "you are here"
                // means, and a dot must not say that. The accent is the
                // app's one attention colour and this is attention. On the
                // tab carrying the connection dot it moves to the other
                // corner: the dot looked at all the time keeps its place.
                Box(
                    Modifier
                        .align(if (connection != null) Alignment.TopStart else Alignment.TopEnd)
                        .offset(x = if (connection != null) (-4).dp else 4.dp, y = (-2).dp)
                        .size(6.dp)
                        .background(colors.accent, PillShape)
                        .testTag("${tab.testTag}_dot"),
                )
            }
        }
        Spacer(Modifier.height(Spacing.s4))
        Text(tab.label.uppercase(), style = TextStyles.navLabel, color = ink)
    }
}

/** How far the pill rises as it becomes the bar, and how much further a pull past the line takes it. */
private val PILL_RISE: Dp = 44.dp
private val PILL_OVERPULL: Dp = 12.dp

/** The tabs fold away one after another: each starts this much later, and takes this much of the swipe. */
private const val TAB_FOLD_STAGGER = .1f
private const val TAB_FOLD_SPAN = .32f

/** The connection dot on the tab you are on: a point larger than the red badge, so the glow and the ring read. */
private val CONNECTION_DOT: Dp = 7.dp

/** What a screen reader hears on the tab you are on, after its name. */
private fun ShareLink.spoken(): String = when (this) {
    ShareLink.CONNECTED -> "Share connected"
    ShareLink.CHECKING -> "Checking the share"
    ShareLink.OUT_OF_REACH -> "Share out of reach"
}

/** The rail's width on a wide window: the widest label ("SETTINGS") plus the pill's own 10dp each side. */
val NAV_RAIL_WIDTH: Dp = 84.dp

/** One rail cell: the pill's 62dp plus the room a stacked cell needs between glyph and label. */
private val NAV_RAIL_CELL_HEIGHT: Dp = 66.dp

/**
 * What a tab screen leaves free on the start edge when the pill is a rail:
 * the s18 gutter the rail floats in, plus the rail. The screen's own s18
 * gutter then puts content 120dp in, which is where the design's inner-
 * display frames put it.
 */
val NAV_RAIL_INSET: Dp = Spacing.s18 + NAV_RAIL_WIDTH

/** The retracted rail as DRAWN: one dot per tab and the padding around them. */
val NAV_RAIL_SPINE_WIDTH: Dp = 14.dp

/**
 * The retracted rail as TAPPED: a target from the screen edge inwards, wide
 * enough to hit without aiming.
 *
 * 36dp and not 48dp because of what lies beyond it. A tab screen's content
 * starts at [NAV_RAIL_SPINE_INSET] plus its own s18 gutter — 40dp in — so
 * 36dp is every pixel available before an invisible target would start
 * swallowing taps meant for the first column of tiles. Paired with
 * [NAV_RAIL_SPINE_TOUCH_HEIGHT] the target clears Android's 48x48dp
 * minimum by area with room over, and the height is free: nothing else is
 * drawn in that gutter at any point down it.
 */
val NAV_RAIL_SPINE_TOUCH_WIDTH: Dp = 36.dp

/** As [NAV_RAIL_SPINE_TOUCH_WIDTH], down the edge: comfortably past the dots at either end. */
val NAV_RAIL_SPINE_TOUCH_HEIGHT: Dp = 120.dp

/**
 * What a tab screen leaves free when the rail is pinned away: the spine and
 * the s8 gutter it hugs. 102dp of reserved width becomes 22dp, which is the
 * 80dp that puts the Library wall's third tile back at its full width.
 *
 * This follows what is DRAWN, not what is tapped: the spine's touch target
 * is wider than the spine, and deliberately spends the screen's own gutter
 * rather than asking for more reserved width. See
 * [NAV_RAIL_SPINE_TOUCH_WIDTH] — if either number moves, check they still
 * clear each other.
 */
val NAV_RAIL_SPINE_INSET: Dp = Spacing.s8 + NAV_RAIL_SPINE_WIDTH

/**
 * Five 71dp slots plus 10dp of inner padding each side.
 *
 * Was four 81dp slots at 344dp until Shorts arrived. 375dp is the full
 * width a 411dp phone has between the s18 gutters, so the cap only bites
 * on something wider; SETTINGS, the longest label, sets at roughly 65dp and
 * keeps about 3dp either side. That is the number to watch if a sixth tab
 * is ever proposed, or the font-size setting is turned well up.
 */
private val NAV_PILL_MAX_WIDTH = 375.dp

/**
 * How far a scrolling tab screen must pad its content so the last row can
 * clear the pill: 62dp of pill, 26dp under it, 8dp over it, and the s18
 * gutter. The one number every tab screen used to hard-code.
 */
val NAV_PILL_CLEARANCE: Dp = 112.dp

/** The phone's pill itself, top to bottom: what the mini player's bar rises above while it shows. */
val NAV_PILL_HEIGHT: Dp = 62.dp

/**
 * How wide a docked message may get. On a phone it spans the gutters; on a
 * wide window the content area is far wider than a line anyone wants to read,
 * so it stops here and stays centred.
 */
val CHROME_MESSAGE_MAX_WIDTH: Dp = 520.dp

/**
 * The edge the nav pill occupies, as padding a screen adds on top of its own
 * gutters. On a phone the pill floats at the bottom, so this is
 * `PaddingValues(bottom = NAV_PILL_CLEARANCE)`; on a wide window (a
 * foldable's inner display, a tablet) the pill becomes a rail on the start
 * edge and the padding moves with it. Provided once by the nav graph; the
 * default is the phone value so previews and tests need nothing.
 *
 * Web analogy: a CSS custom property for the nav's safe area, set on the
 * root and read by every page.
 */
val LocalNavPillInsets = staticCompositionLocalOf { PaddingValues(bottom = NAV_PILL_CLEARANCE) }

/**
 * How far Title Detail pads the end of its scroll so its last rows clear the
 * mini player floating over it (a phone's bar, a wide window's card); zero
 * while there is none. The screens that pad by [LocalNavPillInsets] already
 * get the room, because those insets count the mini player too.
 */
val LocalMiniPlayerClearance = staticCompositionLocalOf { 0.dp }

/**
 * How much of the window's width the nav rail takes from the start edge on a
 * wide window, as it is right now (it animates as the rail comes and goes);
 * 0 on a phone. The tab screens are already laid out inside it. It is here
 * for a screen that sizes things against the whole window rather than its
 * own box: the Library's walls, whose posters keep their size while a
 * title's page takes half the window. Provided once by the nav graph.
 *
 * Dynamic rather than static, unlike the other chrome values: it changes on
 * every frame of the rail's slide, and only the walls read it.
 */
val LocalNavRailInset = compositionLocalOf { 0.dp }

/**
 * Whether the nav pill is showing — and therefore whether a screen's OWN
 * floating chrome should be showing with it.
 *
 * The pill leaves once there has been no touch for as long as Settings ›
 * Display › Hide after says, and returns on the next one. A screen that floats controls over its content (Shorts) has exactly
 * the same question to answer, and answering it with a second timer would
 * put two timers on one screen, drifting apart by however long each took to
 * start. This publishes the pill's own answer, so the two hide and return
 * together off one clock.
 *
 * Defaults to true: a screen composed without the nav graph above it — a
 * preview, a Compose test — should draw its chrome rather than wait for a
 * signal that never arrives.
 *
 * NOTE for QA: chrome behind this is REMOVED from the tree while hidden,
 * not merely faded, so its test tags are unfindable a few seconds after the
 * last touch. Drive it as ONE `run-sequence`: touch, await the tag, tap.
 */
val LocalNavChromeVisible = staticCompositionLocalOf { true }

/**
 * A reason to keep the nav chrome on screen that is NOT a touch.
 *
 * The pill leaves a few seconds after the last touch, and showing a message
 * is not a touch — so a snackbar (4 s by default, longer when the platform
 * extends it for accessibility) outlived the pill by a second or more and
 * ended up floating over the 112 dp that [NAV_PILL_CLEARANCE] reserves for a
 * pill that is no longer there. The two were on separate clocks.
 *
 * A hold puts them on one: while anything is held, the pill stays. It is a
 * COUNT rather than a flag because two things can hold at once (a message
 * arriving while another is still up) and the second release must not
 * cancel the first hold.
 *
 * Nothing calls this directly — [RegolithSnackbarHost] takes a hold for as
 * long as it is showing something, so every snackbar in the app gets this
 * without its own wiring.
 */
@Stable
class NavChromeHold {
    var count by mutableIntStateOf(0)
        private set

    val held: Boolean get() = count > 0

    fun acquire() {
        count++
    }

    fun release() {
        if (count > 0) count--
    }
}

/** The hold the nav graph is listening to. A screen composed without it holds nothing. */
val LocalNavChromeHold = staticCompositionLocalOf { NavChromeHold() }
