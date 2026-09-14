package com.regolith.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.regolith.ui.theme.RegolithTheme
import com.regolith.ui.theme.SheetShape
import com.regolith.ui.theme.Spacing
import com.regolith.ui.theme.TextStyles
import com.regolith.ui.theme.designSp
import com.regolith.ui.theme.scaledDp

/**
 * The app's bottom sheet: the 38×4dp handle, the 22dp top radius, the
 * surface colour and the 18dp gutters, with [title] over whatever the
 * caller puts in [content].
 *
 * It exists because this scaffold was written out three times — Library's
 * sort sheet, Play all, and Search's moment filter — and the third copy is
 * where a shared one stops being premature. Sheets that ask a question with
 * a list of answers should use [SheetOption] for the rows; a sheet whose
 * rows carry more than a label (Play all's glyph and explanation) keeps its
 * own row and still sits in this frame.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RegolithSheet(
    title: String,
    onDismiss: () -> Unit,
    testTag: String,
    modifier: Modifier = Modifier,
    /** A line under the title: what is being chosen between, or how much of it there is. */
    subtitle: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = RegolithTheme.colors
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = SheetShape,
        containerColor = colors.surface,
        contentColor = colors.ink,
        dragHandle = null,
    ) {
        Column(
            modifier.padding(start = Spacing.s18, end = Spacing.s18, top = Spacing.s12)
                .navigationBarsPadding().testTag(testTag),
        ) {
            Box38Handle()
            Spacer(Modifier.height(Spacing.s12))
            DisplayText(title, style = TextStyles.dialogTitle.copy(fontSize = 14.designSp(), lineHeight = 19.6.designSp()))
            if (subtitle != null) {
                Text(subtitle, style = TextStyles.meta12, color = colors.metadata, modifier = Modifier.padding(top = Spacing.s2))
            }
            Spacer(Modifier.height(Spacing.s4))
            content()
            Spacer(Modifier.height(Spacing.s12))
        }
    }
}

/** The grab handle. Drawn rather than Material's, which is taller than the design's. */
@Composable
private fun ColumnScope.Box38Handle() {
    val colors = RegolithTheme.colors
    androidx.compose.foundation.layout.Box(
        Modifier.align(Alignment.CenterHorizontally).size(38.dp, 4.dp)
            .background(colors.raised, RoundedCornerShape(2.dp)),
    )
}

/**
 * One answer in a [RegolithSheet]: the label, an optional [trailing] fact
 * on the right, and a red check when it is the one in force. 48dp so a list
 * of them stays thumb-sized.
 */
@Composable
fun SheetOption(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    testTag: String,
    modifier: Modifier = Modifier,
    /** "3 films", "1080p" — a fact about this answer, never a second label. */
    trailing: String? = null,
) {
    val colors = RegolithTheme.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp)
            .clickable(interactionSource = null, indication = null, onClick = onClick)
            .testTag(testTag),
    ) {
        Text(
            label,
            style = TextStyles.settingLabel.copy(lineHeight = 20.designSp()),
            color = if (selected) colors.ink else colors.inkSoft,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (trailing != null) {
            Text(trailing, style = TextStyles.meta12, color = colors.metadata, modifier = Modifier.padding(horizontal = Spacing.s8))
        }
        if (selected) {
            Icon(RegolithIcons.Check, contentDescription = "Selected", tint = colors.accent, modifier = Modifier.size(18.scaledDp()))
        }
    }
}
