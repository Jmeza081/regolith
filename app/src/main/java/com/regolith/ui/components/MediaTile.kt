package com.regolith.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.SubcomposeAsyncImage
import coil3.compose.SubcomposeAsyncImageContent
import com.composables.icons.lucide.R as LucideR
import com.regolith.R
import com.regolith.domain.artwork.ArtworkKind
import com.regolith.domain.artwork.ArtworkRequest
import com.regolith.ui.theme.CardShape
import com.regolith.ui.theme.RegolithTheme
import com.regolith.ui.theme.Spacing
import com.regolith.ui.theme.TextStyles

/**
 * The media tile (design section 01, "Media tiles"): an image at a fixed
 * aspect, a chip over its top-left corner, a name and a metadata line
 * underneath. Its shape follows the artwork kind: POSTER is 2:3 (Library,
 * Media grid), THUMB is 16:9 (Browse, resume row).
 *
 * States, in the order a tile passes through them:
 *  - reading:     skeleton with a "Reading" chip while the frame is pulled off the share
 *  - image:       the artwork, with [chip] ("4K") over it
 *  - placeholder: the wedge on a surface with the filename, when nothing was readable
 *
 * Folders carry a corner mark; [count] draws a collection's file count;
 * [unwatched] the white dot. [dimmed] is the share-unreachable state
 * (cached art at 45%).
 *
 * @param artwork what to draw, or null for a tile that has no artwork at all (draws the placeholder).
 * @param placeholderLabel text on the wedge placeholder, usually the file extension.
 */
@Composable
fun MediaTile(
    artwork: ArtworkRequest?,
    title: String,
    onClick: () -> Unit,
    testTag: String,
    modifier: Modifier = Modifier,
    kind: ArtworkKind = artwork?.kind ?: ArtworkKind.THUMB,
    meta: String? = null,
    chip: String? = null,
    folder: Boolean = false,
    count: Int? = null,
    dimmed: Boolean = false,
    placeholderLabel: String? = null,
    /** 0..1 watched fraction; draws a thin red bar along the bottom edge. */
    progress: Float? = null,
    /** The design's unwatched mark: a white dot with a dark halo, top-right, "never a recording light". */
    unwatched: Boolean = false,
) {
    val colors = RegolithTheme.colors
    Column(modifier.clickable(onClick = onClick).testTag(testTag)) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(kind.width.toFloat() / kind.height)
                .clip(CardShape)
                .background(colors.surface)
                .alpha(if (dimmed) 0.45f else 1f),
        ) {
            if (artwork == null) {
                WedgePlaceholder(placeholderLabel ?: title)
            } else {
                SubcomposeAsyncImage(
                    model = artwork,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    loading = { ReadingState() },
                    error = { WedgePlaceholder(placeholderLabel ?: title) },
                    success = { SubcomposeAsyncImageContent(contentScale = ContentScale.Crop) },
                )
            }
            if (chip != null) {
                Chip(chip, ChipStyle.OverArt, Modifier.align(Alignment.TopStart).padding(Spacing.s8))
            }
            if (count != null) {
                Chip(count.toString(), ChipStyle.OverArt, Modifier.align(Alignment.TopEnd).padding(Spacing.s8))
            } else if (unwatched) {
                Box(Modifier.align(Alignment.TopEnd).padding(Spacing.s8).size(14.dp).background(colors.ground.copy(alpha = 0.6f), CircleShape)) {
                    Box(Modifier.align(Alignment.Center).size(8.dp).background(colors.ink, CircleShape))
                }
            }
            if (folder) {
                Icon(
                    painterResource(LucideR.drawable.lucide_ic_folder),
                    contentDescription = "Folder",
                    tint = colors.ink,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(Spacing.s8).size(16.dp),
                )
            }
            if (progress != null && progress > 0f) {
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(colors.ink.copy(alpha = 0.25f))) {
                    Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).height(3.dp).background(colors.accent))
                }
            }
        }
        Spacer(Modifier.height(Spacing.s8))
        Text(title, style = TextStyles.rowLabel, color = colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (meta != null) {
            Text(meta, style = TextStyles.metadata, color = colors.metadata, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * Skeleton plus "Reading": the frame is being pulled off the share.
 * Stillness, no shimmer. Its own Box: Coil's slots propagate the tile's
 * minimum size to their child, which would stretch a bare chip.
 */
@Composable
private fun ReadingState() {
    Box(Modifier.fillMaxSize()) {
        Skeleton(Modifier.fillMaxSize(), shape = CardShape)
        Chip("Reading", ChipStyle.OverArt, Modifier.align(Alignment.Center))
    }
}

/**
 * The wedge (the app icon's mark) on a surface, carrying [label]. This is
 * the design's answer to "unreadable or DRM'd": never a broken-image
 * glyph, never an empty box.
 */
@Composable
private fun WedgePlaceholder(label: String) {
    val colors = RegolithTheme.colors
    Box(Modifier.fillMaxSize()) {
        Icon(
            painterResource(R.drawable.ic_launcher_foreground),
            contentDescription = null,
            tint = androidx.compose.ui.graphics.Color.Unspecified,
            modifier = Modifier.align(Alignment.Center).fillMaxSize(0.9f).alpha(0.35f),
        )
        Text(
            label,
            style = TextStyles.chip,
            color = colors.metadata,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.BottomStart).padding(Spacing.s8),
        )
    }
}
