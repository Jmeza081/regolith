package com.regolith.ui.browse

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.composables.icons.lucide.R as LucideR
import com.regolith.R
import com.regolith.domain.artwork.ExistingArtwork
import com.regolith.domain.transfer.ConflictPolicy
import com.regolith.ui.components.EyebrowAction
import com.regolith.ui.components.ListRow
import com.regolith.ui.components.RegolithSheet
import com.regolith.ui.components.RowAction
import com.regolith.ui.components.RowLeading
import com.regolith.ui.components.RowTrailing
import com.regolith.ui.components.SheetChoice
import com.regolith.ui.components.SurfaceCard
import com.regolith.ui.theme.RegolithTheme
import com.regolith.ui.theme.Spacing
import com.regolith.ui.theme.TextStyles

/**
 * The uploads into this folder, at the top of its list: a row per file with
 * the phone's own thumbnail, where it stands, and its one or two controls —
 * or, once everything went, a single summary row.
 *
 * Browse's own [Section] shape (a muted eyebrow over a card of rows), with
 * the section's action beside the eyebrow the way Library puts "Clear
 * failed" beside its own. The rows do nothing when tapped: a file on its
 * way is not in the library yet, so there is no page to open.
 */
@Composable
internal fun UploadSectionView(
    section: UploadSection,
    onAction: (UploadSectionAction) -> Unit,
    onRetry: (Long) -> Unit,
    onRemove: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RegolithTheme.colors
    Column(modifier.testTag("browse_uploads"), verticalArrangement = Arrangement.spacedBy(Spacing.s8)) {
        EyebrowAction(
            text = section.label,
            action = section.action.label,
            onAction = { onAction(section.action) },
            actionTestTag = "browse_uploads_action",
            // Carrying on is ink; taking things away is the quieter grey.
            // Nothing here is red: the red belongs to a failure's own words.
            actionColor = when (section.action) {
                UploadSectionAction.TRY_NOW, UploadSectionAction.RETRY_ALL -> colors.ink
                UploadSectionAction.CANCEL_ALL, UploadSectionAction.CLEAR -> colors.body
            },
        )
        SurfaceCard(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = Spacing.s12)) {
            section.summary?.let { summary ->
                ListRow(
                    title = summary.title,
                    meta = summary.meta,
                    leading = RowLeading.Picture(summary.thumb, glyphFor(summary.kind)),
                    trailing = RowTrailing.None,
                    compact = true,
                    onClick = {},
                    testTag = "browse_uploads_summary",
                )
            }
            section.rows.forEach { row ->
                ListRow(
                    title = row.name,
                    meta = row.status,
                    leading = RowLeading.Picture(row.thumb, glyphFor(row.kind)),
                    trailing = RowTrailing.None,
                    compact = true,
                    onClick = {},
                    testTag = row.testTag,
                    metaMaxLines = 2,
                    metaColor = if (row.emphatic) colors.body else null,
                    progress = row.progress,
                    progressMuted = row.progressMuted,
                    action = {
                        Row {
                            if (row.canRetry) {
                                RowAction(
                                    LucideR.drawable.lucide_ic_rotate_ccw, "Try ${row.name} again", { onRetry(row.id) },
                                    "${row.testTag}_retry", tint = colors.ink,
                                )
                            }
                            if (row.canRemove) {
                                RowAction(
                                    R.drawable.rg_ic_close, if (row.live) "Cancel ${row.name}" else "Remove ${row.name}",
                                    { onRemove(row.id) }, "${row.testTag}_remove",
                                )
                            }
                        }
                    },
                )
            }
        }
        section.note?.let { note ->
            Text(note, style = TextStyles.meta, color = colors.metadata, modifier = Modifier.testTag("browse_uploads_note"))
        }
    }
}

/**
 * "Upload to Lisbon 2026": where the files come from. Two answers, because
 * the phone keeps two kinds of thing — the gallery, which Android's photo
 * picker shows best and without any permission, and everything else, which
 * only the system's file picker can reach.
 */
@Composable
internal fun UploadSourceSheet(
    folderName: String,
    detail: String?,
    onPhotos: () -> Unit,
    onFiles: () -> Unit,
    onPoster: () -> Unit,
    onDismiss: () -> Unit,
) {
    RegolithSheet(title = "Upload to $folderName", subtitle = detail, onDismiss = onDismiss, testTag = "upload_sheet") {
        SheetChoice(LucideR.drawable.lucide_ic_images, "Photos & videos", "From your gallery", "upload_sheet_photos", onClick = onPhotos)
        SheetChoice(LucideR.drawable.lucide_ic_file, "Files", "Downloads, documents, anything else", "upload_sheet_files", onClick = onFiles)
        // P19: one picture, which becomes the folder's own poster.
        SheetChoice(
            LucideR.drawable.lucide_ic_image_up, "Folder poster", "A picture for this folder's tile, saved as poster.jpg", "upload_sheet_poster",
            onClick = onPoster,
        )
    }
}

/**
 * A poster was picked for a folder that has a picture of its own already
 * (P19). Both are shown — the one the folder has now, as the app draws it,
 * and yours — so the choice is between two things you can see. Rename comes
 * first because it loses nothing; Replace is the one that deletes, so its
 * glyph is the accent, as in [UploadQuestionSheet].
 */
@Composable
internal fun PosterQuestionSheet(
    question: PosterQuestion,
    onAnswer: (ExistingArtwork) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = RegolithTheme.colors
    RegolithSheet(title = "${question.folderName} already has a poster", subtitle = question.subtitle, onDismiss = onDismiss, testTag = "poster_question_sheet") {
        SurfaceCard(
            modifier = Modifier.fillMaxWidth().padding(top = Spacing.s8, bottom = Spacing.s8),
            contentPadding = PaddingValues(horizontal = Spacing.s12),
        ) {
            question.existing.forEachIndexed { index, picture ->
                ListRow(
                    title = picture.name,
                    meta = picture.detail,
                    // The first is the one the folder shows, and the app has that picture to hand.
                    leading = if (index == 0) RowLeading.Poster(question.current, picture.name) else RowLeading.Glyph(LucideR.drawable.lucide_ic_image),
                    trailing = RowTrailing.None,
                    compact = true,
                    onClick = {},
                    testTag = "poster_question_existing",
                )
            }
            ListRow(
                title = "Your picture",
                meta = question.pickedNote,
                leading = RowLeading.Picture(question.picked, LucideR.drawable.lucide_ic_image, poster = true),
                trailing = RowTrailing.None,
                compact = true,
                onClick = {},
                testTag = "poster_question_picked",
            )
        }
        SheetChoice(LucideR.drawable.lucide_ic_copy, question.renameLabel, question.renameNote, "poster_question_rename") {
            onAnswer(ExistingArtwork.KEEP)
        }
        SheetChoice(
            LucideR.drawable.lucide_ic_replace, question.replaceLabel, question.replaceNote, "poster_question_replace", tint = colors.accent,
        ) { onAnswer(ExistingArtwork.REPLACE) }
    }
}

/**
 * Names in the pick are already taken — asked ONCE for all of them, before
 * a byte is sent. The files are listed so the answer is about something
 * concrete; one with the same name and size is the same file and is only
 * listed, greyed, as skipped. Replace is the one choice that destroys
 * something, so its glyph is the accent; Keep both comes first because it
 * loses nothing.
 */
@Composable
internal fun UploadQuestionSheet(
    question: UploadQuestion,
    onAnswer: (ConflictPolicy) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = RegolithTheme.colors
    RegolithSheet(title = "Already in ${question.folderName}", subtitle = question.subtitle, onDismiss = onDismiss, testTag = "upload_conflict_sheet") {
        SurfaceCard(
            modifier = Modifier.fillMaxWidth().padding(top = Spacing.s8, bottom = Spacing.s8),
            contentPadding = PaddingValues(horizontal = Spacing.s12),
        ) {
            question.clashes.forEach { clash ->
                ListRow(
                    title = clash.name,
                    meta = clash.detail,
                    leading = RowLeading.Picture(clash.thumb, glyphFor(clash.kind)),
                    trailing = RowTrailing.None,
                    compact = true,
                    onClick = {},
                    testTag = "upload_conflict_file",
                    metaColor = if (clash.sameFile) null else colors.body,
                )
            }
        }
        SheetChoice(LucideR.drawable.lucide_ic_copy, "Keep both", question.keepBothNote, "upload_conflict_keep_both") { onAnswer(ConflictPolicy.KEEP_BOTH) }
        SheetChoice(LucideR.drawable.lucide_ic_skip_forward, question.skipLabel, question.skipNote, "upload_conflict_skip") { onAnswer(ConflictPolicy.SKIP) }
        SheetChoice(
            LucideR.drawable.lucide_ic_replace, "Replace", question.replaceNote, "upload_conflict_replace", tint = colors.accent,
        ) { onAnswer(ConflictPolicy.REPLACE) }
    }
}

/** What a row shows until its thumbnail arrives, or instead of one. */
internal fun glyphFor(kind: UploadKind): Int = when (kind) {
    UploadKind.VIDEO -> R.drawable.rg_ic_play
    UploadKind.PHOTO -> LucideR.drawable.lucide_ic_image
    UploadKind.FILE -> LucideR.drawable.lucide_ic_file
}
