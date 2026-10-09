package com.regolith.ui.poster

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.regolith.R
import com.regolith.domain.artwork.Dimensions
import com.regolith.domain.artwork.PosterFraming
import com.regolith.ui.components.PosterCropper
import com.regolith.ui.components.PosterFramingSaver
import com.regolith.ui.components.PosterFramingScaffold
import com.regolith.ui.components.PosterSwapSheet
import com.regolith.ui.components.PrimaryButton
import com.regolith.ui.components.Segment
import com.regolith.ui.components.SegmentedTabs
import com.regolith.ui.components.StrataLoader
import com.regolith.ui.theme.RegolithTheme
import com.regolith.ui.theme.Spacing
import com.regolith.ui.theme.TextStyles

/**
 * Set as poster (the canvas's Poster-Crop): a picture made into its
 * folder's poster, from the lightbox, the album's selection, or the phone
 * (Upload › Collection poster). The picture sits behind the fixed 2:3 box to
 * be pinched and dragged into place — or, with "Use whole picture", shown
 * as it is. Set as poster asks first when the folder has a poster already
 * ([PosterSwapSheet]), and the screen closes itself when the poster is up.
 *
 * The frame of it is the poster editor's ([PosterFramingScaffold]), so the
 * two read as one tool with two sources.
 */
@Composable
fun SetPosterScreen(
    viewModel: SetPosterViewModel,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = RegolithTheme.colors
    var framing by rememberSaveable(stateSaver = PosterFramingSaver) { mutableStateOf(PosterFraming()) }
    val image = remember(state.picture) { state.picture?.asImageBitmap() }

    LaunchedEffect(state.done) { if (state.done) onClose() }

    val save = {
        val picture = state.picture
        if (picture != null) {
            // As in the poster editor: the crop only cares about the box's
            // shape, which is always 2:3.
            val box = PosterFraming.boxIn(PosterFraming.ASPECT * 1000f, 1000f)
            viewModel.save(if (state.whole) null else framing.cropRect(Dimensions(picture.width.toFloat(), picture.height.toFloat()), box))
        }
    }

    PosterFramingScaffold(
        title = "Set as poster",
        subtitle = state.subtitle.ifEmpty { null },
        onClose = onClose,
        modifier = modifier.testTag("set_poster_screen"),
        picture = { covered ->
            if (state.whole) {
                // The whole picture, as it will be: no box, nothing dimmed.
                image?.let {
                    Image(
                        it, contentDescription = state.pictureTitle, contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize().padding(covered).padding(Spacing.s18).testTag("set_poster_whole_picture"),
                    )
                }
            } else {
                PosterCropper(
                    image = image, framing = framing, onFramingChange = { framing = it },
                    contentPadding = covered, modifier = Modifier.fillMaxSize(), testTag = "set_poster_cropper",
                )
            }
        },
        status = {
            if (state.loading) {
                StrataLoader(height = 32.dp, testTag = "set_poster_loading")
            } else if (state.unreadable) {
                Text(
                    "Couldn’t read this picture.",
                    style = TextStyles.notice, color = colors.inkSoft,
                    modifier = Modifier.background(colors.overArt).padding(Spacing.s12).testTag("set_poster_unreadable"),
                )
            }
        },
    ) {
        Text(
            state.hint, style = TextStyles.settingMeta, color = colors.metadata, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().testTag("set_poster_hint"),
        )
        SegmentedTabs(
            segments = listOf(Segment("Crop to 2:3", "set_poster_crop"), Segment("Use whole picture", "set_poster_whole")),
            selected = if (state.whole) 1 else 0,
            onSelect = { viewModel.setWhole(it == 1) },
        )
        state.readOnly?.let { Text(it, style = TextStyles.notice, color = colors.body, modifier = Modifier.testTag("set_poster_read_only")) }
        state.error?.let { Text(it, style = TextStyles.notice, color = colors.accent, modifier = Modifier.testTag("set_poster_error")) }
        PrimaryButton(
            "Set as poster", save, "set_poster_save",
            modifier = Modifier.fillMaxWidth(),
            enabled = state.canSave,
            loading = state.saving && state.swap == null,
            leadingIcon = painterResource(R.drawable.rg_ic_check),
        )
    }

    state.swap?.let { swap ->
        PosterSwapSheet(swap, onConfirm = viewModel::confirmSwap, onDismiss = viewModel::dismissSwap, busy = state.saving)
    }
}
