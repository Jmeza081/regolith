package com.regolith.ui.search

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.regolith.domain.playback.ChapterFacet
import com.regolith.ui.components.RegolithSheet
import com.regolith.ui.components.SheetOption

/**
 * The moment filter (P12): the chapter names this library repeats, offered
 * as the answers to "which moment?".
 *
 * The list is whatever the library happens to hold — names typed in the
 * chapter editor and names imported from a `.chapters.txt` land in the same
 * table — so it is built fresh every time rather than declared anywhere.
 * "Any moment" is the way out, which is why it sits at the top rather than
 * being a Clear button somewhere else.
 *
 * A film count rides on each row because it is the thing that decides
 * whether a filter is worth tapping.
 */
@Composable
fun MomentFilterSheet(
    facets: List<ChapterFacet>,
    selected: String?,
    onPick: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    RegolithSheet(
        title = "Filter by moment",
        subtitle = if (facets.size == 1) "1 moment appears in more than one film" else "${facets.size} moments appear in more than one film",
        onDismiss = onDismiss,
        testTag = "search_moment_sheet",
    ) {
        // Long enough to scroll on a library with a lot of shared names,
        // capped so the sheet never swallows the screen.
        Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
            SheetOption(
                label = "Any moment",
                selected = selected == null,
                onClick = { onPick(null) },
                testTag = "search_moment_any",
            )
            facets.forEach { facet ->
                SheetOption(
                    label = facet.title,
                    selected = facet.title.equals(selected, ignoreCase = true),
                    onClick = { onPick(facet.title) },
                    testTag = "search_moment_${facet.title.lowercase().replace(' ', '_')}",
                    trailing = "${facet.films} films",
                )
            }
        }
    }
}
