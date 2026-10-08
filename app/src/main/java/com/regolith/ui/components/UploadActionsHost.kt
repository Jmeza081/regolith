package com.regolith.ui.components

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.regolith.ui.util.UploadActions

/**
 * Everything [UploadActions] puts on screen except the section itself: the
 * "Upload to…" sheet, the system pickers it opens, and the question asked
 * when a name is already taken. The screen draws `UploadSectionView` in its
 * own list, where the files are going.
 *
 * [sources] are the sheet's choices: Browse offers the gallery, any file and
 * a poster; a Library collection offers the gallery, its pictures and
 * videos as files, and a poster. [posterNoun] names what the poster is for,
 * and [note] sits under the choices. A picture picked to be the poster goes
 * to [onPosterPicked] — Set as poster, to be framed 2:3 before anything is
 * sent. [onSendingAway] runs just before a picker opens: the picker is
 * another app's screen, so Regolith goes to the background, and the app
 * lock needs to know the trip back is one it sent the user on.
 */
@Composable
fun UploadActionsHost(
    actions: UploadActions,
    title: String,
    detail: String?,
    sources: List<UploadSource>,
    posterNoun: String,
    onSendingAway: () -> Unit,
    onPosterPicked: (uri: String) -> Unit,
    note: String? = null,
) {
    val state by actions.state.collectAsStateWithLifecycle()

    // The system pickers. `rememberLauncherForActivityResult` is the Compose
    // spelling of registerForActivityResult: the result comes back to this
    // spot in the composition, even if the Activity was recreated while the
    // picker was open. None needs a permission: the pickers are the system's,
    // and they hand back only what was chosen.
    val galleryPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
        actions.onPicked(uris.map { it.toString() })
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        actions.onPicked(uris.map { it.toString() })
    }
    // One picture, to be the folder's poster (P19): images only, and one.
    val posterPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) onPosterPicked(uri.toString())
    }

    if (state.sheetOpen) {
        UploadSourceSheet(
            title = title,
            detail = detail,
            sources = sources,
            posterNoun = posterNoun,
            note = note,
            onPick = { source ->
                actions.dismissSheet()
                onSendingAway()
                when (source) {
                    UploadSource.GALLERY -> galleryPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                    UploadSource.GALLERY_VIDEOS -> galleryPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
                    UploadSource.FILES -> filePicker.launch(arrayOf("*/*"))
                    UploadSource.MEDIA_FILES -> filePicker.launch(arrayOf("image/*", "video/*"))
                    UploadSource.VIDEO_FILES -> filePicker.launch(arrayOf("video/*"))
                    UploadSource.POSTER -> posterPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }
            },
            onDismiss = actions::dismissSheet,
        )
    }
    state.question?.let { question ->
        UploadQuestionSheet(question, onAnswer = actions::answerUploadQuestion, onDismiss = actions::dismissUploadQuestion)
    }
}
