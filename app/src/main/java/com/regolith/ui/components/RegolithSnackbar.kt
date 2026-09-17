package com.regolith.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import dev.chrisbanes.haze.HazeState
import com.regolith.ui.theme.CardShape
import com.regolith.ui.theme.RegolithTheme
import com.regolith.ui.theme.Spacing
import com.regolith.ui.theme.TextStyles

/**
 * A line at the bottom of a screen that says what just happened — "Saved
 * to the share" — and goes away on its own. Material's host, dressed as
 * a Regolith card: surface fill, raised hairline, body type. Show one with
 * `state.showSnackbar(message)`; the default duration is short.
 *
 * Put it at the bottom of the screen's root Box with
 * `Modifier.align(Alignment.BottomCenter)`; it pads itself clear of the
 * navigation bar.
 */
@Composable
fun RegolithSnackbarHost(state: SnackbarHostState, modifier: Modifier = Modifier) {
    val colors = RegolithTheme.colors
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
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
                .background(colors.surface, CardShape).border(1.dp, colors.raised, CardShape)
                .padding(start = Spacing.s18, end = if (data.visuals.actionLabel != null) Spacing.s4 else Spacing.s18)
                .testTag("snackbar"),
        ) {
            Text(
                data.visuals.message,
                style = TextStyles.rowLabelMedium, color = colors.ink,
                modifier = Modifier.weight(1f).padding(vertical = Spacing.s12),
            )
            // An action is rare and always reversible-in-one-step (Undo on a
            // move, which is the same rename back). 44dp so it can be hit.
            data.visuals.actionLabel?.let { label ->
                Box(
                    Modifier.defaultMinSize(minHeight = 44.dp)
                        .clickable(interactionSource = null, indication = null, onClick = { data.performAction() })
                        .padding(horizontal = Spacing.s12)
                        .testTag("snackbar_action"),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(label, style = TextStyles.buttonTertiary, color = colors.ink)
                }
            }
        }
    }
}

/**
 * The one message surface for every tab screen: a tier that docks directly
 * above the nav pill, in the pill's own material, with the message's life
 * burning down as a hairline underneath it.
 *
 * **Why it lives in the chrome.** A message and the pill were two floating
 * things a few dp apart, on separate clocks, each unaware of the other — so
 * the pill could slide out from under a message that was still up. Docked,
 * there is one object with two tiers: the chrome is either there or not, and
 * the message is part of it.
 *
 * **Why the fuse.** A snackbar that disappears on its own gives no warning,
 * which is exactly the complaint people have about them. The line says how
 * long is left, so an Undo is a decision rather than a race. It is drawn from
 * the duration the caller asked for, not a guess.
 *
 * The nav graph draws this once. Screens push into it through
 * [LocalAppSnackbar]; nothing else should place one.
 */
@Composable
fun ChromeMessageHost(state: SnackbarHostState, hazeState: HazeState, modifier: Modifier = Modifier) {
    val colors = RegolithTheme.colors
    SnackbarHost(hostState = state, modifier = modifier) { data ->
        val total = when (data.visuals.duration) {
            SnackbarDuration.Short -> 4_000
            SnackbarDuration.Long -> 10_000
            SnackbarDuration.Indefinite -> 0
        }
        // Keyed on the data: a second message restarts its own fuse rather
        // than inheriting what was left of the first one's.
        val fuse = remember(data) { Animatable(1f) }
        LaunchedEffect(data) {
            if (total > 0) fuse.animateTo(0f, tween(total, easing = LinearEasing))
        }
        Box(
            // The pill's own glass, not a lookalike: same 20dp haze, same
            // tint, same hairline. A message docked to the pill in a
            // different material reads as something stuck onto it, and text
            // over unblurred artwork is the readability problem this fixes.
            Modifier.fillMaxWidth()
                .then(navChromeFrost(hazeState, MessageTierShape))
                .testTag("chrome_message"),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
                    .defaultMinSize(minHeight = 56.dp)
                    .padding(start = Spacing.s18, end = if (data.visuals.actionLabel != null) Spacing.s4 else Spacing.s18),
            ) {
                Text(
                    data.visuals.message,
                    style = TextStyles.rowLabelMedium,
                    color = colors.ink,
                    modifier = Modifier.weight(1f).padding(vertical = Spacing.s12),
                )
                data.visuals.actionLabel?.let { label ->
                    Box(
                        Modifier.defaultMinSize(minHeight = 44.dp)
                            .clickable(interactionSource = null, indication = null, onClick = { data.performAction() })
                            .padding(horizontal = Spacing.s12)
                            .testTag("chrome_message_action"),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(label, style = TextStyles.buttonTertiary, color = colors.ink)
                    }
                }
            }
            if (total > 0) {
                Box(
                    Modifier.align(Alignment.BottomStart)
                        .padding(horizontal = Spacing.s18)
                        .fillMaxWidth(fuse.value)
                        .height(2.dp)
                        .background(colors.accent),
                )
            }
        }
    }
}

/** The tier's corners: the pill's family, softened for a rectangle. */
private val MessageTierShape = RoundedCornerShape(26.dp)

/**
 * The message channel every tab screen shares, so they all land in the one
 * place the chrome draws. Screens read it instead of remembering a host of
 * their own; the player keeps its own, because it has no pill to dock to.
 */
val LocalAppSnackbar = staticCompositionLocalOf { SnackbarHostState() }
