package com.regolith.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.regolith.ui.theme.CardShape
import com.regolith.ui.theme.RegolithTheme
import com.regolith.ui.theme.Spacing
import com.regolith.ui.theme.TextStyles

/** Card treatments from design section 01, "Surfaces". */
enum class CardStyle {
    /** #0F0F0F fill with a #1F1F1F hairline. The default. */
    Filled,
    /** Dashed #2E2E2E hairline on #050505. Reserved for "nothing here yet". */
    Empty,
    /** 8% red tint with a red hairline. Error messages, never a full fill. */
    Error,
    /** #141414 with a #2E2E2E hairline: a notice such as "Couldn't reach media". */
    Notice,
    /** 1.5dp white border: a selected tile (share picker). */
    Selected,
}

/**
 * The one card. 14dp radius, hairline border. Rows inside a card use
 * `contentPadding = PaddingValues(horizontal = 12.dp)` and carry their
 * own vertical rhythm (min-height 46–56dp); free content uses 12 or 18.
 */
@Composable
fun SurfaceCard(
    modifier: Modifier = Modifier,
    style: CardStyle = CardStyle.Filled,
    contentPadding: PaddingValues = PaddingValues(Spacing.s18),
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = RegolithTheme.colors
    val base = when (style) {
        CardStyle.Filled -> modifier.background(colors.surface, CardShape).border(1.dp, colors.hairline, CardShape)
        CardStyle.Selected -> modifier.background(colors.surface, CardShape).border(1.5.dp, colors.ink, CardShape)
        CardStyle.Notice -> modifier.background(colors.noticeBg, CardShape).border(1.dp, colors.raised, CardShape)
        CardStyle.Error -> modifier.background(colors.accentTint, CardShape).border(1.dp, colors.accent.copy(alpha = 0.4f), CardShape)
        CardStyle.Empty -> modifier.background(Color(0xFF050505), CardShape).drawBehind {
            drawRoundRect(
                color = colors.raised,
                cornerRadius = CornerRadius(14.dp.toPx()),
                style = Stroke(width = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))),
            )
        }
    }
    Column(modifier = base.padding(contentPadding), content = content)
}

/**
 * The notice card (design: "Couldn't reach media — showing what's saved
 * here"): an 18dp alert glyph in #A0A0A0, the message at 500 13/19 in
 * #EDEDED, optional small print and an action underneath.
 */
@Composable
fun NoticeCard(
    message: String,
    modifier: Modifier = Modifier,
    testTag: String = "notice_card",
    detail: String? = null,
    action: (@Composable () -> Unit)? = null,
) {
    val colors = RegolithTheme.colors
    SurfaceCard(modifier = modifier.testTag(testTag), style = CardStyle.Notice, contentPadding = PaddingValues(Spacing.s12)) {
        Row(verticalAlignment = Alignment.Top) {
            Icon(RegolithIcons.Alert, contentDescription = null, tint = colors.body, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(Spacing.s12))
            Column {
                Text(message, style = TextStyles.notice, color = colors.inkSoft)
                if (detail != null) {
                    Text(detail, style = TextStyles.meta, color = colors.metadata, modifier = Modifier.padding(top = Spacing.s4).testTag("${testTag}_detail"))
                }
                if (action != null) {
                    Spacer(Modifier.size(Spacing.s8))
                    action()
                }
            }
        }
    }
}

/** Convenience for the common "one line of text on a card" default colour. */
val CardInk: Color
    @Composable get() = RegolithTheme.colors.ink
