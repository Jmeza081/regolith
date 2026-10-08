package com.regolith.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.regolith.R
import com.regolith.domain.artwork.ArtworkKind
import com.regolith.domain.artwork.ArtworkOwner
import com.regolith.domain.artwork.ArtworkRequest
import com.regolith.ui.components.ArtworkImage
import com.regolith.ui.components.ArtworkLight
import com.regolith.ui.components.bleedHorizontally
import com.regolith.ui.components.DisplayText
import com.regolith.ui.components.EmptyState
import com.regolith.ui.components.Ghost
import com.regolith.ui.components.Eyebrow
import com.regolith.ui.components.IconCircleButton
import com.regolith.ui.components.ListRow
import com.regolith.ui.components.MediaTile
import com.regolith.ui.components.PlayAllButton
import com.regolith.ui.components.RowLeading
import com.regolith.ui.components.RowTrailing
import com.regolith.ui.components.Segment
import com.regolith.ui.components.SegmentedTabs
import com.regolith.ui.components.posterFlight
import com.regolith.ui.theme.RegolithTheme
import com.regolith.ui.theme.Spacing
import com.regolith.ui.theme.TextStyles
import com.regolith.ui.theme.TileShape
import com.regolith.ui.theme.scaledDp
import com.regolith.ui.util.formatBytes
import com.regolith.ui.util.formatClock
import com.regolith.ui.util.formatDurationShort

/*
 * A leaf collection's page, read as a profile ([CollectionProfile]): the
 * canvas "Collection View Refinements", boards "C + A". These are its parts;
 * LibraryScreen lays them out as the first items of the wall itself, so the
 * header scrolls away with the videos instead of standing over them.
 */

/** Which part of a profile's page is showing: the wall of its videos, the moments in them, or its pictures. */
internal enum class ProfileTab { VIDEOS, MOMENTS, IMAGES }

/**
 * A tab's own Play and Shuffle, in place of Play all: the Moments tab's
 * reel (PlaybackSession.loadReel) or the Images tab's story (P20), named
 * for what plays ([noun]: "moments", "pictures") with roughly how long it
 * runs ("1 min").
 */
internal class TabPlay(val noun: String, val length: String, val onPlay: () -> Unit, val onShuffle: () -> Unit)

/**
 * The top of a profile: the collection's poster lighting the page from
 * behind ([ArtworkLight]), the poster itself, what the collection sits in
 * and its name, a strip of stats, and Play all, Shuffle and Add.
 *
 * On a phone everything is centred under the poster, the way an album page
 * reads. With room for it (the inner display, the wall to itself) the
 * poster moves to the side and the rest stands beside it, so the wall
 * starts a third of a screen sooner.
 *
 * [bleed] is the wall's own side padding: the light reaches past it to the
 * window's edges, where padding would have stopped it in a straight line.
 * [onAdd] is null where nothing can be added (the demo, a phone folder).
 * [tab] is set while the Moments or Images tab shows: Play and Shuffle then
 * play its moments as a reel, or its pictures as a story, instead of the
 * videos. [onPlay] and [onShuffle] are null where there are no videos: an
 * album of pictures alone, which plays its pictures.
 */
@Composable
internal fun ProfileHeader(
    profile: CollectionProfile,
    name: String,
    onPlay: (() -> Unit)?,
    onShuffle: (() -> Unit)?,
    onAdd: (() -> Unit)?,
    modifier: Modifier = Modifier,
    bleed: Dp = Spacing.s18,
    tab: TabPlay? = null,
) {
    val colors = RegolithTheme.colors
    Box(modifier.fillMaxWidth().testTag("library_profile_header")) {
        // The light fills the header's whole height, up under the status bar
        // and out to both edges, and falls off to the page's own ground at
        // the bottom so the wall below starts on plain black. Darker again
        // at the very top, as Title Detail's hero is: a pale poster would
        // otherwise leave the status bar and the white icons on white.
        Box(Modifier.matchParentSize().bleedHorizontally(bleed)) {
            ArtworkLight(profile.light, Modifier.fillMaxSize(), testTag = "library_profile_light")
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(
                        0f to Color(0x73000000), 0.22f to Color(0x1A000000), 0.6f to Color(0x80000000), 1f to colors.ground,
                    ),
                ),
            )
        }
        BoxWithConstraints(Modifier.fillMaxWidth().padding(top = profileBarHeight(), bottom = Spacing.s18)) {
            if (maxWidth >= PROFILE_SIDE_BY_SIDE_MIN) {
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(Spacing.s30)) {
                    ProfilePoster(profile.poster, name, Modifier.size(width = 136.scaledDp(), height = 204.scaledDp()))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.s18)) {
                        Column(verticalArrangement = Arrangement.spacedBy(Spacing.s8)) {
                            profile.parentName?.let { Eyebrow(it) }
                            DisplayText(name, style = TextStyles.detailTitle, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        StatStrip(profile, Modifier.widthIn(max = PROFILE_STATS_MAX_WIDTH).fillMaxWidth())
                        ProfileActions(onPlay, onShuffle, onAdd, playWidth = PROFILE_PLAY_WIDTH, tab = tab)
                    }
                }
            } else {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    ProfilePoster(profile.poster, name, Modifier.size(width = 112.scaledDp(), height = 168.scaledDp()))
                    Spacer(Modifier.height(Spacing.s18))
                    profile.parentName?.let {
                        Eyebrow(it)
                        Spacer(Modifier.height(Spacing.s8))
                    }
                    DisplayText(name, style = TextStyles.detailTitle, maxLines = 3, textAlign = TextAlign.Center, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(Spacing.s18))
                    StatStrip(profile, Modifier.fillMaxWidth())
                    Spacer(Modifier.height(Spacing.s18))
                    ProfileActions(onPlay, onShuffle, onAdd, playWidth = null, modifier = Modifier.fillMaxWidth(), tab = tab)
                }
            }
        }
    }
}

/**
 * The Videos / Moments / Images switch under the header: the same control as
 * the Library's Network / On this device. Only the tabs the collection has
 * ([CollectionProfile.tabs]): an album of pictures alone has none, and shows
 * its pictures straight away. Moments carries no count when there are none,
 * so an empty tab does not announce itself with a zero.
 */
@Composable
internal fun ProfileTabs(profile: CollectionProfile, tab: ProfileTab, onTab: (ProfileTab) -> Unit, modifier: Modifier = Modifier) {
    val tabs = profile.tabs
    BoxWithConstraints(modifier.fillMaxWidth()) {
        SegmentedTabs(
            segments = tabs.map { t ->
                when (t) {
                    ProfileTab.VIDEOS -> Segment("Videos", "library_profile_tab_videos", count = profile.videoCount)
                    ProfileTab.MOMENTS -> Segment("Moments", "library_profile_tab_moments", count = profile.moments.size.takeIf { it > 0 })
                    ProfileTab.IMAGES -> Segment("Images", "library_profile_tab_images", count = profile.pictures.size)
                }
            },
            selected = tabs.indexOf(tab).coerceAtLeast(0),
            onSelect = { onTab(tabs[it]) },
            // Beside the header on a wide window, not across the whole of it:
            // two words do not need 900dp between them.
            modifier = if (maxWidth >= PROFILE_SIDE_BY_SIDE_MIN) Modifier.widthIn(max = PROFILE_TABS_MAX_WIDTH) else Modifier,
        )
    }
}

/**
 * One moment as a tile: the frame at its time with the time over it, the
 * chapter's name, and the video it is in. Search draws a point of interest
 * the same way, and the frame is the same cached picture.
 */
@Composable
internal fun MomentTile(moment: CollectionMoment, onPlayAt: (fileId: Long, startMs: Long) -> Unit, modifier: Modifier = Modifier) {
    MediaTile(
        artwork = moment.frame(),
        kind = ArtworkKind.THUMB,
        title = moment.title,
        meta = moment.videoName,
        chip = formatClock(moment.startMs),
        fallbackLabel = moment.videoName,
        onClick = { onPlayAt(moment.fileId, moment.startMs) },
        testTag = moment.testTag,
        modifier = modifier,
    )
}

/** One moment as a row, for the wall's rows mode: the frame small, the time beside the video's name. */
@Composable
internal fun MomentRow(moment: CollectionMoment, onPlayAt: (fileId: Long, startMs: Long) -> Unit, modifier: Modifier = Modifier) {
    ListRow(
        title = moment.title,
        meta = "${moment.videoName} · ${formatClock(moment.startMs)}",
        leading = RowLeading.Thumb(moment.frame(), fallbackLabel = moment.videoName),
        trailing = RowTrailing.None,
        onClick = { onPlayAt(moment.fileId, moment.startMs) },
        testTag = moment.testTag,
        modifier = modifier,
    )
}

/**
 * The Moments tab with nothing on it: what a moment is, and how one gets
 * here, over the outline of the frames ([rows]: the rows) it will hold.
 * Compact, because the profile's header is right above it.
 */
@Composable
internal fun NoMoments(rows: Boolean, modifier: Modifier = Modifier) {
    EmptyState(
        title = "Moments will collect here",
        body = "Name a chapter while one of these videos plays and it shows up here, ready to play from.",
        // Two rows of frames: one alone fades out before its names show.
        ghost = if (rows) Ghost.Rows(count = 3) else Ghost.Frames(rows = 2),
        compact = true,
        modifier = modifier.padding(top = Spacing.s8),
        testTag = "library_moments_empty",
    )
}

/**
 * How tall the profile's top bar is, status bar included: the header starts
 * its poster under it, and LibraryScreen fills the bar in once the header
 * has scrolled up behind it. A [com.regolith.ui.components.TopBar] with a
 * title alone is s12 above a 44dp row of actions and s18 below.
 */
@Composable
internal fun profileBarHeight(): Dp =
    WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + Spacing.s12 + 44.dp + Spacing.s18

/** The frame at a moment's time, the picture Search already caches for it. */
private fun CollectionMoment.frame() = ArtworkRequest(ArtworkOwner.Moment(fileId, startMs), ArtworkKind.THUMB)

/**
 * The poster, whole: 2:3 at the wall's 12dp corners, with a hairline so a
 * dark poster keeps its edge on the light. The collection's tile flies into
 * it from the wall, and back (PosterFlight.kt).
 */
@Composable
private fun ProfilePoster(poster: ArtworkRequest, name: String, modifier: Modifier) {
    ArtworkImage(
        poster,
        modifier
            .posterFlight(poster.owner)
            .clip(TileShape)
            .border(1.dp, Color(0x24FFFFFF), TileShape)
            .testTag("library_profile_poster"),
        fallbackLabel = name,
    )
}

/**
 * Videos, runtime, size and how many have been watched, in a hairline strip.
 * The runtime cell is left out until every video's length is known, for the
 * reason Play all's sheet gives: a total that is quietly short is worse than
 * none.
 */
@Composable
private fun StatStrip(profile: CollectionProfile, modifier: Modifier = Modifier) {
    val colors = RegolithTheme.colors
    val pictures = profile.pictures.size
    val stats = buildList {
        if (profile.videoCount > 0) add(profile.videoCount.toString() to if (profile.videoCount == 1) "Video" else "Videos")
        if (pictures > 0) add(pictures.toString() to if (pictures == 1) "Picture" else "Pictures")
        if (profile.videoCount > 0) profile.runtimeMs?.let { add(formatDurationShort(it) to "Runtime") }
        add(formatBytes(profile.sizeBytes + profile.pictureBytes) to "Size")
        if (profile.videoCount > 0) add("${profile.watchedCount} of ${profile.videoCount}" to "Watched")
        // An album: when its pictures were taken, in place of what a video has.
        if (profile.videoCount == 0) picturesSpan(profile.pictures)?.let { add(it to "Taken") }
    }.take(PROFILE_STATS_MAX)
    Row(
        modifier
            .height(IntrinsicSize.Min)
            .drawBehind {
                val hairline = 1.dp.toPx()
                drawRect(colors.hairline, size = size.copy(height = hairline))
                drawRect(colors.hairline, topLeft = Offset(0f, size.height - hairline), size = size.copy(height = hairline))
            }
            .testTag("library_profile_stats"),
    ) {
        stats.forEachIndexed { index, (value, label) ->
            if (index > 0) Box(Modifier.width(1.dp).fillMaxHeight().background(colors.hairline))
            Column(
                Modifier.weight(1f).padding(vertical = Spacing.s12),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Spacing.s8),
            ) {
                Text(value, style = TextStyles.rowLabel, color = colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(label.uppercase(), style = TextStyles.fieldLabel, color = colors.navIdle, maxLines = 1)
            }
        }
    }
}

/**
 * Play all, Shuffle, and Add. Play all is the one red on the page and plays
 * the wall in the order it is showing; Shuffle plays the same videos
 * scrambled; Add is the collection's Add sheet, where uploads are allowed.
 * On the Moments tab ([moments]) the first two play the moments as a reel,
 * in the tab's order or scrambled, and Play says how long it runs.
 */
@Composable
private fun ProfileActions(
    onPlay: (() -> Unit)?,
    onShuffle: (() -> Unit)?,
    onAdd: (() -> Unit)?,
    /** Play all's own width, or null to take whatever the row leaves. */
    playWidth: Dp?,
    modifier: Modifier = Modifier,
    tab: TabPlay? = null,
) {
    val play = tab?.onPlay ?: onPlay
    val shuffle = tab?.onShuffle ?: onShuffle
    // Nothing to play (an album of pictures alone): Add stands on its own,
    // where the row's middle is on a phone.
    val arrangement = if (play == null) Arrangement.spacedBy(Spacing.s8, Alignment.CenterHorizontally) else Arrangement.spacedBy(Spacing.s8)
    Row(modifier, horizontalArrangement = arrangement, verticalAlignment = Alignment.CenterVertically) {
        if (play != null) {
            PlayAllButton(
                play,
                if (playWidth != null) Modifier.width(playWidth) else Modifier.weight(1f),
                text = tab?.let { "Play ${it.noun} · ${it.length}" } ?: "Play all",
                testTag = tab?.let { "library_play_${it.noun}_button" } ?: "play_all_button",
            )
        }
        // The same height as the compact Play all beside them.
        if (shuffle != null) {
            IconCircleButton(
                painterResource(R.drawable.rg_ic_shuffle),
                tab?.let { "Shuffle the ${it.noun}" } ?: "Shuffle",
                shuffle,
                tab?.let { "library_shuffle_${it.noun}_button" } ?: "library_profile_shuffle_button",
                size = 42.scaledDp(),
            )
        }
        if (onAdd != null) {
            IconCircleButton(
                painterResource(R.drawable.rg_ic_upload), "Add to this collection", onAdd, "library_profile_add_button",
                size = 42.scaledDp(),
            )
        }
    }
}

/** Four cells read as one line on a phone; past that the Watched cell gives way, as the canvas drew a collection with pictures. */
private const val PROFILE_STATS_MAX = 4

/**
 * When an album's pictures were taken, as one cell: "2024", or "2019–2024",
 * from each picture's own date, or its file's where it does not say.
 */
private fun picturesSpan(pictures: List<PictureTile>): String? {
    if (pictures.isEmpty()) return null
    val years = pictures.map { java.time.Instant.ofEpochMilli(it.takenAtMs ?: it.modifiedAtMs).atZone(java.time.ZoneId.systemDefault()).year }
    val first = years.min()
    val last = years.max()
    return if (first == last) "$first" else "$first–$last"
}

/** Wide enough for the poster to stand beside the name: the inner display with the wall to itself, not beside a title's page. */
private val PROFILE_SIDE_BY_SIDE_MIN = 600.dp

/** The stat strip beside the poster: four cells read as one line, not spread across the window. */
private val PROFILE_STATS_MAX_WIDTH = 480.dp

/** Play all beside the poster: about the phone's own, rather than the whole column. */
private val PROFILE_PLAY_WIDTH = 240.dp

/** The tabs beside the poster on a wide window. */
private val PROFILE_TABS_MAX_WIDTH = 420.dp
