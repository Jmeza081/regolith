package com.regolith.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.R as LucideR
import com.regolith.R
import com.regolith.domain.artwork.ArtworkKind
import com.regolith.domain.artwork.ArtworkOwner
import com.regolith.domain.artwork.ArtworkRequest
import com.regolith.domain.playback.Reel
import com.regolith.domain.playback.ReelClip
import com.regolith.player.PlaybackState
import com.regolith.player.ReelState
import com.regolith.ui.components.DisplayText
import com.regolith.ui.components.Eyebrow
import com.regolith.ui.components.IconCircleButton
import com.regolith.ui.components.ListRow
import com.regolith.ui.components.RowLeading
import com.regolith.ui.components.RowTrailing
import com.regolith.ui.components.SecondaryButton
import com.regolith.ui.components.StorySegments
import com.regolith.ui.components.bleedHorizontally
import com.regolith.ui.theme.RegolithTheme
import com.regolith.ui.theme.Spacing
import com.regolith.ui.theme.TextStyles
import com.regolith.ui.theme.designSp
import com.regolith.ui.theme.scaledDp
import com.regolith.ui.util.formatClock

/*
 * The Moments reel in the player (PlaybackSession.loadReel): the canvas "Next
 * Five Features", boards Reel-Play and Reel-Inner. The player keeps its own
 * picture, transport and ways out; these are the parts a reel has instead of
 * a film's timeline, title and folder.
 */

/**
 * The reel's place, across the top of the picture: one short bar per moment
 * ([StorySegments], which a picture story shares). Every moment has its bar,
 * however many: a reel is a collection's named moments, rarely past a dozen.
 */
@Composable
internal fun ReelSegments(reel: ReelState, progress: () -> Float, modifier: Modifier = Modifier) {
    StorySegments(
        count = reel.clips.size,
        index = reel.index,
        progress = progress,
        description = "Moment ${reel.index + 1} of ${reel.clips.size}",
        testTag = "player_reel_segments",
        modifier = modifier,
        max = reel.clips.size,
    )
}

/**
 * The moment's name over the picture, with its video and its time: what the
 * reel shows as each moment starts, and with the controls ([large] on the
 * inner display, where the picture is twice the size).
 */
@Composable
internal fun ReelCaption(clip: ReelClip, modifier: Modifier = Modifier, large: Boolean = false) {
    val colors = RegolithTheme.colors
    Column(modifier.testTag("player_reel_caption"), verticalArrangement = Arrangement.spacedBy(if (large) 6.dp else 4.dp)) {
        DisplayText(
            clip.name,
            style = TextStyles.screenTitle.copy(
                fontSize = (if (large) 16 else 12).designSp(),
                lineHeight = (if (large) 21 else 16).designSp(),
            ),
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        Text(
            "${clip.videoName} · ${formatClock(clip.startMs)}",
            style = if (large) TextStyles.meta12 else TextStyles.meta, color = colors.inkSoft,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Under the picture while a reel plays: whose moments these are, the one
 * playing (its video, its time and its place in the reel), Watch from here
 * (the whole video, carrying on from this moment) and the reel's shuffle.
 */
@Composable
internal fun ReelDetails(reel: ReelState, onWatchFromHere: () -> Unit, onShuffle: () -> Unit, modifier: Modifier = Modifier) {
    val colors = RegolithTheme.colors
    val clip = reel.clip
    Column(modifier.testTag("player_reel_details"), verticalArrangement = Arrangement.spacedBy(Spacing.s12)) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Eyebrow("Moments · ${reel.title}")
            DisplayText(
                clip.name,
                style = TextStyles.screenTitle.copy(fontSize = 15.designSp(), lineHeight = 19.5.designSp()),
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${clip.videoName} · ${formatClock(clip.startMs)} · ${reel.index + 1} of ${reel.clips.size}",
                style = TextStyles.meta, color = colors.metadata, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            SecondaryButton(
                "Watch from here", onWatchFromHere, "player_reel_watch",
                leadingIcon = painterResource(R.drawable.rg_ic_play), compact = true,
            )
            Spacer(Modifier.weight(1f))
            IconCircleButton(
                painterResource(R.drawable.rg_ic_shuffle),
                if (reel.shuffled) "Shuffle is on. Tap to play the moments in order." else "Shuffle the moments",
                onShuffle, "player_reel_shuffle",
                size = 42.scaledDp(),
                tint = if (reel.shuffled) colors.accent else null,
            )
        }
    }
}

/**
 * "In this reel": every moment in the order it plays, the ones played stepped
 * back and the one playing marked, with how far into it the reel is
 * ([playing], 0 to 1). A tap goes to that moment.
 */
@Composable
internal fun ReelList(reel: ReelState, playing: Float, onClip: (Int) -> Unit, modifier: Modifier = Modifier) {
    val colors = RegolithTheme.colors
    Column(modifier.testTag("player_reel_list"), verticalArrangement = Arrangement.spacedBy(Spacing.s4)) {
        Row(Modifier.fillMaxWidth().padding(bottom = Spacing.s4), verticalAlignment = Alignment.CenterVertically) {
            Eyebrow("In this reel", muted = true, modifier = Modifier.weight(1f))
            Text(
                "${reel.clips.size} moments · about ${Reel.roughLength(Reel.lengthMs(reel.clips))}",
                style = TextStyles.meta, color = colors.metadata, maxLines = 1,
            )
        }
        reel.clips.forEachIndexed { index, clip ->
            val current = index == reel.index
            ListRow(
                title = clip.name,
                meta = "${clip.videoName} · ${formatClock(clip.startMs)}",
                leading = RowLeading.Thumb(ArtworkRequest(ArtworkOwner.Moment(clip.fileId, clip.startMs), ArtworkKind.THUMB), fallbackLabel = clip.videoName),
                trailing = RowTrailing.None,
                onClick = { onClip(index) },
                testTag = "player_reel_clip_$index",
                progress = playing.takeIf { current },
                action = if (current) {
                    {
                        // Well in from the highlight's rounded end, not on it.
                        Icon(
                            painterResource(LucideR.drawable.lucide_ic_audio_lines), "Playing", tint = colors.ink,
                            modifier = Modifier.padding(start = Spacing.s8, end = PLAYING_MARK_END).size(16.dp),
                        )
                    }
                } else {
                    null
                },
                modifier = Modifier
                    // The playing row's highlight reaches past the column, as the
                    // canvas draws it, and every row pads its contents back in
                    // line: the thumb and the playing mark sit inside the
                    // highlight's edges rather than on them.
                    .bleedHorizontally(ROW_INSET)
                    .then(if (current) Modifier.background(colors.raised, RoundedCornerShape(12.dp)) else Modifier)
                    .padding(horizontal = ROW_INSET)
                    .alpha(if (index < reel.index) PLAYED_ALPHA else 1f),
            )
        }
    }
}

/** How far into its moment a reel is, 0 to 1; 0 when no reel plays. Four times a second, from [PlaybackState.positionMs]. */
internal fun PlaybackState.reelProgress(): Float {
    val length = reel?.clip?.durationMs?.takeIf { it > 0 } ?: return 0f
    return (positionMs.toFloat() / length).coerceIn(0f, 1f)
}

/** An upcoming moment's bar: the played ones and the filling part are white over this. */

/** The moments already played, stepped back in the list. */
private const val PLAYED_ALPHA = 0.55f

/** How far a row's highlight reaches past the list on each side, and how far its contents sit inside it. */
private val ROW_INSET = 8.dp

/** The playing mark's own room at the row's end, on top of [ROW_INSET]: 20dp in from the highlight's edge. */
private val PLAYING_MARK_END = 12.dp
