package com.regolith.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.regolith.domain.connection.ShareLink
import com.regolith.domain.connection.ShareStatus
import com.regolith.ui.theme.PillShape
import com.regolith.ui.theme.RegolithTheme
import com.regolith.ui.theme.Spacing
import com.regolith.ui.theme.TextStyles
import com.regolith.ui.util.formatAgo
import kotlinx.coroutines.delay

/**
 * The share's connection as one dot, the owner's three looks for it:
 * - connected: [RegolithTheme.colors.live] green with a soft glow;
 * - checking: grey, pulsing, until the first answer;
 * - out of reach: a grey hollow ring.
 *
 * [size] is the dot itself; the glow reaches past it, so the composable is
 * [size] × 2.5 square and the dot sits in its middle.
 */
@Composable
fun ShareStatusDot(link: ShareLink, modifier: Modifier = Modifier, size: Dp = 8.dp) {
    val colors = RegolithTheme.colors
    val pulse = if (link == ShareLink.CHECKING) {
        val transition = rememberInfiniteTransition(label = "shareDotPulse")
        val alpha by transition.animateFloat(0.35f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "shareDotAlpha")
        alpha
    } else 1f
    Canvas(modifier.size(size * 2.5f)) {
        val r = size.toPx() / 2
        when (link) {
            ShareLink.CONNECTED -> {
                drawCircle(Brush.radialGradient(listOf(colors.live.copy(alpha = 0.55f), Color.Transparent), center, r * 2.5f), r * 2.5f)
                drawCircle(colors.live, r)
            }
            ShareLink.CHECKING -> drawCircle(colors.metadata.copy(alpha = pulse), r)
            ShareLink.OUT_OF_REACH -> {
                val stroke = 1.5.dp.toPx()
                drawCircle(colors.metadata, r - stroke / 2, style = Stroke(stroke))
            }
        }
    }
}

/** "JESSES-MAC-MINI · MOVIES", or "· OUT OF REACH": what a screen reader calls the share in its state. */
private fun ShareStatus.label(): String = when (link) {
    ShareLink.OUT_OF_REACH -> "$serverName · Out of reach"
    else -> listOfNotNull(serverName.ifEmpty { null }, shareName).joinToString(" · ")
}

private fun ShareStatus.spoken(): String = when (link) {
    ShareLink.CONNECTED -> "${label()}: connected"
    ShareLink.CHECKING -> "${label()}: checking"
    ShareLink.OUT_OF_REACH -> label() + if (owed.isEmpty()) "" else ", ${owed.size} waiting to write"
} + ". Tap for details."

/**
 * The connection dot as a control: the dot alone in a small frosted circle
 * (so it reads over any poster), inside a 44dp touch target. No words: the
 * owner's call, the colour says it, and the sheet a tap opens says the rest.
 *
 * Used twice by the nav graph: just above the phone's nav pill, at its
 * start, sliding with it; and above the rail on the inner display.
 * Settings › Display › Share connection hides both.
 */
@Composable
fun ShareStatusButton(status: ShareStatus, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = RegolithTheme.colors
    Box(
        modifier
            .size(44.dp)
            .clip(PillShape)
            .clickable(onClick = onClick)
            .semantics { contentDescription = status.spoken() }
            .testTag("share_status_button"),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(28.dp).background(colors.overArt, PillShape).border(1.dp, colors.pillBorder, PillShape),
            contentAlignment = Alignment.Center,
        ) { ShareStatusDot(status.link) }
    }
}

/**
 * What tapping the dot opens: the share's connection in a few facts, and
 * the chapter files it still owes.
 *
 * Connected: the address it is reached at, the network, how long a check
 * takes, and the drops since the app opened, with **Check now**. Out of
 * reach: when it was last heard from and what went wrong, the files waiting
 * (safe on the phone), and **Try now**, which checks at once and writes them
 * if it answers.
 */
@Composable
fun ShareStatusSheet(status: ShareStatus, onCheck: () -> Unit, onDismiss: () -> Unit) {
    val colors = RegolithTheme.colors
    val out = status.link == ShareLink.OUT_OF_REACH
    // Ticks once a second, so "4 s ago" counts while the sheet is open.
    val now by produceState(System.currentTimeMillis()) {
        while (true) { delay(1_000); value = System.currentTimeMillis() }
    }
    val subtitle = when (status.link) {
        ShareLink.CONNECTED -> "Connected" + (status.lastAnsweredAtMs?.let { " · last heard from ${formatAgo(it, now)}" } ?: "")
        ShareLink.CHECKING -> "Checking…"
        ShareLink.OUT_OF_REACH -> "Out of reach" + (status.outSinceMs?.let { " for ${formatAgo(it, now).removeSuffix(" ago")}" } ?: "") + " · checking every 15 s"
    }
    RegolithSheet(
        title = status.serverName,
        onDismiss = onDismiss,
        testTag = "share_status_sheet",
        header = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.s8)) {
                ShareStatusDot(status.link, size = 10.dp)
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.s2)) {
                    DisplayText(
                        listOfNotNull(status.serverName.ifEmpty { null }, status.shareName).joinToString(" · "),
                        style = TextStyles.dialogTitle.copy(fontSize = TextStyles.dialogTitle.fontSize * 0.7f),
                        color = if (out) colors.body else colors.ink,
                        maxLines = 1,
                    )
                    Text(subtitle, style = TextStyles.meta12, color = colors.metadata, modifier = Modifier.testTag("share_status_subtitle"))
                }
            }
        },
        footer = {
            Box(Modifier.padding(top = Spacing.s12)) {
                if (out) {
                    PrimaryButton("Try now", onCheck, "share_status_try_button", Modifier.fillMaxWidth())
                } else {
                    SecondaryButton("Check now", onCheck, "share_status_check_button", Modifier.fillMaxWidth())
                }
            }
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.s18), modifier = Modifier.padding(top = Spacing.s12)) {
            SurfaceCard(contentPadding = PaddingValues(horizontal = Spacing.s12)) {
                if (out) {
                    Fact("Last heard from", status.lastAnsweredAtMs?.let { "${java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(it))} · ${formatAgo(it, now)}" } ?: "Not since the app opened")
                    Fact("What happened", status.cause ?: "No answer")
                } else {
                    Fact("Address", listOfNotNull(status.host, status.address?.takeIf { it != status.host }).joinToString(" · "))
                    Fact("Round trip", status.rttMs?.let { "$it ms" } ?: "—")
                }
                Fact("Network", status.network.label)
                Fact("Drops since opening", if (status.drops == 0) "None" else "${status.drops}", last = true)
            }
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.s8)) {
                Eyebrow(if (status.owed.isEmpty()) "Waiting to write" else "Waiting to write · ${status.owed.size}", muted = true)
                if (status.owed.isEmpty()) {
                    Text("Nothing. Every change is on the share.", style = TextStyles.meta, color = colors.body)
                } else {
                    SurfaceCard(contentPadding = PaddingValues(horizontal = Spacing.s12), modifier = Modifier.testTag("share_status_owed")) {
                        status.owed.take(MAX_OWED_ROWS).forEachIndexed { i, owed ->
                            Column(
                                Modifier.fillMaxWidth().defaultMinSize(minHeight = 52.dp).padding(vertical = Spacing.s8),
                                verticalArrangement = Arrangement.spacedBy(Spacing.s2, Alignment.CenterVertically),
                            ) {
                                Text(owed.name.substringBeforeLast('.'), style = TextStyles.rowLabelMedium, color = colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("Chapters", style = TextStyles.meta12, color = colors.metadata)
                            }
                            if (i < minOf(status.owed.size, MAX_OWED_ROWS) - 1) Box(Modifier.fillMaxWidth().height(1.dp).background(colors.hairline))
                        }
                        if (status.owed.size > MAX_OWED_ROWS) {
                            Text("and ${status.owed.size - MAX_OWED_ROWS} more", style = TextStyles.meta12, color = colors.metadata, modifier = Modifier.padding(vertical = Spacing.s12))
                        }
                    }
                    Text(
                        if (out) "Safe on this phone. Written the moment it answers." else "Being written now.",
                        style = TextStyles.meta12, color = colors.metadata,
                    )
                }
            }
        }
    }
}

/** A fact row as the video page's facts card draws one: label left, value right, 46dp. */
@Composable
private fun Fact(label: String, value: String, last: Boolean = false) {
    val colors = RegolithTheme.colors
    Row(
        Modifier.fillMaxWidth().defaultMinSize(minHeight = 46.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = TextStyles.factLabel, color = colors.metadata)
        Text(value, style = TextStyles.factValue, color = colors.inkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = Spacing.s12))
    }
    if (!last) Box(Modifier.fillMaxWidth().height(1.dp).background(colors.hairline))
}

/** Past this the list says "and N more": the sheet is a glance, not a queue manager. */
private const val MAX_OWED_ROWS = 5
