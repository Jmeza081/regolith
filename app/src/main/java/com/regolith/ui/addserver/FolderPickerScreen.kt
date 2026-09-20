package com.regolith.ui.addserver

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.regolith.R
import com.regolith.ui.components.ErrorCard
import com.regolith.ui.components.PrimaryButton
import com.regolith.ui.components.SecondaryButton
import com.regolith.ui.components.Skeleton
import com.regolith.ui.components.SurfaceCard
import com.regolith.ui.components.TopBar
import com.regolith.ui.theme.RegolithTheme
import com.regolith.ui.theme.Spacing
import com.regolith.ui.theme.TextStyles

/**
 * "Choose folders" — one level of a share, reached from a share's chevron
 * on Choose a share, and from its own rows going deeper. Picks can sit at
 * any depth and at different depths from each other: a folder at the top,
 * another three levels down, and nothing in between.
 *
 * Each row is two targets — the box picks, the rest of the row opens (see
 * [FolderRow]) — and an unpicked folder says how many picks are below it,
 * so a deep choice can be found again without opening every folder on the
 * share. A folder already inside a chosen parent shows greyed with its
 * parent named, because picking it would change nothing.
 *
 * Picks write straight away, like the share toggles do; Done only climbs
 * back out. The rows are one card, the same card Browse draws its folders
 * in, so the two screens read as the same file system.
 */
@Composable
fun FolderPickerScreen(
    viewModel: FolderPickerViewModel,
    onBack: () -> Unit,
    onOpen: (relPath: String) -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = RegolithTheme.colors
    Column(modifier.fillMaxSize().navigationBarsPadding().testTag("addserver_folders_screen")) {
        TopBar(title = state.title, onBack = onBack, subtitle = state.breadcrumb, subtitleMuted = true)
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = Spacing.s18), verticalArrangement = Arrangement.spacedBy(Spacing.s12)) {
            item {
                // What a pick means right now, said before the list rather
                // than discovered after: a first pick NARROWS a share that
                // was in the library whole.
                Text(
                    when {
                        state.wholeShare -> "The whole share is in your library. Tick folders to keep only those; open one to look inside."
                        state.chosenCount == 0 -> "Tick the folders to add to your library, at any depth. Open one to look inside."
                        else -> "${state.chosenCount} folder" + (if (state.chosenCount == 1) "" else "s") + " chosen across this share."
                    },
                    style = TextStyles.meta12, color = colors.metadata,
                    modifier = Modifier.testTag("addserver_folders_note"),
                )
            }
            when {
                state.loading -> item {
                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.s8)) {
                        repeat(5) { Skeleton(Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(12.dp)) }
                    }
                }
                state.error != null -> item {
                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.s12)) {
                        ErrorCard(message = state.error!!, detail = state.errorDetail, testTag = "addserver_folders_error")
                        SecondaryButton(text = "Try again", onClick = viewModel::load, compact = true, testTag = "addserver_folders_retry")
                    }
                }
                state.folders.isEmpty() -> item {
                    Text("No folders in here — only files.", style = TextStyles.meta12, color = colors.metadata, modifier = Modifier.testTag("addserver_folders_empty"))
                }
                else -> item {
                    SurfaceCard(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = Spacing.s12)) {
                        state.folders.forEach { folder ->
                            FolderRow(
                                folder = folder,
                                onToggle = { viewModel.toggle(folder) },
                                onOpen = { onOpen(folder.relPath) },
                            )
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(Spacing.s12))
        PrimaryButton(text = "Done", onClick = onDone, testTag = "addserver_folders_done", modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.s18))
        Spacer(Modifier.height(Spacing.s18))
    }
}

/**
 * One folder, two targets: the box at the start picks it, everything else
 * opens it.
 *
 * That split is the whole screen. With the row itself picking, walking down
 * to a folder three levels in meant selecting every folder on the way —
 * and a selected folder swallows everything under it, so the first tap
 * made the rest unreachable and the picker looked like it only worked at
 * the top. Opening is the common act and gets the big target; picking is
 * the deliberate one and gets a box that looks like what it is.
 */
@Composable
private fun FolderRow(folder: FolderChoice, onToggle: () -> Unit, onOpen: () -> Unit) {
    val colors = RegolithTheme.colors
    val covered = folder.includedBy != null
    Row(
        Modifier.fillMaxWidth().defaultMinSize(minHeight = 56.dp).padding(vertical = Spacing.s8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Three looks, two channels each: a chosen folder is a white box with
        // a dark check; one covered by its parent is a grey box with a grey
        // check (in, but not by its own doing); the rest show a folder glyph.
        Box(
            Modifier.size(38.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(if (folder.selected) colors.ink else colors.disabledBg)
                .then(if (covered) Modifier.border(1.dp, colors.hairline, RoundedCornerShape(11.dp)) else Modifier)
                .clickable(interactionSource = null, indication = null, enabled = !covered, onClick = onToggle)
                .testTag("addserver_folder_${folder.name}"),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painterResource(if (folder.selected || covered) R.drawable.rg_ic_check else R.drawable.rg_ic_folder_small),
                contentDescription = if (folder.selected) "${folder.name}, chosen" else "Choose ${folder.name}",
                tint = if (folder.selected) colors.ground else colors.metadata,
                modifier = Modifier.size(18.dp),
            )
        }
        Spacer(Modifier.width(Spacing.s12))
        Row(
            Modifier.weight(1f)
                .clickable(interactionSource = null, indication = null, onClick = onOpen)
                .testTag("addserver_folder_open_${folder.name}"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.s2)) {
                Text(folder.name, style = TextStyles.rowLabel, color = if (covered) colors.body else colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    when {
                        covered -> "Inside ${folder.includedBy!!.substringAfterLast('/')}, already chosen"
                        folder.selected -> "chosen"
                        // The signpost down to a deep pick. Without it an
                        // unpicked folder looks like an empty branch.
                        folder.chosenInside == 1 -> "1 chosen inside"
                        folder.chosenInside > 1 -> "${folder.chosenInside} chosen inside"
                        else -> "folder"
                    },
                    style = TextStyles.meta12,
                    color = if (folder.chosenInside > 0 && !folder.selected) colors.body else colors.metadata,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                painterResource(R.drawable.rg_ic_chevron_right),
                contentDescription = "Open ${folder.name}",
                tint = colors.metadata,
                modifier = Modifier.size(17.dp),
            )
        }
    }
}
