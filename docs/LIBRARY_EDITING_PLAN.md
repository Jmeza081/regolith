# Editing from the Library — investigation

Status: investigation only (branch `feature/library-browse-parity`).
Nothing below is built yet. Revised 2026-10-05 after the owner's
correction; see the first section.

The owner browses through the Library, but renaming, moving, deleting,
downloading and setting posters all live in Browse. Finding the same video
again in the folder tree, just to fix it, is the complaint.

## The model: a folder is a container

The first draft of this plan treated a folder with a poster as **one
title**, the way a film library does: `Films/Arrival (2016)/` holds one
film. The owner corrected that.

- **A folder and its poster are a container** for a list of related videos.
  `Home Videos` with a poster holds many clips.
- **Each video is its own thing,** with its own companion files: its
  chapters, its subtitles, its own picture.
- **No single video "is" the folder.** Tying the folder's poster to one of
  its videos is the wrong assumption.

Regolith is a player for whatever lives on the share, much of it personal.
The film-library case is the special one, not the norm.

## What the app does today, against that model

- **Most of it already fits.** A folder with several videos is a Collection
  tile with the folder's own poster. Opening it shows every video as its own
  tile, each with its own picture: its basename image, or a frame from it.
- **The exception is the title shortcut.** A folder becomes one tile, its
  **largest** video, when it has videos, no subfolders, and either a year in
  its name or exactly one video (`FolderClassifier`, `FolderKind.TITLE`).
  - The year test is broad. `Hawaii 2019`, `Christmas 2019` and
    `Wedding (2021)` all pass.
  - So a dated folder of home videos shows as one clip. The others are
    reachable only from that clip's detail page ("In this collection", which
    only plays them) or from Browse.
- **Companion files are invisible and get left behind.**
  - The companions are the files that share a video's base name:
    `beach.mp4` with `beach.chapters.txt`, `beach.en.srt`, `beach.jpg` and
    `beach.nfo`. The scan records only videos, so neither Browse nor the
    Library shows them.
  - Rename, move and delete take only the `.chapters.txt` along. Rename
    `beach.mp4` and `beach.jpg` no longer matches, so the video loses its own
    picture. Move it and its subtitles stay in the old folder.
- **The player reads no subtitle files.** It only plays tracks inside the
  video file, so `beach.en.srt` is ignored today.

## Recommendation, revised

1. **A folder is one tile only when it holds exactly one video.** Any
   folder with several videos is a collection, whatever its name says.
   - Movie folders that hold two copies (4K and HD) or extras become small
     collections that show each file. That is honest, if a little less
     tidy for a film library.
2. **A video and its companions are one unit, everywhere.** Rename, move and
   delete take every companion with the video, in Browse as much as the
   Library. Nobody can manage companions by hand, because nothing shows
   them, so they have to travel by themselves.
3. **The Library edits what it shows:**

| Tile | Move / Delete | Rename | Download |
|---|---|---|---|
| A collection (a folder of videos) | the folder and everything in it | the folder | everything under it (as today) |
| A video | the video and its companions | the video, and its companions renamed to match | the video (as today) |
| A one-video folder (shown as its video) | the folder: it is that video's container | the folder, whose name the tile shows | the video (as today) |

Browse keeps its exact behaviour, except that videos bring their companions
along there too. The folder poster can be set from a collection's own
screen. Upload videos is an open question (below).

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
     exclusions, or expand the folder into what is still picked. It is
     small and ships as its own patch.
2. **Companions travel with their video.** Widen `FileOpsRepository`'s
   chapters follow-along to every file that shares the video's base name,
   for rename, move and delete, with tests. The delete dialog can then say
   "and its 3 companion files". This is small to medium, and Browse benefits
   at once.
3. **Narrow the title shortcut** to exactly one video.
   - Change the classifier, and have the Library's tile builder apply the
     same rule straight away, so folders already scanned change without a
     database migration.
   - Title Detail's "In this collection" list then never applies, since a
     one-video folder has no other videos, and can go.
   - This is small.
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
5. **Give the Library the verbs.** The translator from tile to target is
   now small:
   - a collection maps to its folder;
   - a video maps to that video, with its companions handled by step 2;
   - a one-video folder maps to its folder.
   Three more details:
   - A move cannot cross shares, and the root wall spans them, so offer Move
     only when the picks are on one share.
   - Listen to `ArtworkRepository.replaced`, so tiles redraw after a poster
     change.
   - Show the same four verbs in the pill as Browse.
   This is medium.
6. **Collection extras,** from the collection's own screen: Upload › Folder
   poster, and perhaps Add videos. Browse's sheets for these move to
   `ui/components/`. This is small.
7. **Docs and checking.**
   - The README already claims "Hold any video — or any folder … Download,
     Move, Rename, Delete", which is true only in Browse.
   - Update NAVIGATION.md, and add an ARCHITECTURE decision row.
   - Verify on the wide AVD against the Samba test share, with companion
     files in place.

## Decisions to make first

1. A folder with several videos and a year in its name (`Hawaii 2019`)
   **shows as a collection** (recommended), not as one tile.
2. A folder with exactly one video **keeps showing as that video**
   (recommended), not as a box you open.
3. A video's **companions go with it** when it is moved, renamed or deleted,
   in Browse and the Library (recommended).
4. The exclusions fix: **refuse** (recommended, simple and safe), or expand
   the folder into what is still picked.
5. Whether **Add videos** belongs in the Library, or stays a Browse job.

## Noticed on the way

- **Subtitle files are ignored.** Loading `beach.en.srt` beside `beach.mp4`
  would be a feature of its own, and it follows naturally from treating
  companions as part of a video.
- **Multi-file titles.** The design draws one as "Arrival · 3 files". With
  decision 1, that folder becomes a collection showing its three files.
- **NAVIGATION.md** leaves the poster editor out of its pushed-screens
  table.
