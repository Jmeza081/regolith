# Editing from the Library — investigation

Status: investigation only (2026-10-04, branch `feature/library-browse-parity`).
Nothing below is built yet.

The owner browses through the Library, but renaming, moving, deleting,
downloading and setting posters all live in Browse. Finding the same film
again in the folder tree, just to fix it, is the complaint.

## What each tab is for

- **The Library is the media.** The design calls it "Everything the scan
  understood, as posters" (§05). Its things are titles and collections. A
  title folder is one title, names are parsed ("Arrival (2016)"), and posters,
  subtitles and other sidecars are hidden. You go there to watch. Web
  analogy: the storefront.
- **Browse is the share.** The design calls it "The share as it actually is
  — folders, filenames, extensions, sizes — for when the media guessed
  wrong" (§07). Web analogy: the admin's file manager.
- **The design draws no editing at all.** §08 says the scan only reads.
  File management came later (P14: "rename, move and delete from Browse and
  the video page"), so the Library was left out by scope, not by a recorded
  decision. Only a code comment stands in for one: Library's selection
  "only knows how to download a pick".

## Recommendation: the same verbs, in the Library's own terms

Yes to editing in the Library. The thing you are looking at should be the
thing you can fix.

It should not be literal parity, though:

- **The Library acts on what it shows: titles and collections.** Browse stays
  the exact file view for everything below that: one file inside a title
  folder, subtitles, images, `.nfo`s, and folders that hold no media.
- **One implementation serves both.** The verbs move out of `BrowseViewModel`
  into a shared controller, so the two tabs cannot drift apart. Two screens
  with copy-pasted logic is the bug CLAUDE.md warns about.

| Verb | Library: a title | Library: a collection | Browse (unchanged) |
|---|---|---|---|
| Download | its main video (as today) | everything under it (as today) | as today |
| Rename | the title folder, whose name the tile shows; a loose video's own name | the folder | file or folder |
| Move | the whole title folder: video, poster, subtitles, other versions | the folder | file or folder |
| Delete | the whole title folder; the dialog says what goes | the folder | file or folder |
| Folder poster | — (a film's poster comes from the player's poster editor) | from the collection's own screen, like Browse's Upload | as today |
| Upload videos | — | open question (below) | as today |

Why a title's verbs act on its folder: the Library's Title tile points at the
**largest video** of a title folder. A file-level move or delete would leave
the folder's `poster.jpg`, subtitles and other versions behind, and only the
`.chapters.txt` sidecar travels with the video today. Deleting the last
video would also leave an empty folder on the share that the Library no
longer shows. Download is different. "Download Arrival" means "give me the
film", one file, not every version in the folder.

## What already exists

- **Selection.** The Library already multi-selects, through the same
  app-wide `SelectionStore` Browse and Search use. Only Download is offered.
- **The data layer.** `FileOpsRepository` (rename, move, delete,
  createFolder, all by id), `TransferRepository`, `UploadRepository` and
  `PosterRepository` are generic. Title Detail already renames and deletes
  its one file through `FileOpsRepository`.
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
     too, and the dialog's count includes it.
   - The proposed fix: while a picked folder has exclusions, refuse Move and
     Delete with a note ("open it and pick what to move"). The alternative is
     to expand the folder into what is still picked.
   - It is small, ships as its own patch, and the Library would otherwise
     inherit the bug.
2. **Extract the verbs from Browse.** That is about 330 lines of
   `BrowseViewModel` and 260 of `BrowseScreen`. They become `FileActions`: a
   controller each ViewModel owns, and one host composable for the dialogs,
   the move sheet and the messages. Web analogy: a `useFileActions()` hook
   and a `<FileActionModals/>` component. Three things get fixed on the way:
   - **Move assumes the folder on screen is the source.** "They're already
     here" and Undo's destination both use the screen's folder, so Undo
     gathers a cross-folder selection into one folder. Each item needs its
     own origin. The Library root has no folder on screen, and a title
     tile's video lives a level down.
   - **Verbs are offered where they cannot work.** Move, Rename and Delete
     show for the demo library and the phone's own videos, and only fail
     when tried ("read-only"). Gate them up front.
   - **There are no tests.** `BrowseViewModel` has none today; the controller
     gets them.
3. **Give the Library the verbs, title-aware.**
   - A Title tile carries its title folder's id; today it carries only the
     video's. The Library's controller maps a picked title to that folder for
     Move, Rename and Delete, and Download keeps the main video.
   - The root wall spans shares, and a move cannot cross shares, since it is
     a server-side rename. Offer Move only when the picks are on one share,
     with a hint otherwise.
   - Listen to `ArtworkRepository.replaced`, as Browse does, so tiles redraw
     after a poster change without leaving the screen.
   - Show the same four verbs in the pill as Browse: Download, Move,
     Rename, Delete.
4. **Collection extras, from the collection's own screen** (`Library(folderId)`),
   the way Browse's Upload acts on the folder on screen. Upload › Folder
   poster belongs here, and perhaps Add videos. Browse's sheets for these
   move to `ui/components/`.
5. **Docs and checking.**
   - The README already says "Hold any video — or any folder … Download,
     Move, Rename, Delete", which is true only in Browse today.
   - Update NAVIGATION.md, and add an ARCHITECTURE decision row.
   - Verify on the wide AVD against the Samba test share.

Sizes: step 1 is small; step 2 is the largest, a refactor of working code;
step 3 is medium; step 4 is small.

## Decisions to make first

1. A title in the Library moves, renames and deletes **as its folder**
   (recommended), rather than as its main video alone.
2. Renaming a title renames **the folder only** (recommended). Its video
   keeps its file name, which Browse or Title Detail can change.
   Alternatively, rename the video to match.
3. The exclusions fix: **refuse** (recommended, simple and safe), or expand
   the folder into what is still picked.
4. Whether **Add videos** belongs in the Library at all, or stays a Browse
   job.

## Noticed on the way

- **Other files in a title folder.** Title Detail's "In this collection"
  rows play the other files. There is no way to open their own page, so a
  title folder's non-main video can only be managed from Browse.
- **Multi-file titles.** The design draws one as "Arrival · 3 files"; the
  Library collapses it to its largest video.
- **NAVIGATION.md** leaves the poster editor out of its pushed-screens
  table.
