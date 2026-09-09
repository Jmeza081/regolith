package com.regolith.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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

enum class RowTrailing { Chevron, Checked, None }

/** What sits at the left of a row. */
sealed interface RowLeading {
    /** A 17dp glyph in a 34dp #1A1A1A box with 10dp corners (Browse folders). */
    data class IconBox(val icon: Int) : RowLeading

    /** A bare 18dp glyph (Home's server list). */
    data class Glyph(val icon: Int) : RowLeading

    /** A 52dp-wide 16:9 thumbnail with 7dp corners (Browse files, search results). */
    data class Thumb(val artwork: ArtworkRequest?, val fallbackLabel: String = "") : RowLeading

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
    minHeight: androidx.compose.ui.unit.Dp = 56.dp,
    /** Text drawn at the right instead of a glyph, e.g. "2.4 TB free". */
    trailingText: String? = null,
) {
    val colors = RegolithTheme.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = minHeight)
            .clickable(onClick = onClick)
            .testTag(testTag),
    ) {
        when (leading) {
            is RowLeading.IconBox -> Box(Modifier.size(34.dp).background(colors.badgeBg, BoxShape), contentAlignment = Alignment.Center) {
                Icon(painterResource(leading.icon), contentDescription = null, tint = colors.ink, modifier = Modifier.size(17.dp))
            }
            is RowLeading.Glyph -> Icon(painterResource(leading.icon), contentDescription = null, tint = colors.ink, modifier = Modifier.size(18.dp))
            is RowLeading.Thumb -> Box(Modifier.width(52.dp).aspectRatio(16f / 9f).clip(ThumbShape)) {
                ArtworkImage(leading.artwork, fallbackLabel = leading.fallbackLabel, modifier = Modifier.size(52.dp, 29.25.dp))
            }
            RowLeading.None -> Unit
        }
        if (leading != RowLeading.None) Spacer(Modifier.width(Spacing.s12))
        Column(Modifier.weight(1f)) {
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
                Icon(painterResource(R.drawable.rg_ic_chevron_right), contentDescription = null, tint = colors.metadata, modifier = Modifier.size(15.dp))
            }
            RowTrailing.Checked -> {
                Spacer(Modifier.width(Spacing.s12))
                Icon(painterResource(R.drawable.rg_ic_check), contentDescription = "Selected", tint = colors.ink, modifier = Modifier.size(18.dp))
            }
            RowTrailing.None -> Unit
        }
    }
}
