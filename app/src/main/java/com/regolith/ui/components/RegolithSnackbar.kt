package com.regolith.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SnackbarVisuals
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.R as LucideR
import com.regolith.ui.theme.PillShape
import com.regolith.ui.theme.RegolithTheme
import com.regolith.ui.theme.Spacing
import com.regolith.ui.theme.TextStyles

/**
 * What a message is saying, which picks its icon and whether it is drawn
 * in the accent. Only [FAILED] earns red: it is the one kind you may need
 * to act on, and red is rationed to one thing per screen.
 */
enum class MessageKind {
    /** A fact about where things stand ("Saved on this phone"). */
    INFO,

    /** Something you asked for went through ("3 videos moved"). */
    DONE,

    /** Something you asked for did not. Stays up longer by default. */
    FAILED,
}

/**
 * A snackbar message that knows its [MessageKind]. Material's host only
 * carries text, an action and a duration, so the kind rides along here
 * rather than being guessed from the wording. Show one with [showMessage].
 */
data class RegolithMessage(
    override val message: String,
    val kind: MessageKind,
    override val actionLabel: String? = null,
    override val duration: SnackbarDuration =
        if (kind == MessageKind.FAILED) SnackbarDuration.Long else SnackbarDuration.Short,
) : SnackbarVisuals {
    override val withDismissAction: Boolean get() = false
}

/**
 * Shows a Regolith message and suspends until it is gone, like
 * `showSnackbar` (web analogy: an `await`ed toast that resolves with
 * whether its button was pressed). A failure defaults to the long
 * duration, because it is worth reading twice.
 */
suspend fun SnackbarHostState.showMessage(
    message: String,
    kind: MessageKind,
    actionLabel: String? = null,
    duration: SnackbarDuration? = null,
): SnackbarResult = showSnackbar(
    duration?.let { RegolithMessage(message, kind, actionLabel, it) } ?: RegolithMessage(message, kind, actionLabel),
)

/**
 * A snackbar for a screen with no nav pill (the player, Title Detail),
 * drawn as the same [MessageCapsule] the tab screens use. Show one with
 * `state.showMessage(text, kind)`; a plain `showSnackbar(text)` still
 * works and is drawn as [MessageKind.INFO].
 *
 * Put it at the bottom of the screen's root Box with
 * `Modifier.align(Alignment.BottomCenter)`; it pads itself clear of the
 * navigation bar.
 */
@Composable
fun RegolithSnackbarHost(state: SnackbarHostState, modifier: Modifier = Modifier) {
    // Keep the nav pill on screen for as long as there is something to read,
    // so the message never ends up hovering over the gap where the pill was.
    // Done here rather than at each call site: this is the one place in the
    // app that knows a message is up.
    val hold = LocalNavChromeHold.current
    val showing = state.currentSnackbarData != null
    DisposableEffect(showing) {
        if (showing) hold.acquire()
        onDispose { if (showing) hold.release() }
    }
    SnackbarHost(hostState = state, modifier = modifier.navigationBarsPadding().padding(Spacing.s18)) { data ->
        MessageCapsule(data, testTag = "snackbar")
    }
}

/**
 * The one message surface for every tab screen, riding directly above the
 * nav pill so the two come and go together.
 *
 * **Why it lives in the chrome.** A message and the pill were two floating
 * things a few dp apart, on separate clocks, each unaware of the other — so
 * the pill could slide out from under a message that was still up. Docked,
 * they are one group: the chrome is either there or not, and the message
 * is part of it.
 *
 * The nav graph draws this once. Screens push into it through
 * [LocalAppSnackbar]; nothing else should place one.
 */
@Composable
fun ChromeMessageHost(state: SnackbarHostState, modifier: Modifier = Modifier) {
    SnackbarHost(hostState = state, modifier = modifier) { data ->
        MessageCapsule(data, testTag = "chrome_message")
    }
}

/**
 * One message, drawn as a capsule only as wide as what it says, centred in
 * the space it is given (design: "A · Capsule").
 *
 * - **Ring.** The time left burns down as a ring around the kind's icon,
 *   drawn from the duration the caller asked for, so an Undo is a decision
 *   rather than a race. An indefinite message shows a full ring.
 * - **Shape.** A pill while it is one line; a message that wraps rounds
 *   off at 24dp instead, because a 50% radius on a tall box is a lozenge.
 * - **Colour.** A lifted solid surface, not the pill's glass: the message is
 *   the newest thing on screen and should sit in front of the picture, not
 *   blend into it. A failure's ring, icon and hairline take the accent.
 *
 * Tags: [testTag] on the capsule, `<testTag>_action` on its button.
 */
@Composable
private fun MessageCapsule(data: SnackbarData, testTag: String) {
    val colors = RegolithTheme.colors
    val visuals = data.visuals
    val kind = (visuals as? RegolithMessage)?.kind ?: MessageKind.INFO
    val failed = kind == MessageKind.FAILED
    val total = when (visuals.duration) {
        SnackbarDuration.Short -> 4_000
        SnackbarDuration.Long -> 10_000
        SnackbarDuration.Indefinite -> 0
    }
    // Keyed on the data: a second message restarts its own ring rather than
    // inheriting what was left of the first one's.
    val fuse = remember(data) { Animatable(1f) }
    LaunchedEffect(data) {
        if (total > 0) fuse.animateTo(0f, tween(total, easing = LinearEasing))
    }
    var lines by remember(data) { mutableIntStateOf(1) }
    val shape = if (lines > 1) WrappedCapsuleShape else PillShape
    val accentInk = if (failed) colors.accent else colors.ink

    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier
                .shadow(10.dp, shape, ambientColor = colors.ground, spotColor = colors.ground)
                .background(colors.lifted, shape)
                .border(1.dp, if (failed) colors.accent.copy(alpha = 0.55f) else colors.liftedBorder, shape)
                .defaultMinSize(minHeight = 48.dp)
                .padding(start = 7.dp, top = 6.dp, bottom = 6.dp, end = if (visuals.actionLabel != null) 6.dp else Spacing.s18)
                // Material's own snackbar announces itself; a custom one has
                // to ask, or TalkBack users never hear it arrive.
                .semantics { liveRegion = if (failed) LiveRegionMode.Assertive else LiveRegionMode.Polite }
                .testTag(testTag),
        ) {
            Box(Modifier.size(34.dp), contentAlignment = Alignment.Center) {
                val ring = if (failed) colors.accent else colors.ink.copy(alpha = 0.75f)
                val track = colors.ink.copy(alpha = 0.10f)
                Canvas(Modifier.size(34.dp)) {
                    val stroke = 2.dp.toPx()
                    val inset = stroke / 2
                    val arcSize = Size(size.width - stroke, size.height - stroke)
                    drawArc(track, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
                    val sweep = if (total > 0) 360f * fuse.value else 360f
                    drawArc(ring, -90f, sweep, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
                }
                Icon(
                    painterResource(kind.icon()),
                    contentDescription = null,
                    tint = accentInk,
                    modifier = Modifier.size(16.dp),
                )
            }
            Text(
                visuals.message,
                style = TextStyles.message,
                color = colors.ink,
                onTextLayout = { lines = it.lineCount },
                modifier = Modifier.weight(1f, fill = false).padding(vertical = Spacing.s4),
            )
            // An action is rare and always one step (Undo a move, Retry).
            // The compact frosted pill, so it reads as a button and not as
            // part of the sentence.
            visuals.actionLabel?.let { label ->
                SecondaryButton(
                    text = label,
                    onClick = { data.performAction() },
                    testTag = "${testTag}_action",
                    compact = true,
                )
            }
        }
    }
}

private fun MessageKind.icon(): Int = when (this) {
    MessageKind.INFO -> LucideR.drawable.lucide_ic_info
    MessageKind.DONE -> LucideR.drawable.lucide_ic_check
    MessageKind.FAILED -> LucideR.drawable.lucide_ic_circle_alert
}

/** A capsule whose message wrapped: rounded, no longer a full pill. */
private val WrappedCapsuleShape = RoundedCornerShape(24.dp)

/** A chrome tier's corners: the pill's family, softened for a rectangle. Used by `BackgroundWorkTier`. */
val ChromeTierShape = RoundedCornerShape(26.dp)

/**
 * The message channel every tab screen shares, so they all land in the one
 * place the chrome draws. Screens read it instead of remembering a host of
 * their own; the player keeps its own, because it has no pill to dock to.
 */
val LocalAppSnackbar = staticCompositionLocalOf { SnackbarHostState() }
