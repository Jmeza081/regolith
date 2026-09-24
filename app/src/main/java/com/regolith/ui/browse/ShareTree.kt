package com.regolith.ui.browse

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.regolith.R
import com.regolith.ui.components.Eyebrow
import com.regolith.ui.theme.RegolithTheme
import com.regolith.ui.theme.Spacing
import com.regolith.ui.theme.TextStyles

/**
 * The share tree that stands beside the folder list on a wide window: every
 * enabled share and the folders directly under it, with the one you are in
 * lit. Two levels deep on purpose — it answers "where am I, and how do I get
 * back to the top", which is what costs a back-tap on a phone. Going deeper
 * is the folder list's job.
 *
 * Browse-specific, so it lives here rather than in `ui/components/`.
 */
@Composable
fun ShareTree(
    nodes: List<TreeNode>,
    currentFolderId: Long?,
    onOpen: (folderId: Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.verticalScroll(rememberScrollState()).testTag("browse_tree"),
        verticalArrangement = Arrangement.spacedBy(Spacing.s2),
    ) {
        Eyebrow("Sources", Modifier.padding(start = Spacing.s12, bottom = Spacing.s8), muted = true)
        nodes.forEach { node ->
            TreeRow(node = node, selected = node.folderId == currentFolderId, onOpen = onOpen)
        }
    }
}

/** How much of the width the tree takes beside the list. */
val SHARE_TREE_WIDTH: Dp = 220.dp

/**
 * Below this the tree is dropped and Browse is the list alone: at the point
 * where a detail pane opens beside it there is no longer room for three
 * columns, and the list is the one that has to stay.
 */
val SHARE_TREE_MIN_WIDTH: Dp = 600.dp

@Composable
private fun TreeRow(node: TreeNode, selected: Boolean, onOpen: (folderId: Long) -> Unit) {
    val colors = RegolithTheme.colors
    val ink = if (selected) colors.ink else colors.body
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .background(if (selected) colors.frostBg else Color.Transparent, RoundedCornerShape(10.dp))
            .clickable(interactionSource = null, indication = null) { onOpen(node.folderId) }
            // A child is indented by the glyph's width, so the share it belongs to reads as its parent.
            .padding(start = Spacing.s12 + if (node.depth == 0) 0.dp else 18.dp, end = Spacing.s12)
            .testTag(node.testTag),
    ) {
        Icon(
            painterResource(if (node.isShare) R.drawable.rg_ic_server else R.drawable.rg_ic_browse),
            contentDescription = null,
            tint = if (selected) colors.ink else colors.navIdle,
            modifier = Modifier.size(18.dp),
        )
        Text(
            node.name,
            style = TextStyles.rowLabelSmall,
            color = ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (node.fileCount > 0) {
            Text("${node.fileCount}", style = TextStyles.meta, color = colors.metadata)
        }
    }
}
