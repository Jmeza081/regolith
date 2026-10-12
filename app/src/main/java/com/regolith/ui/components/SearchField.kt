package com.regolith.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.regolith.R
import com.regolith.ui.theme.PillShape
import com.regolith.ui.theme.RegolithTheme
import com.regolith.ui.theme.Spacing
import com.regolith.ui.theme.TextStyles
import com.regolith.ui.theme.designSp
import com.regolith.ui.theme.scaledDp

/**
 * The search field (design section 06, Search): a 44dp pill with a 1dp
 * border, a magnifier, [placeholder] until something is typed, and a clear
 * button once it is. Search's own field, and the move sheet's for finding a
 * folder anywhere on the share.
 *
 * [query] belongs to the caller. Keep it somewhere that changes the moment
 * the text does (a ViewModel's StateFlow set synchronously, as both callers
 * do, or local state), never something updated later: a field that lags
 * behind the keyboard drops letters typed quickly.
 *
 * [containerColor] is the pill's fill: the surface colour on a screen's
 * dark ground, where it stands out by itself; something else on a sheet,
 * which is already the surface colour. [testTag] marks the text field and
 * [clearTestTag] the clear button.
 */
@Composable
fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String,
    testTag: String,
    modifier: Modifier = Modifier,
    clearTestTag: String = "${testTag}_clear",
    containerColor: Color = RegolithTheme.colors.surface,
    focusRequester: FocusRequester? = null,
    /** The keyboard's search key. */
    onSearch: () -> Unit = {},
) {
    val colors = RegolithTheme.colors
    val textStyle = TextStyles.body.copy(fontSize = 14.designSp(), lineHeight = 14.designSp())
    Row(
        modifier.height(44.scaledDp()).clip(PillShape).background(containerColor).border(1.dp, colors.raised, PillShape).padding(horizontal = Spacing.s12),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(R.drawable.rg_ic_search), contentDescription = null, tint = colors.body, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(Spacing.s8))
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            singleLine = true,
            textStyle = textStyle.copy(color = colors.ink),
            cursorBrush = SolidColor(colors.accent),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSearch() }),
            decorationBox = { inner ->
                Box {
                    if (query.isEmpty()) Text(placeholder, style = textStyle, color = colors.metadata, maxLines = 1)
                    inner()
                }
            },
            modifier = Modifier.weight(1f)
                .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                .testTag(testTag),
        )
        if (query.isNotEmpty()) {
            Spacer(Modifier.width(Spacing.s8))
            Box(
                Modifier.size(20.dp).background(colors.raised, PillShape).clickable(interactionSource = null, indication = null) { onQueryChange("") }.testTag(clearTestTag),
                contentAlignment = Alignment.Center,
            ) {
                Icon(painterResource(R.drawable.rg_ic_close_small), contentDescription = "Clear", tint = colors.ink, modifier = Modifier.size(11.dp))
            }
        }
    }
}

/**
 * [text] with what [query] matched in [accent]: the first occurrence of each
 * typed word, ignoring case. For a search's results, so a row shows why it
 * is there — in its meta line as well as its title, since a row can be
 * there for its folder or its path.
 */
fun highlightMatches(text: String, query: String, accent: Color): AnnotatedString {
    val words = query.split(Regex("""[\s.\-_/]+""")).filter { it.isNotEmpty() }
    if (words.isEmpty()) return AnnotatedString(text)
    val ranges = words.mapNotNull { w ->
        val i = text.indexOf(w, ignoreCase = true)
        if (i < 0) null else i until i + w.length
    }.sortedBy { it.first }
    return buildAnnotatedString {
        var cursor = 0
        for (r in ranges) {
            if (r.first < cursor) continue
            append(text.substring(cursor, r.first))
            withStyle(SpanStyle(color = accent)) { append(text.substring(r.first, r.last + 1)) }
            cursor = r.last + 1
        }
        append(text.substring(cursor))
    }
}
