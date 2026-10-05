# Editing from the Library — investigation

Status: being built (branch `feature/library-browse-parity`). Revised twice
on 2026-10-05 after the owner's corrections (first section), then the owner
answered every open question the same day (last sections).

Progress, in the build order at the end:

- Done: 1, the leaky-pick fix (also on its own branch, `fix/leaky-pick`).
- Done: 3, every folder is a collection.
- Done: 4, the listing half. Browse lists every file. Picking those files
  (the third `FileOpTarget` kind) waits for step 5, which moves the verbs
  out of Browse.

The owner browses through the Library, but renaming, moving, deleting,
downloading and setting posters all live in Browse. Finding the same video
again in the folder tree, just to fix it, is the complaint.

## The model: every folder is a collection

The first draft of this plan treated a folder with a poster as **one
title**, the way a film library does: `Films/Arrival (2016)/` holds one
film. The owner corrected that, twice.

- **A folder and its poster are a container** for a list of related videos.
  `Home Videos` with a poster holds many clips. In the owner's words, a
  poster and a folder are "just containers for a list of correlated files".
- **Each video is its own thing,** with its own companion files: its
  chapters, its subtitles, its own picture.
- **Every folder that holds videos is a collection, even one holding a
  single video.** A collection of one is still a collection, because more
  videos may be added to it later. In the owner's words: "To say that it
  just counts as a single video is incorrect."

Read here as: a folder without a poster is a collection too. It shows the
mosaic made from its videos until it gets one, exactly as today. Tying the
Library's structure to whether a poster exists would make it reshuffle the
moment one is added.

**Browse shows every file; the Library parses.** Also the owner's rule. In
their words, Browse should be "for viewing everything inside a folder as a
file system would": the poster image, subtitles, chapters, everything.
"A collection simply parses that information out", but keeps the editing
tools.

## What the app does today, against that model

- **Most of it already fits.** A folder with several videos is a Collection
  tile with the folder's own poster. Opening it shows every video as its own
  tile, each with its own picture: its basename image, or a frame from it.
- **The exception is the title shortcut.** A folder becomes one tile, its
  **largest** video, when it has videos, no subfolders, and either a year in
  its name or exactly one video (`FolderClassifier`, `FolderKind.TITLE`).
  Both halves are wrong under the model:
  - **The year test is broad.** `Hawaii 2019`, `Christmas 2019` and
    `Wedding (2021)` all pass, so a dated folder of home videos shows as one
    clip and hides the rest.
  - **A one-video folder never appears as a collection** you can open and
    later add to.
- **A lone video borrows its folder's poster.** A folder's `poster.jpg`
  becomes the picture of its only video (`ArtworkCandidates.forFile`,
  `videosInFolder <= 1`). So the video's picture changes the day a second
  video arrives.
- **The player's poster editor writes the folder's `poster.jpg`.**
  - It warns only when the folder holds other videos (`PosterTarget.sharedWithFolder`).
  - Under this model it is always the collection's poster.
- **Companion files are invisible and get left behind.**
  - The companions are the files that share a video's base name:
    `beach.mp4` with `beach.chapters.txt`, `beach.en.srt`, `beach.jpg` and
    `beach.nfo`. The scan records only videos, so neither Browse nor the
    Library shows them.
  - Rename, move and delete take only the `.chapters.txt` along. Rename
    `beach.mp4` and `beach.jpg` no longer matches, so the video loses its own
    picture. Move it and its subtitles stay in the old folder.
- **The player reads no subtitle files.** It only plays tracks inside the
  video file.

## Recommendation, revised

1. **Every folder is a collection; the title shortcut goes.**
   - The classifier stops producing `TITLE`.
   - The Library's tile builder stops collapsing folders. Rows already
     stored as `TITLE` are read as collections, so no database migration is
     needed.
   - Title Detail's "In this collection" list, which only ever applied to
     title folders, goes, because the collection itself now shows its
     videos.
   - Search stops labelling folders "title".
   - The demo library's film folders become collections.
   - The cost: one more tap to reach a film that sits in its own folder.
2. **Posters belong to collections.** A video's tile is made the way every
   other video's is (its own picture if it has one, otherwise a frame from
   it), never from its folder's poster, even when it is the folder's only
   video. Its picture then never changes because a second video arrived.
   - The visible cost: a film alone in its folder shows a frame, not the
     film poster, in Continue watching, search and its own page, unless it
     has its own picture.
   - Pictures already made are rebuilt by bumping `ArtworkStore.GENERATION`.
   - *Decision 2.*
3. **The poster editor sets the collection's poster,** as it already writes
   `poster.jpg`, and always says so ("the poster for Films"). Making a
   video's own picture from a frame could be added beside it later.
   *Decision 3.*
4. **A video and its companions are one unit, everywhere.** Rename, move and
   delete take every companion with the video, in Browse as much as the
   Library. *Decision 1.*
5. **The Library edits what it shows:**

| Tile | Move / Delete | Rename | Download |
|---|---|---|---|
| A collection (any folder of videos, one or many) | the folder and everything in it | the folder | everything under it (as today) |
| A video | the video and its companions | the video, and its companions renamed to match | the video (as today) |

Browse keeps its exact behaviour, except that videos bring their companions
along there too. The folder poster can be set from a collection's own
screen. Upload videos is *decision 5*, and the owner already expects to add
videos to existing collections.

## What already exists

- **Selection.** The Library already multi-selects, through the same
  app-wide `SelectionStore` Browse and Search use. Only Download is offered.
- **The data layer.** `FileOpsRepository` (rename, move, delete,
  createFolder, all by id), `TransferRepository`, `UploadRepository` and
  `PosterRepository` are generic. Title Detail already renames and deletes
  its one file through `FileOpsRepository`. The `.chapters.txt` follow-along
  is the pattern to widen to every companion.
- **The UI pieces.** `MoveToSheet` (with the A–Z rail), `PromptDialog`,
  `ConfirmDialog`, the pill's `SelectionVerb`s, and the pick states of
  `MediaTile` and `ListRow` all live in `ui/components/`.
- **Tests.** `FileOpsRepositoryTest` (25 cases), `MoveToSheetTest`,
  `SelectionChromeTest` and the `Selection` tests.

## What it would take

1. **Fix first: a shipped bug in Browse.** Move and Delete ignore what you
   take back out of a picked folder.
   - `BrowseViewModel.picked()` sends only the picked folders and files. It
     drops the exclusions, which Download honours. So picking a folder,
     un-picking a video inside it and pressing Delete deletes that video
     too.
   - The proposed fix: refuse with a note while a picked folder has
     exclusions, or expand the folder into what is still picked
     (*decision 4*).
   - It is small and ships as its own patch.
2. **Companions travel with their video.** Widen `FileOpsRepository`'s
   chapters follow-along to every file that shares the video's base name,
   for rename, move and delete, with tests. The delete dialog can then say
   "and its 3 companion files". This is small to medium, and Browse benefits
   at once.
3. **Every folder is a collection.**
   - Change the classifier, the Library's tile builder, Title Detail's
     sibling list, Search's label and the demo library.
   - Change the lone-video poster rule in `ArtworkCandidates.forFile` and
     `ArtworkRepository.adoptInFolder`, and bump `ArtworkStore.GENERATION`.
   - Update the poster editor's wording.
   This is small to medium. The artwork change is the part to test with
   care.
4. **Extract the verbs from Browse.** About 330 lines of `BrowseViewModel`
   and 260 of `BrowseScreen` become `FileActions`: a controller each
   ViewModel owns, and one host composable for the dialogs, the move sheet
   and the messages. Web analogy: a `useFileActions()` hook and a
   `<FileActionModals/>` component. Three things get fixed on the way:
   - **Move assumes the folder on screen is the source.** "They're already
     here" and Undo's destination both use it, so Undo gathers a
     cross-folder selection into one folder. Each item needs its own
     origin.
   - **Verbs are offered where they cannot work.** Move, Rename and Delete
     show for the demo library and the phone's own videos, and only fail
     when tried. Gate them up front.
   - **There are no tests.** `BrowseViewModel` has none today; the
     controller gets them.
   This is the largest step.
5. **Give the Library the verbs.** The translator from tile to target is now
   trivial: a collection maps to its folder, and a video to that video, with
   its companions handled by step 2. Three more details:
   - A move cannot cross shares, and the root wall spans them, so offer Move
     only when the picks are on one share.
   - Listen to `ArtworkRepository.replaced`, so tiles redraw after a poster
     change.
   - Show the same four verbs in the pill as Browse.
   This is medium.
6. **Collection extras,** from the collection's own screen: Upload › Folder
   poster, and Add videos if decision 5 says so. Browse's sheets for these
   move to `ui/components/`. This is small.
7. **Docs and checking.**
   - The README already claims "Hold any video — or any folder … Download,
     Move, Rename, Delete", which is true only in Browse.
   - Update NAVIGATION.md, and add an ARCHITECTURE decision row for the
     container model.
   - Verify on the wide AVD against the Samba test share, with companion
     files in place.

## Decisions (made by the owner, 2026-10-05)

Also decided by the owner: every folder that holds videos is a collection,
whatever its name and however many videos it holds, and Browse shows every
file.

1. **Yes:** a video's companions go with it when it is moved, renamed or
   deleted, in Browse and the Library.
2. **Yes:** a video's tile never uses its folder's poster. It is made like
   any other video's, which in practice is a frame from it.
3. **Yes:** the player's poster editor sets the collection's poster, and says
   so.
4. **Yes, refuse:** the leaky-pick fix refuses Move and Delete while a picked
   folder has something un-picked inside it, and says why.
5. **Yes:** Add videos goes on a collection's own screen in the Library.

## Browse shows every file (new)

Today `LibraryRepository.refreshFolder` lists a folder on the share and
keeps only its subfolders and videos. The listing already holds every other
file; it is handed to the artwork and chapter checks and then dropped. So
Browse can show every file without any extra trip to the server.

- **Record the other files too.** Keep them in their own table, filled from
  the same listing, not as `media_files`. Every other screen assumes a
  media file is a video: the Library, Home, Search, Shorts, downloads.
- **Rows.** Browse lists them after the folder's videos, by their real names
  and sizes, each with an icon for its kind: picture, subtitles, chapters,
  info, other.
- **Actions.** They take the same selection actions as any file: rename,
  move, delete, download. `FileOpTarget` gains a third kind for them. A
  companion picked on its own moves alone; a video picked moves with its
  companions.
- **Tapping one.** Tapping a picture could open a preview (a poster is the
  obvious case). Tapping anything else does nothing for now.
- **The Library is unchanged.** It never lists these files.
- **The demo library and the phone's own videos** have no share listing, so
  they show only videos, as today.
- **A fix that comes with it.** Upload › Files already accepts any file, and
  that file has been invisible after upload until now.

## Build order and test builds

1. **The leaky-pick fix**, on its own small branch, released as a patch.
2. **Companions travel with their video.**
3. **Every folder is a collection,** with the lone-video picture change and
   the poster editor's wording.
4. **Browse shows every file.** This brings a test build for the owner: the
   Library looks different (film folders open as collections), and so does
   Browse.
5. **Extract `FileActions` from Browse.**
6. **Give the Library the verbs.**
7. **Collection extras:** the folder poster and Add videos, from a
   collection's screen. This brings a test build, then the merge.
8. **Docs and checking** throughout. The ARCHITECTURE decision row for the
   container model lands with step 3.

## Noticed on the way

- **Subtitle files are ignored.** Loading `beach.en.srt` beside `beach.mp4`
  would be a feature of its own, and it follows naturally from treating
  companions as part of a video.
- **The design's single-title tile goes.** The design draws a title with
  its files as "Arrival · 3 files"; under the container model that is a
  collection showing three videos.
- **NAVIGATION.md** leaves the poster editor out of its pushed-screens
  table.
