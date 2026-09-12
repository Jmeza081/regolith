package com.regolith.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.regolith.R
import com.regolith.domain.artwork.ArtworkRequest
import com.regolith.ui.theme.BoxShape
import com.regolith.ui.theme.RegolithTheme
import com.regolith.ui.theme.Spacing
import com.regolith.ui.theme.TextStyles
import com.regolith.ui.theme.ThumbShape
import com.regolith.ui.theme.scaledDp

/**
 * What sits at the right of a row. [Checked] is both "this is the chosen
 * one" (the share picker) and "this is picked" (a download selection).
 */
enum class RowTrailing { Chevron, Checked, None }

/** What sits at the left of a row. */
sealed interface RowLeading {
    /** A 17dp glyph in a 34dp #1A1A1A box with 10dp corners (Browse folders). */
    data class IconBox(val icon: Int) : RowLeading

    /** A bare 18dp glyph (Home's server list). */
    data class Glyph(val icon: Int) : RowLeading

    /** A 52dp-wide 16:9 thumbnail with 7dp corners (Browse files, search results). */
    data class Thumb(val artwork: ArtworkRequest?, val fallbackLabel: String = "") : RowLeading

    /** A 34dp-wide 2:3 poster with 7dp corners (Library in rows mode). */
    data class Poster(val artwork: ArtworkRequest?, val fallbackLabel: String = "") : RowLeading

    /**
     * Selection mode on a row you can still walk into: a 38dp box holding
     * the check when picked and [icon] when not.
     *
     * Paired with [ListRow]'s `onLeadingClick`, this is the split the
     * Choose-folders picker already proved necessary — see that screen's
     * `FolderRow`. With the whole row picking, walking down to a folder
     * three levels in means selecting every folder on the way, and a
     * picked folder swallows everything under it, so the first tap makes
     * the rest unreachable. Opening is the common act and keeps the big
     * target; picking is the deliberate one and gets a box that looks like
     * what it is.
     *
     * [locked] is the covered case: in, but not by its own doing, so the
     * box is grey with a grey check and takes no taps.
     */
    data class PickBox(val icon: Int, val picked: Boolean, val locked: Boolean = false) : RowLeading

    data object None : RowLeading
}

/**
 * One row inside a card (design: Browse, Settings, Home's server list).
 * 56dp minimum height, 12dp gap, label at 500 14/18 (or 13/17 when
 * [compact]), meta at 400 11/1.4 in #6E6E6E, a 15dp chevron or check on
 * the right. Rows are separated by nothing but their own height; the card
 * is the frame.
 */
@Composable
fun ListRow(
    title: String,
    onClick: () -> Unit,
    testTag: String,
    modifier: Modifier = Modifier,
    meta: String? = null,
    leading: RowLeading = RowLeading.None,
    trailing: RowTrailing = RowTrailing.Chevron,
    compact: Boolean = false,
    /**
     * 56px in the frames around a 34px icon box. The box goes through
     * SIZE_SCALE, so the row that frames it must too, or the box ends up
     * 6dp from the card edge where the design had 11.
     */
    minHeight: androidx.compose.ui.unit.Dp = 56.scaledDp(),
    /** Text drawn at the right instead of a glyph, e.g. "2.4 TB free". */
    trailingText: String? = null,
    /**
     * Hold to start a multi-selection (Browse, Library, Search). Null means
     * the row has nothing to hold for. The platform's own ~500 ms timeout
     * applies, which is the reflex every other Android app has trained;
     * [com.regolith.ui.components.SELECT_HOLD_MS] is where that is written
     * down and how it would be changed.
     */
    onLongClick: (() -> Unit)? = null,
    /**
     * A separate tap target on the leading slot, for a [RowLeading.PickBox]:
     * this picks the row while [onClick] still opens it. Null leaves the
     * whole row as one target, which is right for a file — there is nowhere
     * to walk into.
     */
    onLeadingClick: (() -> Unit)? = null,
    /** Spoken for the pick box, e.g. "Choose Films" / "Films, picked". */
    leadingDescription: String? = null,
) {
    val colors = RegolithTheme.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = minHeight)
            // With a pick box the row is two targets: the box picks, this
            // opens. Without one it is a single target, as it always was.
            .then(
                if (onLeadingClick == null) {
                    Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)
                } else {
                    Modifier
                },
            )
            .testTag(testTag),
    ) {
        when (leading) {
            is RowLeading.IconBox -> Box(Modifier.size(34.scaledDp()).background(colors.badgeBg, BoxShape), contentAlignment = Alignment.Center) {
                Icon(painterResource(leading.icon), contentDescription = null, tint = colors.ink, modifier = Modifier.size(17.scaledDp()))
            }
            is RowLeading.Glyph -> Icon(painterResource(leading.icon), contentDescription = null, tint = colors.ink, modifier = Modifier.size(18.scaledDp()))
            is RowLeading.Thumb -> Box(Modifier.width(52.scaledDp()).aspectRatio(16f / 9f).clip(ThumbShape)) {
                ArtworkImage(leading.artwork, fallbackLabel = leading.fallbackLabel, modifier = Modifier.size(52.scaledDp(), 29.25.scaledDp()))
            }
            is RowLeading.Poster -> Box(Modifier.width(34.scaledDp()).aspectRatio(2f / 3f).clip(ThumbShape)) {
                ArtworkImage(leading.artwork, fallbackLabel = leading.fallbackLabel, modifier = Modifier.size(34.scaledDp(), 51.scaledDp()))
            }
            is RowLeading.PickBox -> Box(
                Modifier
                    .size(38.scaledDp())
                    .clip(BoxShape)
                    .background(if (leading.picked) colors.ink else colors.disabledBg)
                    .then(if (leading.locked) Modifier.border(1.dp, colors.hairline, BoxShape) else Modifier)
                    .then(
                        if (onLeadingClick != null && !leading.locked) {
                            Modifier.clickable(interactionSource = null, indication = null, onClick = onLeadingClick)
                        } else {
                            Modifier
                        },
                    )
                    .testTag("${testTag}_pick"),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painterResource(if (leading.picked || leading.locked) R.drawable.rg_ic_check else leading.icon),
                    contentDescription = leadingDescription,
                    tint = if (leading.picked) colors.ground else colors.metadata,
                    modifier = Modifier.size(18.scaledDp()),
                )
            }
            RowLeading.None -> Unit
        }
        if (leading != RowLeading.None) Spacer(Modifier.width(Spacing.s12))
        Column(
            Modifier
                .weight(1f)
                .then(
                    if (onLeadingClick != null) {
                        Modifier.combinedClickable(
                            interactionSource = null, indication = null,
                            onClick = onClick, onLongClick = onLongClick,
                        ).testTag("${testTag}_open")
                    } else {
                        Modifier
                    },
                ),
        ) {
            Text(title, style = if (compact) TextStyles.rowLabelSmall else TextStyles.rowLabelMedium, color = colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (meta != null) {
                Text(meta, style = TextStyles.meta, color = colors.metadata, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (trailingText != null) {
            Spacer(Modifier.width(Spacing.s12))
            Text(trailingText, style = TextStyles.meta, color = colors.metadata, maxLines = 1)
        }
        when (trailing) {
            RowTrailing.Chevron -> {
                Spacer(Modifier.width(Spacing.s12))
                Icon(painterResource(R.drawable.rg_ic_chevron_right), contentDescription = null, tint = colors.metadata, modifier = Modifier.size(15.scaledDp()))
            }
            RowTrailing.Checked -> {
                Spacer(Modifier.width(Spacing.s12))
                Icon(painterResource(R.drawable.rg_ic_check), contentDescription = "Selected", tint = colors.ink, modifier = Modifier.size(18.scaledDp()))
            }
            RowTrailing.None -> Unit
        }
    }
}
