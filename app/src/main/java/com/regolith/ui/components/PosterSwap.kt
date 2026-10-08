package com.regolith.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.R as LucideR
import com.regolith.domain.artwork.ArtworkKind
import com.regolith.domain.artwork.ArtworkOwner
import com.regolith.domain.artwork.ArtworkRequest
import com.regolith.ui.theme.RegolithTheme
import com.regolith.ui.theme.Spacing
import com.regolith.ui.theme.TextStyles

/** How a new poster is made, for the words of the sheet that asks first ([PosterSwap]). */
enum class PosterMaking {
    /** Cut 2:3 out of a picture on the share, which stays as it is. */
    CROP,

    /** A picture on the share, renamed to be the poster (decision 17). */
    RENAMED,

    /** A JPEG copy of a picture on the share that is no poster as it is: a HEIC, or over 8 MB. */
    COPY,

    /** Cut out of a film's frame, in the poster editor. */
    FRAME,

    /** A picture from the phone, cut 2:3 (Upload › Collection poster). */
    PHONE_CROP,

    /** A picture from the phone, whole. */
    PHONE_WHOLE,
}

/** A picture acting as the folder's artwork now, and the dated name it is kept under (`FolderPoster.keptNames`). */
data class KeptPicture(val name: String, val keptName: String)

/**
 * What setting a poster is about to do, for the sheet that asks first (the
 * canvas's Poster-Confirm): the poster the folder has now and the name it is
 * kept under, beside the new one. Only asked when there IS a poster to keep;
 * a folder without one simply gets the new poster.
 *
 * The pictures being kept are the reason to ask at all — nothing is lost,
 * but the folder changes in two places at once — so the sheet shows them as
 * pictures, not names alone.
 *
 * @property current the folder's poster as the app draws it now.
 * @property kept the pictures renamed out of the way, the one the folder wears first.
 * @property next the new poster as it will look: the crop, the frame or the picture.
 * @property nextName what the new poster is called on the share, "poster.jpg".
 * @property source what it is made from: a picture's title ("IMG_4821") or a film's; unused for a picture from the phone.
 */
data class PosterSwap(
    val folderName: String,
    val current: ArtworkRequest,
    val kept: List<KeptPicture>,
    val next: ImageBitmap?,
    val nextName: String,
    val making: PosterMaking,
    val source: String,
) {
    /** The line under the new poster: "poster.jpg, your crop". */
    val nextNote: String
        get() = "$nextName, " + when (making) {
            PosterMaking.CROP, PosterMaking.PHONE_CROP -> "your crop"
            PosterMaking.RENAMED -> "$source renamed"
            PosterMaking.COPY -> "a copy of $source"
            PosterMaking.FRAME -> "this frame"
            PosterMaking.PHONE_WHOLE -> "your picture"
        }

    /** "+ 1 more" under the kept poster when the folder had several pictures of its own. */
    val moreKept: String? get() = (kept.size - 1).takeIf { it > 0 }?.let { "+ $it more kept" }

    /** The sentence under the two pictures: what becomes the poster, and what happens to the one there now. */
    val body: String
        get() {
            val what = when (making) {
                PosterMaking.CROP -> "$folderName’s poster becomes your crop of $source, and the picture itself stays as it is."
                PosterMaking.RENAMED -> "$source becomes $folderName’s poster, renamed $nextName."
                PosterMaking.COPY -> "$folderName’s poster becomes a JPEG copy of $source, sized for a poster, and the picture itself stays as it is."
                PosterMaking.FRAME -> "$folderName’s poster becomes this frame of $source."
                PosterMaking.PHONE_CROP -> "$folderName’s poster becomes your crop of the picture from your phone."
                PosterMaking.PHONE_WHOLE -> "$folderName’s poster becomes the picture from your phone, saved as $nextName."
            }
            val keptPart = if (kept.size == 1) {
                "The poster there now is kept, renamed, and stays under Images."
            } else {
                "The ${kept.size} pictures acting as its poster now are kept, renamed, and stay under Images."
            }
            return "$what $keptPart"
        }

    companion object {
        /** The swap for [folderId], from the pictures [kept] there (old name to kept name, the worn one first). */
        fun of(
            folderId: Long,
            folderName: String,
            kept: Map<String, String>,
            next: ImageBitmap?,
            nextName: String,
            making: PosterMaking,
            source: String,
        ) = PosterSwap(
            folderName = folderName,
            current = ArtworkRequest(ArtworkOwner.Folder(folderId), ArtworkKind.POSTER),
            kept = kept.map { (name, keptName) -> KeptPicture(name, keptName) },
            next = next,
            nextName = nextName,
            making = making,
            source = source,
        )
    }
}

/**
 * "Set as poster?" (the canvas's Poster-Confirm): the poster there now,
 * "KEPT AS poster (8 Oct).jpg", an arrow, and the new one, "THE POSTER
 * poster.jpg, your crop", over a sentence saying what happens, then Set as
 * poster and Cancel. Used wherever a poster is made: from a picture on the
 * share, one from the phone, and a film's frame.
 *
 * [busy] while the poster goes up: the button shows it, and taps wait.
 */
@Composable
fun PosterSwapSheet(swap: PosterSwap, onConfirm: () -> Unit, onDismiss: () -> Unit, busy: Boolean = false) {
    val colors = RegolithTheme.colors
    RegolithSheet(title = "Set as poster?", onDismiss = onDismiss, testTag = "poster_swap_sheet") {
        Row(
            Modifier.fillMaxWidth().padding(top = Spacing.s12, bottom = Spacing.s18),
            horizontalArrangement = Arrangement.spacedBy(Spacing.s8),
            // The pictures sit level whatever the names under them wrap to.
            verticalAlignment = Alignment.Top,
        ) {
            SwapSide(
                label = "Kept as",
                name = swap.kept.firstOrNull()?.keptName.orEmpty(),
                more = swap.moreKept,
                testTag = "poster_swap_kept",
                modifier = Modifier.weight(1f),
            ) {
                ArtworkImage(swap.current, Modifier.fillMaxSize(), fallbackLabel = swap.folderName)
            }
            Icon(
                painterResource(LucideR.drawable.lucide_ic_arrow_right), contentDescription = null,
                tint = colors.metadata, modifier = Modifier.padding(top = SIDE_PICTURE * 0.75f - 8.dp).size(16.dp),
            )
            SwapSide(label = "The poster", name = swap.nextNote, ringed = true, testTag = "poster_swap_next", modifier = Modifier.weight(1f)) {
                swap.next?.let { Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
            }
        }
        Text(swap.body, style = TextStyles.body, color = colors.body, modifier = Modifier.testTag("poster_swap_body"))
        PrimaryButton(
            "Set as poster", onConfirm, "poster_swap_confirm",
            modifier = Modifier.fillMaxWidth().padding(top = Spacing.s18),
            loading = busy,
        )
        SecondaryButton("Cancel", onDismiss, "poster_swap_cancel", Modifier.fillMaxWidth().padding(top = Spacing.s8, bottom = Spacing.s8))
    }
}

/** One side of the swap: a 2:3 picture with its eyebrow and name under it. The new poster is [ringed]. */
@Composable
private fun SwapSide(
    label: String,
    name: String,
    testTag: String,
    modifier: Modifier = Modifier,
    more: String? = null,
    ringed: Boolean = false,
    picture: @Composable () -> Unit,
) {
    val colors = RegolithTheme.colors
    val shape = RoundedCornerShape(8.dp)
    Column(modifier.testTag(testTag), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .width(SIDE_PICTURE)
                .aspectRatio(2f / 3f)
                .then(if (ringed) Modifier.border(1.5.dp, colors.ink, shape) else Modifier)
                .clip(shape)
                .background(colors.surface),
        ) { picture() }
        Eyebrow(label, Modifier.padding(top = Spacing.s8), muted = true)
        Text(name, style = TextStyles.notice, color = colors.ink, textAlign = TextAlign.Center, modifier = Modifier.padding(top = Spacing.s4))
        if (more != null) Text(more, style = TextStyles.meta12, color = colors.metadata, modifier = Modifier.padding(top = Spacing.s4))
    }
}

/** The pictures themselves, about the board's size on the phone. */
private val SIDE_PICTURE = 76.dp
