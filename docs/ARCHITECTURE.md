# Architecture

How Regolith is put together, and the decisions that are deliberately hard
to reverse. Written for a web developer learning Android; Android terms are
defined the first time they matter.

## Layers

```
ui/        Compose screens + reusable components. Reads state, sends events.
           Never touches data/ directly.
   ↕ one StateFlow<UiState> per screen, events up
ViewModel  Per-screen state holder that survives rotation (≈ a store/hook).
   ↕
domain/    Pure Kotlin: models, use cases, parse rules, the SmbGateway
           interface. No Android imports, so it is trivially unit-testable.
   ↕
data/      Implementations: jcifs-ng SMB client, Room database, DataStore
           preferences, credential store, the artwork pipeline (data/artwork:
           resolver, on-disk store, frame source, Coil fetcher), the library
           scan (data/scan) and the downloads (data/transfer: worker,
           scheduler seam, repository, on-disk store). Only place
           third-party IO lives.
player/    Media3 (ExoPlayer) session, the SMB DataSource, the container
           probe, scrub thumbnails.
di/        Hilt modules wiring the above together.
```

Single Gradle module (`:app`) until there is a real reason to split.

## Guardrails (fixed early, never reopened)

These are the decisions that would be expensive to change later. Each has a
short "why"; see the plan for the alternatives rejected.

- **G1. SMB behind `domain/smb/SmbGateway` + `SeekableByteSource`.**
  `data/smb/JcifsGateway.kt` is the only file that imports `jcifs.*`.
  jcifs-ng has the random-access file reads ExoPlayer needs; if a NAS
  requires SMB3 encryption, `org.codelibs:jcifs` is API-compatible and the
  interface makes it a one-file swap.
- **G2. The player reads through a custom Media3 `DataSource`, addressed by
  a credential-free app URI** (`regolith://file/{id}`) that a resolver maps
  to SMB or a local download. The player never knows where bytes come from.
- **G3. Room is the single source of truth, including background progress.**
  Files are keyed by `(shareId, relPath)`; rescans mark missing, never
  delete. Scan and transfer progress are rows observed as Flows, so every
  screen shows the same live state and it survives process death.
- **G4. `PlaybackSession` (ExoPlayer) is a Hilt singleton outside the UI**,
  so picture-in-picture, cast and a media session can be added later
  without touching screens.
- **G5. Artwork lives in an app-owned directory + `artwork` table.** Coil is
  only the view layer; its disk cache is not used because generated frames
  are expensive and must survive eviction for offline states.
- **G6. Credentials on the Android Keystore from day one** (AES-GCM
  ciphertext in DataStore, key in the Keystore). `security-crypto` is
  deprecated. The `servers` table has no password column.
- **G7. Navigation 3, one back stack, one nav pill.** See `NAVIGATION.md`.
- **G8. Design tokens mapped onto Material 3 once** in `ui/theme/`, plus
  `RegolithTheme.colors` for tokens M3 lacks. Fonts are bundled. Every
  interactive element carries a `testTag`; the root sets
  `testTagsAsResourceId` so argent/uiautomator can see them.
- **G9. Package layout as in `CLAUDE.md`; `domain/` has zero Android imports.**
- **G10. One back stack on every screen size; wide layouts are scenes and
  panes, never a second navigation structure.** A foldable's inner display
  or a tablet renders Library + Title Detail side by side from the same
  `[…, Library, TitleDetail]` stack (Navigation 3 scene strategies), so the
  phone path stays the tested one. Layouts key on `ui/adaptive/WindowShape`
  (window width class + fold posture), never on the device model. See
  `FOLDABLE_PLAN.md`.

## Data flow, Phase 1 (connect → browse → play)

```
ManualEntryScreen ─▶ AddServerViewModel ─▶ SourceRepository.connect()
                                              ├─ SmbGateway.listShares()   (jcifs-ng, IO thread)
                                              ├─ Room: servers, shares      (only after the server answered)
                                              └─ CredentialStore            (Keystore-encrypted password, if saved)

BrowseScreen ─▶ BrowseViewModel ─▶ LibraryRepository.observeContents()   Room Flows: folders + media_files + progress
                                └▶ LibraryRepository.refreshFolder()     SmbGateway.list() → upsert by (shareId, relPath), mark missing

PlayerScreen ─▶ PlayerViewModel ─▶ PlaybackSession (singleton ExoPlayer)
                                      └─ MediaItem(regolith://file/{id})
                                           └─ DefaultDataSource → SmbDataSource → MediaUriResolver → SmbGateway.open()
                                              (file:// would be handled by DefaultDataSource itself: Phase 5 downloads)
```

Threading rules worth knowing as a web developer: Room and jcifs-ng may not
be touched on the main thread (suspend DAOs and `Dispatchers.IO` handle it);
ExoPlayer may ONLY be touched on the main thread (`PlaybackSession` runs its
ticker on `Dispatchers.Main`). `SmbDataSource` runs on ExoPlayer's own loader
thread and therefore uses the blocking DAO variants.

## Player (Phase 2)

```
PlayerScreen ── gestures (Modifier.playerGestures) ── SeekStacker (pure)      double-tap seek, stacking taps
             ── PlayerSystemControls                                        brightness (window override), media volume
             ── PlayerViewModel ── brightness (StateFlow; survives every layout change, reset once on leaving)
                                ── transfer (TransferRepository.observeForFile; the download pill)
                                ── PlaybackSession ── ExoPlayer (StateFlow: rebuilt on HW/SW switch)
                                                   ── AbLoop (pure)          A–B span, ±0.5 s nudges, restart at B
                                                   ── VideoInfo (pure)       "4K · HDR · HEVC" chips from the selected tracks
                                                   ── ScrubThumbnails        Phase 3; the scrubber already reports the live fraction
```

Landscape is immersive and follows the phone's rotation (`SCREEN_ORIENTATION_SENSOR`);
portrait keeps the system bars and puts title, chips, pills and "next in this folder"
under the picture. Sheets are a side sheet in landscape and a bottom sheet in portrait,
and they are UI state, not routes.

## Artwork and scrub thumbnails (Phase 3)

```
MediaTile ── AsyncImage(ArtworkRequest(owner, kind)) ── Coil ImageLoader (memory cache only)
                └─ ArtworkFetcher ── ArtworkRepository.resolve()
                                        ├─ cached row + file in filesDir/artwork/{owner}/{id}/{kind}.jpg?  → serve
                                        ├─ 1. sidecar  poster/folder/cover/thumb.{jpg,jpeg,png,webp}   (title folders and folders)
                                        ├─ 2. basename Arrival.2016.mkv → Arrival.2016.jpg               ArtworkCandidates (pure)
                                        ├─ 3. embedded MP4 covr                                          ┐ FrameSource
                                        ├─ 4. frame at the MIDPOINT of the runtime                       ┘ (Media3 FrameExtractor; retriever as fallback)
                                        ├─ 4b. folders only: 2×2 mosaic of frames from the videos inside
                                        └─ 5. placeholder row (wedge + filename; expires after a day)
                                        → writes BOTH kinds (500×750 poster, 320×180 thumb) + `artwork` rows

TitleDetail ── MediaProbe (Media3 MetadataRetriever through SmbDataSource) ── media_files probe columns
PlayerScreen ── Chapters pill ── PlaybackState.chapters ── user_chapters (P9) › ChapterRepository ── ChapterParser (pure) › ChapterMarks.evenly
PlayerScreen ── Scrubber.onScrub(ms) ── PlayerViewModel ── ScrubThumbnails.request(ms)        the finger: conflated, newest wins
PlayerScreen ── Chapters / filmstrip ── PlayerViewModel ── ScrubThumbnails.requestAll(list)   a wall: unlimited, nothing dropped
                                                             └─ OnDemandScrubThumbnails: one FrameSource, 10 s buckets, LRU of 40 (FrameIndex, pure)
```

Two things a web developer would not guess: a folder listing is what
decides steps 1 and 2 (the listing is cached for five minutes so a grid of
twenty tiles costs one SMB list), and a loose file in a folder with other
videos does NOT take that folder's `poster.jpg` (the design shows
`Films/Hard.Boiled.1992.mp4` getting a frame grab beside `Films/poster.jpg`;
the sidecar belongs to the collection tile).

## Library scan, parsing, search (Phase 4)

```
Share picker "Scan N shares" / Settings "Scan all" / Home pull-to-refresh
   └─ ScanRepository.enqueue(shareId)   unique WorkManager job per share
        └─ ScanWorker (foreground, dataSync)  breadth-first walk of the share
             └─ LibraryRepository.refreshFolder(folderId) per folder
                  ├─ upsert folders + files by (shareId, relPath); mark missing, never delete   (G3)
                  ├─ TitleParser.parseVideoName / parseFolderName  → titleParsed, year, season, episode
                  ├─ FolderClassifier.classify(name, children)      → ROOT / COLLECTION / TITLE / SHOW / SEASON / PLAIN
                  └─ scan_runs row updated every 400 ms              (foldersDone, filesFound, currentPath)
Room triggers keep media_fts / folder_fts (FTS4, unicode61) in step with the tables.

Home     ← observeContinueWatching (progress JOIN), observeNewest (addedAtMs), scan_runs
Library  ← all folders + files of the enabled shares, walked in memory: TITLE folder → one title tile
           for its largest video; other folders with files beneath → collection tile; loose files → title tiles
Search   ← media_fts MATCH '"word"* …' ∪ folder_fts, progress for the Unwatched filter, scan_runs for the footer
```

Parsing is local only ("nothing leaves the network"): a year or an
`SxxEyy` is what makes a title "matched"; anything else is shown by its
filename with the design's "No match" chip. Kinds are decided from one
listing as the walk goes, so there is no second pass.

## Downloads and offline (Phase 5, requeued as one queue in P8)

```
TitleDetail "Keep on this device" ── TransferRepository.start(fileId)
Browse / Library / Search hold → Download ── TransferRepository.startAll(fileIds)      known files
                                          └─ TransferRepository.addFolderPicks(picks)  whole folders
     └─ transfers rows (QUEUED, bytesDone = 0, localPath = "{id}.{ext}")
     └─ download_picks rows (discovered = 0)       a folder still to be walked, carrying excludedFileIds / excludedPaths (v7)
     └─ TransferScheduler.enqueue()                interface; WorkManagerTransferScheduler today
          └─ TransferQueueWorker (foreground dataSync, ONE unique job "transfers", KEEP,
             network constraint, expedited, exponential backoff)
               ├─ phase 1, discovery: picks.nextUndiscovered() → library.listSubtree(folderId)
               │    ├─ breadth-first refreshFolder() per folder, honouring the share's roots (rootsCover)
               │    ├─ excluded subtrees are not entered (prune); excluded file ids are not queued
               │    ├─ files queued PER FOLDER as they are found, so copying starts after the first
               │    └─ SmbFailure → Result.retry(), the pick kept so the walk resumes
               └─ phase 2, the drain: transfers.nextQueued() → copy → repeat (one file at a time)
                    ├─ StorageCheck.hasRoom(free, total, done)?  no → FAILED · NO_ROOM · shortfall in the row
                    ├─ gateway.open(...).readAt(bytesDone …) → append to "{localPath}.part"; row updated every 500 ms
                    ├─ the 500 ms tick re-reads the row: gone or not RUNNING → abandon this file (per-file Cancel)
                    ├─ SmbFailure → PAUSED · SHARE_DROPPED, Result.retry() ("resumes on its own"), server marked unreachable
                    └─ done → rename .part → DONE
          notification (id 43): "Keeping N videos on this device" · "Finding files… 34" while discovering,
          then "3 of 34 · name" with a determinate bar in permille of BYTES; Stop cancels the batch

PlaybackSession.load ── MediaUriResolver.playableUriFor(fileId)
     └─ finished copy?  file:// (DefaultDataSource reads it)  :  regolith://file/{id} (SmbDataSource)

Reachability: every SMB caller (listing, scan, transfer) marks the server reachable or not on `servers`;
Library's Network tab shows the out-of-reach card from that column, and "Try again" is one root listing.
```

## Multi-select and batch downloads (P8)

```
hold a row or tile (platform ~500 ms, combinedClickable)
     └─ SelectionStore (@Singleton, in memory)        Selection(folders: Set<FolderPick>, files: Set<FilePick>)
          ├─ toggleFolder: a pick DROPS narrower picks and files beneath it   (domain/transfer/Selection.kt, pure)
          ├─ pathCoveredBy(paths, relPath): the one path rule, shared with rootsCover
          └─ coversFileIn (inclusive) vs coveredByAncestor (exclusive)
     └─ SelectionPresenter                            one implementation, three screens
          ├─ observe(): Flow<SelectionUiState?>        null = not selecting
          │    folders of the picked shares + DONE file ids + pending picks → the tally
          └─ download(): files → startAll, folders → addFolderPicks, then one enqueue

Browse · Library · Search  ← state.selection drives the contextual TopBar, the row checks and SelectionBar
                              folders: the pick box is its own target, the row still opens (RowLeading.PickBox)
                              inside a pick every row is live: tap takes it OUT (excludedFiles / excludedFolders), tap again puts it back
                              back navigates; the selection ends on arriving somewhere that cannot act on it

Library › On this device   ← DeviceUiState.picked: a SEPARATE, tab-scoped selection, opposite act
     ├─ hold a copy → pick; Select all; Remove (destructive SelectionBar) → RemoveTarget.PICKED
     ├─ "Clear all" beside Ready offline → RemoveTarget.EVERYTHING
     └─ both through RemoveCopiesDialog, which names the count; then removeAll / removeEverything
AppViewModel.tabDots       ← transfers.observeAll(): anything not DONE lights the Settings dot
Settings › Downloads       ← the same rows: the 8dp status dot, "3 of 34 · 61.2 GB left", the inline bar, Stop
```

Three things a web developer would not guess. The selection cannot live in
`BrowseViewModel` because that ViewModel is created **per `folderId`**
(assisted injection), so drilling into a subfolder builds a new one — the
singleton store is what lets picks survive the one gesture the feature
exists for. The tally sums each covered folder's OWN `fileCount`/`byteCount`
rather than expanding to file ids, because `WHERE folderId IN (…)` over a
share's folders passes SQLite's 999-variable limit; the exact id list is
built once, at Download. And a picked folder the app has never listed
contributes nothing to that sum, so the bar says "At least N" or
"Counting…" rather than printing a number it cannot stand behind.

## Design fidelity, discovery, onboarding (Phase 6)

The design export (`design/docs/.../Regolith Video Player.dc.html`) is a
set of 320×692 phone frames plus three 780×360 landscape player frames,
each an HTML tree with exact `font:` / `color:` / `padding:` values. Phase
6 treated those values as the spec and rebuilt every screen against them:

- **Type and colour are copied, not approximated.** `ui/theme/Type.kt`
  holds one `TextStyle` per `font:` pair the frames use (screen title
  Michroma 15/1.3, eyebrow 600 11px tracked .14em, tile name 600 12/14,
  body 400 14/21, …) and `Color.kt` the exact tokens (`#8A8A8A` idle nav,
  `rgba(255,255,255,.06)` frost, `rgba(0,0,0,.52)` pill, …). Layout pixels
  are used as dp for gutters and gaps only; every *fixed* size (buttons,
  thumbnails, posters, glyphs) goes through `SIZE_SCALE`, and grids stay
  fractions of the width because they were already right. Type is the
  original case of the same rule — every size and line height in
  `Type.kt` is the frame's px multiplied by `TYPE_SCALE` (1.28 = 411/320)
  so text keeps the same share of the screen it has in the frames. Tracking
  stays in `em`, which is already relative, and the sizes stay written as
  the design's raw px so a style can still be diffed against the export.
- **The two media lists are switchable.** Library and Browse each carry a
  grid/rows switch in the top bar (`ui/components/TopBar.kt`'s
  `viewModeAction`), stored per screen in `AppPreferences`. Browse's title is
  the folder you are in, "Browse" at the root — the frames' "Add media"
  described the flow, not the screen.
- **Icons and photographs come from the export.** The design's SVG paths
  were converted to `res/drawable/rg_ic_*.xml` (36 vectors) and its four
  photographs extracted into `res/drawable-nodpi/rg_*.webp`, replacing the
  Lucide set for anything the design draws. Lucide stays as a dependency
  only for glyphs the design does not draw.
- **Components carry the design's states.** `ui/components/` gained the
  frosted `PillButton`, `IconCircleButton`, `SwitchControl` (44×26),
  `CollectionBadge` / `UnwatchedDot` / `CountBadge`, `NoticeCard`,
  `SkeletonTile`, `ProgressEdge`, `ResumeCard` and the `NavPill` with its
  dimmed cells (no source → Library/Browse/Settings dim; every server out of
  reach → Home/Browse dim; `AppViewModel.dimmedTabs`).
- **Splash and onboarding.** The system splash (black + wedge) covers the
  cold start; `SplashContent` (moon photograph, wedge, wordmark) then shows
  for 1.4 s over the first screen. `OnboardingScreen` is a `HorizontalPager`
  of the three photographed pages with dots, Skip and Next; the last page
  has only "Find my server", which pushes `AddServer.Search`.
- **LAN finder.** `data/discovery/HostDiscovery` sweeps port 445 across the
  phone's Wi-Fi subnet (48 sockets in flight, 300 ms each) and names hosts
  by reverse DNS. No mDNS: from Android 17 (targetSdk 37)
  `NsdManager.discoverServices` opens a system "Choose a device to connect"
  picker instead of returning results, which would put a foreign list on
  top of the design's own. Picking a host prefills `AddServer.Manual`.
- **Player.** The A–B loop, when set, replaces the portrait details panel
  under the picture (design frame 29) and stays in the side sheet in
  landscape; brightness and volume drags draw the design's rails; the
  gesture map shows once, on the first landscape session
  (`AppPreferences.gesturesSeen`).
- **Startup.** Installing jcifs-ng's full BouncyCastle takes seconds on a
  cold start, so `JcifsGateway` does it on its own thread and joins it
  before the first handshake. The debug build still cold-starts in ~5 s on
  the emulator, the same as other debug apps there.

## Demo library

`data/demo/` is a library with no server behind it, for reviewing the app
away from a share. `DemoLibrary.install()` writes a server, a share, the
folder tree and 18 files exactly as a scan would, then copies four bundled
clips (`res/raw/demo_*.mp4`, ~900 KB together) into `DemoStore`, one file
per title, and seeds four progress rows so Continue watching has content.

It works end to end because of one small piece of plumbing:
`player/LocalMedia` answers "is there a copy of this file on the device?"
and is consulted by the player (`MediaUriResolver.playableUriFor`), the
artwork pipeline (`ArtworkRepository.resolveFile`), the scrub previews and
the container probe. A demo file and a finished download are the same
answer, so posters are real frame grabs, Title Detail's codec rows are read
off the container, and scrubbing shows real frames — with the network off.

`DemoSource.HOST` marks the pretend server so the scan, `refreshFolder` and
`probeReachable` skip it instead of failing against a host that does not
exist. Gated by `BuildConfig.DEMO_LIBRARY`.

## User chapters (P9)

Every file already had chapters — the container's own markers, or the even
split off a ladder of round intervals — and they are good for jumping and
useless as places: "Part 3" says nothing about what is there. P9 adds a
third kind, written by the user in the player, and makes it the one that
wins.

```
PlayerScreen ── Chapters sheet › Edit ── PlayerViewModel.chapterDraft (ChapterDraft, pure)
                                          │   mark at playhead · select · move/nudge · rename · remove
                                          └─ Done ── UserChapterRepository.save(fileId, chapters) ── user_chapters (schema v8, one set per file, one transaction)
PlaybackSession.load(fileId) ── followUserChapters ── UserChapterRepository.observe(fileId) ── PlaybackState.userChapters
PlaybackState.chapters  = userChapters ›› containerChapters ›› ChapterMarks.evenly(duration)      chapterSource says which
Scrubber(chapters)      = segments with 2dp gaps; the finger's segment grows; ScrubPreview names the part (chapterLabelAt)
SearchViewModel ── UserChapterRepository.search(q) ── user_chapter_fts MATCH ── SearchHit.Moment ── Player(fileId, startMs)
Settings › Chapters ── UserChapterRepository.stats() / clearAll()
```

What a web developer would not guess:

- **The draft lives in the ViewModel, the rows in Room, and the session
  only observes.** The editor never talks to the player. Done writes a set
  to `user_chapters`; the session has been collecting that file's rows
  since `load`, so the scrubber updates by itself — and so does an open
  player when Settings clears everything. Guardrail G4 holds: the session
  has no editing state, the ViewModel has no playback state.
- **Reverting is deleting.** `chapters` is a three-way fallback, so taking
  the user's rows away lets the container's markers or the even split fill
  back in. Nothing is stored to "restore".
- **The rules are a pure value.** `ChapterDraft` (domain) keeps marks
  sorted, pins 0:00 (rename only), refuses a mark within a second of another,
  keeps names exactly as typed until save (trimming per keystroke would eat
  the space you just typed) and remembers which file editing began on, so
  autoplay moving on cannot make Done write to the wrong film.
- **The editor is a slot, not a screen.** It takes the A–B loop panel's place
  under the picture in portrait and in the unfolded column; in landscape or
  half-open, where there is no column, it is a sheet with a timeline of its
  own. Edit pauses the film and steps out of a chosen full screen first.
- **Marks move on a strip of their own, one at a time.** The first build put
  draggable flags on the 3dp scrubber and the owner found them finicky: a
  miss by a few pixels scrubbed instead of moving the mark. Round two gives
  the editor a 64dp `MarksTimeline` with 30×40dp handles, and the picture's
  scrubber only scrubs. Opening a row is a lock — every other handle is
  inert and every other row dims — and the open row lifts to a lighter card
  with the name, a typed Start time (`ChapterDraft.parseClock`, checked
  against `bounds`), and ±0.5 s / ±5 s nudges.
- **Only names are searchable.** The FTS index is over `title`; an unnamed
  mark has no words to find. A hit is a `SearchHit.Moment` — a place, not a
  file: its own eyebrow above the file matches, a tag on the row, no part in
  multi-select, and a tap that opens the player at that time through the
  Player key's existing `startMs`.

## Chapter sidecars (P10)

P9 kept the chapters you write in a Room table on the phone. P10 puts the
durable copy next to the film on the share — `<basename>.chapters.txt`, in
mkvmerge's simple format (`docs/CHAPTERS.md` is the contract, written so a
desktop editor can produce the same file) — and turns the table into a
cache of it, which is also the search index.

```
Done ── UserChapterRepository.save ── user_chapters (rows first) ── ChapterSyncRepository.markDirty ── chapter_sync.dirty
                                                                       └─ ChapterSyncScheduler ── ChapterSyncWorker ── syncDirty ── SidecarWriter.write (.part, rename over) ── SmbGateway.write/rename/delete
Scan / Browse ── LibraryRepository.refreshFolder ── ChapterSyncRepository.onFolderListed ── newer file? ── SidecarWriter.read ── ChapterSidecar.parse ── replaceForFile
ScanWorker done ── ChapterSyncScheduler.enqueue                                                              a refused write gets another go
PlaybackSession ── observeSync ── PlaybackState.chapterSync / chapterSyncNote ── the sheet's subtitle
Revert ── ChapterSyncRepository.revert ── rows AND the file          Settings › Clear ── clearLocal ── rows only, never the share
```

What a web developer would not guess:

- **The gateway's first write, and its only one.** `SmbGateway.write`,
  `rename` and `delete` exist for this, are called from `SidecarWriter`
  alone, and name nothing but `<basename>.chapters.txt` and its `.part`.
  The URL for a write drops the trailing slash `urlFor` adds, because jcifs
  creates a new name as a directory when the URL ends in `/`.
- **jcifs says "auth" when it means "denied".** ACCESS_DENIED arrives as
  `SmbAuthException`; the gateway maps that status to `Forbidden` (signed
  in, refused this request), which for a write is a read-only share. Reads
  never cared; writes do.
- **Newest wins, whole.** A sidecar whose modified time moved replaces the
  rows unless the phone has a newer unsent edit, in which case the worker
  writes over it. No merge: a chapter list is one value.
- **Rows first, file later.** Done never waits on the network. The worker
  is one unique WorkManager job with a network constraint and backoff, and
  a scan finishing enqueues it too, so a write refused while the share was
  read-only is retried without anyone asking.
- **A downloaded film carries its chapter file too.** The download worker
  fetches `<basename>.chapters.txt` beside the film once the copy lands and
  keeps it as `<fileId>.<ext>.chapters.txt` in the downloads directory;
  every edit rewrites that local copy at once (so an offline edit is on
  disk before the share hears of it), every import refreshes it, and the
  copy's removal takes it away. Playback itself still reads the rows.
- **Settings never touches the share.** Clear empties the phone's cache;
  the next scan re-imports every film that has a file. Revert is the one
  action that deletes a file, after a confirm that names it, and on a
  read-only share it leaves a note that the file will come back.

## Decision log

| Date | Decision | Why |
|---|---|---|
| 2026-09-08 | Navigation 3 instead of Navigation Compose 2 | Nav2 is in maintenance mode; a greenfield app should not start on a migration. |
| 2026-09-08 | minSdk 34 / targetSdk 37 | Personal app on a Pixel 10. Frame extraction and foreground-service types need no version branches. |
| 2026-09-08 | jcifs-ng 2.1.10 for SMB | True random-access reads; no JGSS dependency on Android; NetBIOS naming for host labels. SMBJ rejected (BouncyCastle, no NetBIOS, more DIY). |
| 2026-09-08 | Title matching is local filename parsing only | Matches the design's "nothing leaves the network" promise; no API keys, no network layer. |
| 2026-09-08 | Lucide icons as vector drawables (`icons-lucide-android`) | The current artifact; `R.drawable.lucide_ic_*` via `painterResource`. |
| 2026-09-08 | Fonts bundled in `res/font` | Downloadable Fonts resolves async and would flash on the splash. |
| 2026-09-08 | Committed debug keystore | Identical signatures on every machine so reinstalls keep the Room database. |
| 2026-09-08 | Hand-rolled DAO upserts keyed by natural key | Room's `@Upsert` resolves conflicts by primary key; ours are unique indexes on autogenerated rows. |
| 2026-09-08 | Connecting / Sign-in-failed are states of the manual-entry screen, not routes | The typed address and credentials must survive a failure; a separate route would lose them or need a flow-scoped store Nav3 lacks. |
| 2026-09-08 | Playback stops when the Player screen leaves (Phase 1) | No background audio yet; `PlaybackSession` already owns the player so PiP/background can be added without touching the screen. |
| 2026-09-08 | Android's "BC" crypto provider is replaced with jcifs-ng's bundled BouncyCastle at gateway init | NTLM needs MD4; Android's trimmed BC lacks it and `addProvider` is a no-op while a "BC" exists. Every password login failed with `NoSuchAlgorithmException: MD4` until this. |
| 2026-09-08 | `ACCESS_LOCAL_NETWORK` declared and requested on the Add Server screen (not deferred to Phase 6) | Android 17 enforces it for targetSdk 37; without it LAN connects time out and look like an unreachable server. The emulator's virtual gateway is exempt, which hid it. |
| 2026-09-08 | `PlaybackSession.player` is a `StateFlow<ExoPlayer?>` | Hardware/software decoding is a renderers-factory choice fixed at build time, so switching means a new ExoPlayer at the same position; the surface re-attaches by observing the flow. |
| 2026-09-08 | Double-tap seek stacking, A–B loop maths and video labels are pure Kotlin in `domain/playback` | Gesture and loop edge cases are unit-tested without an emulator; the screen only wires them. |
| 2026-09-08 | Vertical drags and pinches use a hand-rolled detector | Compose's transform detector consumes one-finger pans, which would swallow the brightness/volume drags. |
| 2026-09-08 | 1 MiB read-ahead (`BufferedByteSource`) between the DataSource and the share | ExoPlayer's extractors read headers a few bytes at a time; measured 2,000 SMB round trips averaging 30 bytes at 8 ms each. One block fetch amortises them; the same wrapper serves frame extraction in Phase 3. |
| 2026-09-08 | `jcifs.smb.client.tcpNoDelay=true` and 1 MB send/receive buffers | Nagle plus delayed ACK stalled each request; bigger buffers let one SMB2 read carry a whole block. |
| 2026-09-08 | The play/pause button follows `playWhenReady`, not `isPlaying` | `isPlaying` is false during every rebuffer, so a loop restart or a seek looked like a pause. The user's intent is the button's state; buffering is the spinner. |
| 2026-09-08 | Guest SMB sessions never enforce IPC signing; password sessions relax it only after a signature failure | A guest session has no session key to sign with; some servers produce signatures jcifs-ng cannot validate. |
| 2026-09-08 | Connect falls back to the share named in the address on any non-auth enumeration failure | jcifs-ng dials port 445 for the enumeration RPC regardless of the address port, and many NAS boxes refuse enumeration to non-admins. |
| 2026-09-08 | Servlet API excluded from jcifs-ng | Only its HTTP filter needs it; keeps the APK lean. R8 `-dontwarn` covers the dangling references. |
| 2026-09-08 | Frames come from `MediaMetadataRetriever` over a `MediaDataSource`, not Media3's `FrameExtractor` | The retriever takes our byte source directly, returns a Bitmap with no GL pipeline, and `OPTION_CLOSEST_SYNC` decodes one key frame per request. `FrameExtractor` needs `media3-effect` plus an OpenGL context per grab. It sits behind `FrameSource` if the platform extractors ever reject a container the player handles. |
| 2026-09-08 | One resolution writes both artwork kinds | Opening a video over SMB is the expensive step; poster and thumb are two crops of the same source. |
| 2026-09-08 | `BufferedByteSource` keeps several blocks for frame extraction (8 × 256 KiB); the player keeps one 1 MiB block | A frame grab alternates between the MP4 sample tables at the end of the file and the frame bytes in the middle; a single block thrashed (23 reads, 62 KB, 4.5 s per frame). Eight small blocks: ~350 ms per frame. |
| 2026-09-08 | Coil pinned at 3.4.0 | 3.5+ is compiled with Kotlin 2.4, whose metadata the Kotlin 2.2 compiler AGP 9.4 bundles cannot read. Bump with AGP. |
| 2026-09-08 | Coil's disk cache is off; `filesDir/artwork` + the `artwork` table are the cache | A frame grab is expensive and must survive eviction for the offline state; Settings › Media clears it explicitly (guardrail G5). |
| 2026-09-08 | Placeholder rows expire after 24 h; unreachable-share failures are not recorded | A file the decoder cannot read stops being retried on every scroll, but a `poster.jpg` added later is picked up; a share that is merely off is retried next time. |
| 2026-09-08 | Room v2 via `@AutoMigration(1, 2)`; exported schemas are debug assets | Additive change (new table, nullable columns). AGP does not merge assets for the unit-test source set, so the migration test reads the schemas from the debug variant's assets. |
| 2026-09-08 | Browse tiles open Title Detail, not the player | The design gives every title one red Play on its detail screen; the detail is also where the container probe runs once. |
| 2026-09-09 | The scan is a foreground WorkManager job of type `dataSync`, unique per share | It must survive the user leaving the Scanning screen ("Run in the background") and the app being backgrounded; WorkManager also restarts it after a process death. `KEEP` policy means a second tap does not walk the share twice. |
| 2026-09-09 | Scan progress lives in `scan_runs` rows, not WorkManager progress | Guardrail G3: Home, Library, Search, Settings and the Scanning screen all read the same Flow, and it survives the process dying. |
| 2026-09-09 | Folder kinds are decided from a single listing | `Season NN` names, `SxxEyy` files and `Title (Year)` names are enough; a second bottom-up pass would have doubled the walk for the SHOW/SEASON edge only. |
| 2026-09-09 | A folder with exactly one video is a TITLE folder even without a year | `The Thing/The.Thing.1982.mkv` is the common layout; the cost is that a one-file `Home videos/` reads as a title. |
| 2026-09-09 | Search is Room FTS4 with `unicode61` over filename, parsed title and path, prefix-matched per word | Accent folding gives "samourai" → "Samouraï"; external-content tables cost no duplicate text. The index is rebuilt once in the 2→3 migration. |
| 2026-09-09 | The Library walks the share's folder tree in memory | Two queries (all folders, all files of the enabled shares) instead of one per collection; a 1,284-file share is a few hundred KB. Revisit if a share reaches tens of thousands of rows. |
| 2026-09-09 | The Scanning screen shows an indeterminate bar and the live count, not a percentage | The share's size is unknown until it has been walked; the design's "64%" would have been invented. |
| 2026-09-09 | Downloads are foreground WorkManager jobs behind a `TransferScheduler` interface | Android's user-initiated data transfer jobs are the better fit for long copies but change the enqueue/permission model; the interface keeps that swap to one class. Unique-per-file `KEEP` policy makes "Try again" idempotent. |
| 2026-09-09 | Resume trusts the `.part` file's length over the row's `bytesDone` | A crash between a write and the row update leaves the file slightly ahead, never behind; re-reading a few bytes is harmless, skipping them is not. |
| 2026-09-09 | A dropped share pauses a transfer (retry with backoff); no room fails it | The design names the two causes separately because they need different fixes: waiting vs. freeing space. |
| 2026-09-09 | Local playback is a `file://` URI from the same resolver | DefaultDataSource already reads files; the player never learns which it got, and progress, scrub previews and Title Detail keep working on the same file id. |
| 2026-09-09 | "Out of reach" is a column on `servers` set by whichever SMB call failed last | One source of truth for Library, Home and the transfers instead of each screen probing; cleared by the next successful call or by "Try again". |
| 2026-09-09 | Notification permission is requested on the Scanning screen (Phase 6) | The scan runs either way; the notification only tells the user why the app is busy. Asked where the reason is on screen; the scan proceeds whatever the answer. |
| 2026-09-09 | Design SVGs converted to `rg_ic_*` vector drawables; Lucide kept only as a fallback | The frames draw their own glyphs (wedge, server, pills); a near-match icon set is exactly the "minute details" the audit was about. |
| 2026-09-09 | Design px = dp, type at the frames' stated sizes | The frames are 320px wide; a 411dp phone gets the same sizes with more room. Scaling everything ~1.28× to match proportions is the alternative, left as a question for the owner. |
| 2026-09-09 | No mDNS in the LAN finder | Android 17 routes `NsdManager` discovery through a system device picker for targetSdk 37; the port-445 sweep finds every host the picker would and more. |
| 2026-09-09 | BouncyCastle installed off the main thread, joined before the first SMB handshake | The provider load cost ~3.7 s of the cold start on the emulator, all on the main thread under the splash. |
| 2026-09-09 | **Reverses the row above**: type is scaled 1.28× (`TYPE_SCALE` in `Type.kt`), layout is not | Reading the 320px frames' type as sp on a 411dp phone made every label ~28% smaller relative to the screen than the mock, because layouts stretch to the real width but type did not. 411/320 = 1.284. Layout stays at design px = dp (the frames' padding at the real width is the intended "more room"); only type is multiplied. Still `sp`, so the user's font-size setting applies on top. |
| 2026-09-09 | Library and Browse each get a grid/rows switch, remembered separately in DataStore | Answers the open question about frame 25 vs 26 with "both": a poster wall is how you recognise a film, a row is how you scan a hundred filenames, and which one you want depends on the share, not on the app. Two keys rather than one because the two screens answer different questions. Library's rows are hairline-separated on the ground, not in a card: a card of 1,284 rows is not a card. |
| 2026-09-09 | Screens slide instead of scaling; tabs cross-fade | Navigation 3's default scale-and-fade reads as "this screen was destroyed", which is what made leaving a screen look wrong. A push slides in from the right over a sixth-width parallax; a pop reverses it, and predictive back drives the same transform. The four pill destinations are siblings, not a stack, so they cross-fade — sliding would imply an order the pill does not have. |
| 2026-09-09 | The player's full-screen button fills the screen without rotating the phone | Orientation is the phone's business; filling the screen is the user's. Landscape is always full-bleed (nothing else a wide screen should do) and the button is therefore a portrait-only control — in landscape there is no glyph, because rotation is what takes you back. Back steps out of full screen before it leaves the player. A rotation lock is the next step; until then the player still forces SCREEN_ORIENTATION_SENSOR. |
| 2026-09-09 | "All" opens a Continue watching screen rather than filtering Library | The resume list is ordered by when you last played, which is not one of Library's sorts, and it is the only list where a half-finished title outranks a new one. Both read the same `observeResume` so Home's row and the screen can never disagree. |
| 2026-09-09 | Pull to refresh asks for 130dp and moves the page with the finger | Material's 80dp default fired on the smallest flick down, so the share was rescanned by accident. The page now follows the pull at 60% and springs back, which is what tells you the gesture is live before it commits. |
| 2026-09-09 | A demo library that writes real rows and real files, rather than a mock layer | Everything the UI shows comes from Room and from a file on disk, so a fake data source would have had to be threaded through the repositories, the artwork pipeline, the probe and the player — four places to get subtly wrong. `DemoLibrary` instead seeds the same rows a scan would and copies four bundled clips into `DemoStore`; nothing downstream knows demo mode exists. |
| 2026-09-09 | Anything that opens a file asks `LocalMedia` first | The player already preferred a finished download over the share; the artwork pipeline, the scrub previews and the container probe did not, so they all needed the network even when the bytes were on the device. One lookup (download, or demo file) in front of all four fixes offline downloads and makes the demo library work for free. |
| 2026-09-09 | Demo files live outside the downloads directory | A demo file is not a download. Keeping it in its own directory means "On this device" stays a true statement, the storage figure is not inflated, and removing the demo is one `deleteRecursively`. |
| 2026-09-09 | The scan, folder refresh and reachability probe skip the demo server (`DemoSource`) | `demo.regolith.local` resolves to nothing, so every SMB call to it would fail and mark it out of reach — the first pull-to-refresh would have made a working demo look broken. |
| 2026-09-09 | `SIZE_SCALE` (= `TYPE_SCALE`) on every FIXED dp size; fractions and gutters untouched | The frames are 320px wide and the phone is 411dp, so every fixed size in the export was 22% small on screen: a 112px poster filled 35% of a frame and 27% of the phone, and the same held for buttons, thumbnails and glyphs. A button is type in a box and a thumbnail is art in a box. The exclusions are the point: the Library and Browse grids divide what is left after the gutters, so their tiles already landed at 29% and 45% against the frames' 28% and 43% — scaling those would have broken them — and gutters, gaps and the 44dp hit targets stay put, because room across is what a wider screen is for and 44dp is an ergonomic floor. |
| 2026-09-09 | The resume card is 256dp, not 186 × the scale | 186px on a 320px frame showed a card and a half; scaled by 1.28 it would show two. 256 restores the frame's peek, which is what says "this is the thing you were watching" rather than "here are some tiles". The one place a measured value beat the formula. |
| 2026-09-09 | One eyebrow, one row label, one in-card title; Michroma is titles only | Section eyebrows were Space Grotesk on Home, Library, Browse and Search but Michroma on Settings, the player sheets, Title Detail and the add-server flow — two systems, half the app each, and the Michroma one put the display face on wayfinding so Settings read as three competing headings. `MichromaLabel` and `michromaRow` are gone: every eyebrow is `Eyebrow`, a server name is content and is set in the content face, `rowLabelMedium` (14) collapsed onto `settingLabel` (15) because a one-point difference at the same weight across adjacent cards reads as a mistake, and an empty state inside a card takes `dialogTitle` while Home's full-screen one keeps `emptyTitle`. The switch's knob, inset and travel now share the track's scale — scaling the track alone had left the knob 14dp short of the end. |
| 2026-09-09 | List-row minimum heights go through `SIZE_SCALE` after all | The earlier exclusion ("rows are content, they grow with their text") was wrong for the *minimum*: 56px in the frames existed to frame a 34px icon box, and once the box scaled to 44dp the unscaled row left it 6dp from the card edge where the design had 11. `ListRow` defaults to `56.scaledDp()` (72dp); Library's poster rows use `64.scaledDp()` so a 65dp-tall poster gets 8dp each side. Gutters and gaps are still not scaled — this is a fixed size framing fixed content, which is exactly the rule. |
| 2026-09-09 | A parent listing never overwrites a child folder's counts | `FolderDao.upsert` keeps `fileCount`/`byteCount` unless the incoming row was itself listed; before, browsing into a share zeroed every subfolder's counts. |
| 2026-09-10 | Adaptive layouts key on window shape, not device (`ui/adaptive/WindowShape`: `wide` = width ≥ 600dp, plus fold posture and hinge bounds) | The owner has a Galaxy Z Fold; its inner display deserves its own layouts (see `FOLDABLE_PLAN.md`). A width breakpoint is one boolean that also covers tablets and split-screen, where a device check would not. Material 3 adaptive 1.3.0 supplies both signals through `currentWindowAdaptiveInfo()` (it wraps Jetpack WindowManager's `FoldingFeature`), is built with Kotlin 2.1.20 so it clears the metadata ceiling that pinned Coil, and brings `ListDetailSceneStrategy` for Navigation 3 so two-pane screens come from the existing back stack (G10). The manifest already keeps the Activity alive across `screenSize|screenLayout`, so fold and unfold recompose rather than recreate. |
| 2026-09-10 | Every tab screen pads for the nav pill through `LocalNavPillInsets`, not a `112.dp` literal | Nine call sites carried the same number. On a wide window the pill becomes a rail on the start edge and the padding moves with it; one composition local set by the nav graph is what lets the screens not know which. |
| 2026-09-10 | On a wide window the nav pill is the same composable turned vertical (`NavPill(vertical = true)`), floating on the start edge; tab screens are inset by `NAV_RAIL_INSET` per entry in the nav graph | Thumbs rest on the sides of a book-sized device and bottom-centre is a reach, so the rail is where Material puts navigation above 600dp too. One composable with an axis flag keeps the blur, tokens, cells and test tags identical, so there is nothing to drift. The inset is applied per tab entry rather than on the `NavDisplay` because pushed screens (Player, Title Detail) have no rail and take the full width; a container-level inset would have jumped on every push. |
| 2026-09-10 | Home on a wide window: Newly added is a six-across wall and the resume row shows exactly three cards | Home is a vertical scroll, so the wall is rows of six, not a lazy grid (a lazy grid inside a scroll column needs a fixed height). The resume card is sized from the row width rather than the phone's 256dp so three land edge to edge, which is what the inner-display frame shows. |
| 2026-09-10 | On a wide window Title Detail is a PANE beside the wall that opened it, through Navigation 3's `ListDetailSceneStrategy`, not a pushed screen | The back stack keeps its shape — `[Home, Library, TitleDetail]` either way — so the phone path stays the tested one and there is no second navigation structure to keep in sync (G10). The strategy pairs the top two keys into one scene: Library declares `listPane()` and Title Detail declares `detailPane()`. A detail opened from Home, Search or Browse finds no list beneath it and fills the window. `shouldHandleSinglePaneLayout = false` leaves the compact case to the default scene, so the phone keeps the slide transitions from Phase 6. |
| 2026-09-10 | Two panes from 600dp (`calculatePaneScaffoldDirectiveWithTwoPanesOnMediumWidth`), not Material's 840dp default | The Fold's inner display measures 883dp, clearing the default threshold by 12dp — close enough that a slightly smaller foldable or a split-screen window would silently fall back to one pane. 600dp also matches `WindowShape.wide`, so the rail and the panes appear together instead of at two different widths. |
| 2026-09-10 | The list pane asks for the rail's width on top of the wall's (`preferredPaneSize`), capped at 55% of the window | The rail is drawn inside the list pane, so at Material's default 360dp pane the wall had 258dp left and its three columns collapsed to 72dp tiles. The pane now pays for both (102 + 364). The cap matters just above the two-pane threshold, where the full ask would leave the detail unreadable; it scales instead of falling off a cliff. |
| 2026-09-10 | Picking another title in the pane layout REPLACES the open detail instead of stacking a second one | The wall stays live beside the detail, so a second tap pushed `TitleDetail` on top of `TitleDetail`: the key beneath was no longer a wall, so the pane lost its close control and the wall marked the wrong tile. On a phone the wall is covered when a detail is open, so the top key is never a detail and the same call is the plain push it always was. |
| 2026-09-10 | The player's portrait details are two columns on a wide window, with the playback sheet's own content inline in the left one | Under a 16:9 picture on the inner display there is a 330dp band the phone fills with a title and a list. Split, it holds what the film is and how it plays on the left, what plays next on the right, and the settings that were a modal sheet become part of the screen. `PlaybackSheetContent(header = false)` is the same composable without its title bar and close glyph, so there is one speed control in the app, not two. With an A–B loop set the left column is the loop panel, exactly as the phone replaces its details; unlike the phone, the right column keeps "Next in this folder", because the room is there. |
| 2026-09-10 | The speed and decoder pills are dropped from the wide player's pill row; A–B and Chapters stay | The pills exist to open the playback sheet. With that sheet inline two rows below them, a pill would be a second control for a setting already on screen — or, if made inert, a dead one. A–B is not a shortcut to anything: tapping it is how a loop is armed, so it stays and is always shown there rather than only once a loop exists. |
| 2026-09-10 | Browse spends its extra width on the share tree, not on a detail pane; Library spends it on the detail **(revised in F6, see below)** | Both were tried. A window has room for two of the three columns, not three: at 852dp the rail takes 102, and tree plus list plus detail leaves the detail at 166dp. The two screens ask different questions. A poster wall shows art and nothing else, so the detail beside it earns its place; a Browse row already carries the name, resolution, size and progress, and what Browse costs you on a phone is knowing where you are. So Browse keeps the tree and a file opens Title Detail full screen, as it does from Home and Search. |
| 2026-09-10 | Half open on a table, the player splits at the hinge's REPORTED position, and that beats full screen | `FoldingFeature` gives the hinge's own bounds; half the screen would be wrong, because the two halves of a fold are not exactly equal. Above the fold is the picture with the header chrome only; below it is the deck, where the hands are when the device stands on a table. Full screen is ignored in this posture — there is no picture worth filling a folded screen with — so the flex branch is tested before the immersive one. |
| 2026-09-10 | The flex deck's filmstrip is the existing scrub-thumbnail pipeline, not a new one | `ScrubThumbnails.request()` is already fire-and-forget and `updates` already re-emits as frames land, so nine frames cost nine key-frame seeks and no new machinery. They are taken at the MIDDLE of each of nine equal slices, so the first is not the black frame every film opens on, and requested furthest-first because the worker serves the most recent request first — which leaves the frames beside the playhead arriving first. Nothing is requested until the deck is on screen, and the whole strip is gated by the same "Scrub thumbnails" setting, because the frames come off the share. |
| 2026-09-10 | The deck never auto-hides; only the chrome over the picture does | Chrome hides because it covers the film. The deck covers nothing: it is the bottom half of a folded screen, and a transport that vanished from it would leave the user tapping a black panel to get it back. Dragging the timeline moves the ring along the strip instead of raising the floating preview frame the phone shows, since the whole film is already laid out. |
| 2026-09-10 | The split is draggable, and settles onto one of three anchors | A divider you can leave anywhere is a divider you keep fixing. The anchors are the rail (wall collapsed, detail full), the default width, and 85% (wall full, detail a sliver). Material's `PaneExpansionState` owns the position, so `preferredPaneSize` was removed with it: two sources for one number is how they drift apart. The handle is ours, drawn as the short vertical pill every foldable app uses, but it wears the library's `paneExpansionDraggable` modifier, which brings the 48dp touch target and the accessibility actions — so the split can be moved without dragging at all. |
| 2026-09-10 | ~~The control that brings the wall back appears only at the collapsed anchor~~ **Reversed the same day, see below** | The reasoning missed the 85% anchor, where the detail is a 114dp sliver and the control was drawn *inside* it — so the state with the worst dead end had no button at all. |
| 2026-09-10 | ~~The player's picture is divided into THIRDS~~ **Revised to 15/70/15, see below** | Two halves left nowhere for a third gesture and put a dismiss under both of them. Thirds also match the app's own gesture map, which has always been drawn in three columns. A middle drag up enters full screen and down leaves it, committing past 12% of the height or on a flick, so a stray finger cannot flip the layout. |
| 2026-09-10 | Only the middle third can dismiss the player, and a double-tap there is play/pause | Any fast downward flick used to leave the player, so dragging brightness down quickly closed the film — a real bug the new map fixes for free, because the side thirds no longer dismiss. The middle cannot seek either, being neither the back nor the forward side, which leaves play/pause as the gesture that belongs there. |
| 2026-09-10 | An ambient glow behind the portrait player: the file's own cached poster, blurred and faded downward | A 2.39:1 film sat in black bars, and the details below sat on flat ground. The poster is already generated and on disk, so the glow costs no read over the share and works with the network gone; a frame sampled from the film would cost a key-frame seek every few seconds. It is scaled past the edges so the blur has nothing to fade into, and darkened on a downward gradient to the app's own ground, which is how spill light actually falls off. Not drawn in full screen, where the picture covers everything. |
| 2026-09-10 | The reset control lives on the divider handle, not in either pane | The handle is the only thing on screen in *every* split; a button drawn inside a pane vanishes with that pane, which is how both extremes became dead ends. One control now covers both directions, and Title Detail lost its own. Double-tapping the handle does the same, the desktop-splitter gesture, free once there is something for it to do. |
| 2026-09-10 | Closing a detail returns the divider to the even split | The position used to be remembered for the session: collapse the wall, tap Home, come back, open a title, and you landed collapsed with nothing on screen explaining why. Collapsing is now a gesture for one title, not an invisible mode. |
| 2026-09-10 | `Browse` is a list pane again — the tree yields to the detail rather than competing with it | The F3 row above is right that three columns do not fit, but it chose by screen instead of by moment, so the same file behaved differently depending on which wall you came from. Browse now declares `listPane()` again: with no detail open it is the full 750dp and the tree stands beside the list as before; open a file and the pane drops to 466dp, which is below `SHARE_TREE_MIN_WIDTH`, so the tree steps aside on its own and returns when the detail closes. Two of three columns at any instant, and both walls behave the same. Its selected row lifts onto `skeleton` rather than `surface`: Browse's rows already sit on a `surface` card, so the Library's lift is invisible there. |
| 2026-09-10 | The rail hides two ways, on different triggers | Idle retract slides the rail out but keeps the reserved inset, so nothing reflows and the wall never jumps mid-read; the pin drops the inset from 102dp to 22dp and hands the width back, which reflows and therefore only happens on request. The spine that stays behind is the same four tabs at 4dp, not a hamburger: the lit dot still answers "where am I". Touches are watched in the `Initial` pointer pass and reported down a `MutableSharedFlow` so a scroll does not recompose the tree per frame. |
| 2026-09-10 | Autoplay is armed by four conditions and gated on `RESUMED` | The setting is on, `STATE_ENDED` arrived with `playWhenReady` still set (so scrubbing to the last second while paused is not an ending), the folder has a next file, and this one was not cancelled. The countdown runs inside `repeatOnLifecycle(RESUMED)` because a coroutine launched from the composition keeps running in the background, and a film ending off-screen must not quietly pull the next file off the share. |
| 2026-09-10 | Autoplay never crosses a folder, and defaults on | Name order inside one folder is the queue the player already draws as "Next in this folder"; crossing folders would wander into whatever sorted next on a share browsed by folder. On by default because a folder of episodes is the common case and the Up next card gives ten seconds to say no. |
| 2026-09-10 | The rail's collapse control is a circle beside the pill, not a cell inside it | A 26dp chevron glued under four 66dp tab cells, sharing their frosted ground, read as a fifth tab that had lost its label. It is now the same 44dp `IconCircleButton` the detail pane closes with, `s12` below the rail; `NavPill` goes back to being four cells in two axes, which is what its KDoc always claimed. They animate as one group so the edge never shows half a nav. |
| 2026-09-10 | Autoplay is two switches, the second nested and disabled when the first is off | "Keep playing" says what happens; "Don't ask first" names the thing being removed rather than the machinery removing it, and the dependency is then self-evident — you cannot skip a question you are not being asked. Disabled rather than hidden, so the option is visible before the parent is on and the list never changes shape. Rejected: one three-way segmented control (tidier, but a different shape from every other row in Settings). |
| 2026-09-10 | An explicit queue overrides "Keep playing" | `PlaybackState.queued` is true while Play all or Shuffle is running, and the player then plays on regardless of the setting. Tapping Shuffle on seven files is a request for seven; a queue that stopped after the first would be a bug. The setting governs what happens when you open ONE file and it ends. |
| 2026-09-10 | The playback queue lives on the session AND on the Player key | The session owns the running order (it is what outlives the screen, G4) and drops it the moment a file outside it is opened, so the autoplay step can call `load(id)` without destroying the queue it is walking. The key carries the ids as well, so a queue survives process death; `Player(fileId, startMs, queue)` stays serialisable because it is only a list of Longs. The order is the wall's own — shuffled in the nav graph, not in the session, which never has to know what a wall is. |
| 2026-09-10 | The player's middle drag moves the picture, because scale is the one transform a SurfaceView honours | Measured on the Fold before designing around it: a parent `graphicsLayer` with `scaleX/scaleY = 0.6` moved the picture; `alpha = 0.25` did nothing at all, since the system composites a SurfaceView in its own layer rather than painting it into ours. So the picture grows toward full screen and shrinks toward leaving, the details column (an ordinary composable) fades and slides, and anything that needs to darken the picture is a scrim drawn OVER it. Rejected: switching to a TextureView (an extra GPU copy and no HDR passthrough, for a fade we can get another way). |
| 2026-09-10 | **The split does not move. It is 50/50.** (reverses the draggable divider, F6 → F12) | Built over three rounds, and worse after each one. Whatever the panes did as the divider moved — reflow, clip, fade — something on one side was always wrong, and every fix for one side broke the other: the drawer that stopped the wall shrinking also stopped the rail from resizing it, which is the one resize that should happen. An even split is right for both panes and needs no handle, no anchors, no reset control, and no rule about what happens at the extremes. What the divider was really for — seeing the whole wall — is what closing the detail already does. Gone with it: `PaneHandle`, `DrawerPane`, `pane_reset_split`, the reset-on-close effect, `MIN_WALL_WIDTH`. |
| 2026-09-10 | The rail resizes the column beside it; that is what retracting it is FOR | The other half of the same mistake. With the wall behind a drawer, hiding the rail no longer gave its space back — the column stayed at its floor width and clipped instead. Since nothing is dragged any more, the width only changes when the rail does, and an ordinary resize is exactly right: the wall lays out to whatever is left and lays out again when the rail goes away. |
| 2026-09-10 | The landscape two-pane player is a property of the window, not of what is next | `sideBySide` briefly also required the folder to have something after this file, so the last episode of a season — or any lone file — opened into the portrait layout while the device was sideways. The test is now the window plus "this came from the library"; a film handed over by another app has no folder at all and stands the column down. When the folder is simply finished the column stays and says so, because a column that goes blank reads as a screen that failed to draw. |
| 2026-09-10 | ~~Both panes are drawers: measured at a floor width, placed at the start, clipped~~ (removed with the draggable divider) | The divider was still resizing *content* rather than the *view* of it. Each pane now measures its content at a floor — the wall at rail + 364dp, the detail at 360dp — and places it at the start edge, so a title that fitted on one line still fits on one line while half of it is behind the divider. A `Layout` rather than a `Box`, because a Box asked to hold something wider than itself does not promise where it puts it, and the overflow was leaving on the OUTER edge (cutting the first tile) instead of under the divider. Only the pane that may be dismissed also fades. |
| 2026-09-10 | The rail's inset follows the rail, and animates (reversing F6) | F6 reserved the rail's 102dp even while it was slid away, so nothing would reflow. What that produced was a screen with an obvious empty stripe and no rail in it — and the two ways of hiding the rail reserving *different* amounts of space, which reads as a bug rather than a decision. The inset now tracks what is actually on screen and animates between the two, which is what "nothing should jump" wanted in the first place. The pane scaffold keys off the PINNED state only, never the animated value: it is keyed on the pane width and would otherwise rebuild on every frame of a retraction. |
| 2026-09-10 | The chapter sheet is a wall of stills, and the pill spins until the list has settled | A chapter is a place in a film; the only thing that says which place is the picture, and the number beside it is how you got there. Frames come through the same scrub pipeline the flex filmstrip uses — one key-frame seek per card, no new machinery — taken a quarter of the way INTO each part, because a chapter boundary is usually a cut and the frame on a cut is often black. Cards never resize as pictures land, so the sheet does not jump. Separately, `chaptersScanned` distinguishes "this file has no chapters" from "we have not looked yet": without it the sheet opened on even divisions and then recounted itself when the container's real ones arrived, which is the height jump. |
| 2026-09-10 | Previous and Next join the transport, greyed rather than removed | Skipping ten seconds was a gesture and skipping a film was not. Both now sit in one `Transport` composable shared by all three chromes — portrait, full screen and flex — which is also what stops them drifting apart. The skip keys are always drawn and dimmed when there is nowhere to go: a transport row that changes width as you walk a folder moves the play button out from under your thumb. Previous walks whatever order Next does, the queue when one is running and the folder otherwise. |
| 2026-09-10 | "Open with Regolith": a VIEW intent plays a film that is not in the library | Two intent filters, because the type arrives two ways — a `content://` URI whose provider states a MIME type, and a `file://` URI with an extension and often no type at all; the second lists the containers Regolith actually plays rather than a wildcard, which would put it in the chooser for documents and archives. `PlaybackSession.loadExternal` plays a bare URI: no row, so no artwork, no folder, no resume point and no scrub previews (those need a seekable handle the library resolves and a foreign URI does not). What survives is the player — transport, speed, decoder, A–B, and chapters, which are even divisions of a runtime and need nothing but the runtime. The URI is held in the AppViewModel rather than read off the Activity's intent where it is used: the Activity is recreated on every rotation with the same intent still attached, so handling it in place would reopen the player each time the device turned. |
| 2026-09-10 | Artwork frames come from Media3's `FrameExtractor`, not the platform retriever | The bug hunt's real finding. `MediaMetadataRetriever` has two weaknesses that only hurt artwork and look identical from outside — a tile showing the first second: it is the PLATFORM extractor, not the one Regolith plays with, so a container the app plays perfectly can still refuse to seek; and a refused seek is not reported, it just hands back the opening frame. `FrameExtractor` uses the same extractors and the same `SmbDataSource` as playback, and returns `Frame.presentationTimeMs` — a receipt saying where the frame really came from, which the log now prints and warns on when it is more than 30 s out. Proven on the emulator with a fixture whose hue encodes its own timestamp: every thumbnail read back as 300.7 s of a 600 s clip. **The cost is real**: an ExoPlayer and a GL context per extraction, measured at roughly 1–3 s against the retriever's 250 ms on a local file. Taken deliberately — it is one still per file, the gap closes over SMB where I/O dominates, and the retriever stays the fallback and stays the scrub-preview path, where dozens of frames per drag make cheap the only thing that matters. |
| 2026-09-10 | `BufferedByteSource` is synchronized | Found while hunting the same bug. Its LRU is an access-ordered `LinkedHashMap`, which mutates on every *get*, and both readers above it — ExoPlayer's loader and the retriever's native decoder — call `readAt` from threads it does not own. Two concurrent reads could corrupt the map or return an evicted block, and the symptom is a frame decoded from the wrong bytes: over a share, never on a local file. Exactly the shape of bug that hides for months and looks like "some of my thumbnails are wrong". |
| 2026-09-10 | Chapters are even divisions of the runtime unless the container says otherwise | The container's own markers are strictly better information and still win. But almost no file has them, and what a chapter list is FOR is jumping through a film — so every file now gets one, cut at a round interval off a ladder (10 s, 15 s, 30 s, 1/2/5/10/15 min) chosen so the count lands at twelve or under. Round, not arithmetic: "every 10 minutes" is a thing you can hold in your head and "every 11 minutes 24 seconds" is not. A trailing sliver shorter than a quarter of the interval is absorbed rather than given its own part. The sheet says which kind you are looking at, because it changes what the names mean — "Act one" was written by someone, "Part 3" is arithmetic. |
| 2026-09-10 | Title Detail's hero gets its own 1280x720 `BACKDROP`, generated on demand | It was drawing the 320x180 `THUMB` across the full width of the window — 2076px on the inner display, a six-times blow-up, and it looked exactly like one. A backdrop is four times a thumb's bytes, so it is NOT one of the `stills` every tile writes: a wall of two hundred has no use for one. It resolves on its own key, through the same source order, for the one title actually opened. The player's ambient glow takes it too — 16:9 is the right shape for spill behind a 16:9 picture, where the 2:3 poster was a centre crop of the frame stretched across the window. |
| 2026-09-10 | A frame grab falls back to Media3 for the runtime before it grabs at 0 | The midpoint fix only helps if the midpoint is known. `MediaMetadataRetriever` does not always report a duration — some containers, some muxers — and `duration ?: 0` grabs the first frame, which is the black title card the whole change exists to avoid. `ArtworkRepository.runtimeOf` now tries the open FrameSource, then the `media_files` row, then `MediaProbe` (Media3's extractors, behind the `DurationProbe` seam so `data/artwork` keeps no Media3 dependency), and writes the answer back to the row. The extra open only happens for the files that would otherwise be wrong. |
| 2026-09-10 | Chapters are parsed by hand, from the container | Nothing on the platform will do it: `MediaMetadataRetriever` has no chapter API, and Media3's extractors keep only what they need to *play* a file — Matroska's `Chapters` element and MP4's `chpl` box are not that. Both formats are small and stable, so `ChapterParser` (pure, in `domain/media`, driven from a byte array by its tests) is a page of parsing rather than a dependency. It reads Matroska (following `SeekHead` when the chapters sit after the clusters) and the Nero `chpl` box. NOT the QuickTime chapter *track* — a text track behind a `tref` of type `chap`, which means walking sample tables and then samples, several times the code for a form almost nothing writes now. A file with no chapters gets no pill: the control used to be drawn unconditionally and did nothing. |
| 2026-09-10 | The player's sheets are a bottom sheet in every orientation (reversing design section 10) | The design put a 344dp side panel on the landscape player. It was built, and then rejected in use: a control you reach for while holding a phone sideways belongs under your thumbs, not against the far edge, and the same settings arriving from two different directions depending on how you were holding the device made them read as two different sheets. The sheet scrolls internally, which a short landscape window makes necessary. |
| 2026-09-10 | ~~The split divider stops on the left and dismisses on the right~~ (removed, see above) | Dragged either way it used to resize the content on both sides, and at the old 0.85 anchor the detail became a column of one-word lines. The two ends now do different things, the way every foldable mail app does it. LEFT stops at the wall's minimum (rail + 240dp, two poster columns): a two-pane screen with no list is just the detail with a stripe down the side, and the rail is already how you leave. RIGHT goes all the way and the detail is dismissed — `DismissiblePane` lays its content out at a fixed 260dp however narrow the pane gets and clips the overflow, so the words never reflow on the way out; it slides under the divider and fades, and below 40dp it is not composed at all. The handle's reset button brings it back. |
| 2026-09-10 | The letterbox bars carry the film's colour instead of black | Verified on the Fold, and the finding is the useful part: media3's `ContentFrame` sizes the video **surface** to the content, so the bands a 16:9 film leaves in a near-square window are ordinary window pixels and ours to paint. `AmbientGlow(spill = true)` fills them with the same blurred poster the windowed player already sits on, knocked back behind a flat scrim so it reads as spill rather than a second, blurrier picture. Black stays underneath, so a film whose poster never loaded looks exactly as it did. **Bars burned into the frames themselves — a 2.39:1 film encoded into a 16:9 raster — cannot be touched: those pixels are the picture.** The wash is the poster, not the live frame; sampling the playing frame would need a TextureView or an ImageReader per frame. |
| 2026-09-10 | Moving tiles removed (reversing the F7 entry below) | The sheet's cells are 240×135, which is what made a wall of them affordable, and at tile size that reads as a smeared thumbnail beside the crisp 500×750 still it plays over. The still is the better picture and it was already there. Removing it also takes back the twelve seeks per file and the ~3× disk. `ArtworkKind` is back to the two stills. |
| 2026-09-10 | A frame grab comes from the MIDPOINT of the runtime, not 10% | 10% is where a title card or a studio ident usually still is, so a folder of episodes sharing an intro produced a folder of identical tiles — the failure the whole artwork pipeline exists to avoid. Half way in is past every intro and well short of the credits. `ArtworkStore.GENERATION` is the migration: bumping it drops every row the app GENERATED (frame grabs, mosaics, placeholders) and leaves everything found on the share alone, so an existing library rebuilds itself on demand instead of needing Settings › Media › Clear. |
| 2026-09-10 | A folder with no sidecar gets a 2×2 mosaic of its contents, composed once per kind | A collection with no `folder.jpg` was a grey wedge, which tells you nothing; four frames from the films inside tell you what it holds. Composed separately at 500×750 and 320×180 rather than once and cropped — a 16:9 grid squeezed into a 2:3 poster slices the outer column in half. The frames are the expensive part and they are shared. A folder holding fewer than four videos takes several frames from each; a show folder walks down into its seasons (breadth-first, 24 folders max) to find any at all. All-or-nothing: a grid with a hole in it looks broken where a plain placeholder does not. A scan that finds a sidecar has appeared drops the mosaic row (`ArtworkRepository.onFolderListed`), so adding `folder.jpg` and rescanning replaces it. |
| 2026-09-10 | ~~Moving tiles are a sprite sheet of stills, not video and not an animated image~~ (removed, see above) | Playing the real files fails on decoders, not bandwidth: the inner-display wall shows eighteen tiles, and a phone will not give you eighteen hardware decoder instances — Media3 fails hard rather than degrading when they run out. An animated WebP or GIF is impossible for a different reason: Android can DECODE both and ENCODE neither, with no public API. So `ArtworkKind.PREVIEW` is twelve 240×135 frames packed into one 960×405 JPEG, drawn a cell at a time at 4fps. One image is one Coil entry, one decode and one memory-cache line per tile, where twelve frames would be twelve of each. Measured: 42KB a sheet against 15KB for a title's poster and thumb together. |
| 2026-09-10 | Previews are generated lazily, one at a time, and the setting defaults off | A sheet is twelve key-frame seeks over SMB against a thumbnail's one, through the same connection playback reads from, so `previewSlots` is a semaphore of ONE and the tile asks for nothing until it has been on screen for 700ms — flinging through a wall queues no work, because a tile that scrolls past leaves the composition and cancels its own request. Off by default so a library that never turns it on pays nothing. `ArtworkKind.stills` exists so the frame-grab and sidecar paths, which write both still kinds in a loop, do not try to write a sheet from one frame. |
| 2026-09-10 | The picture's zones are 15/70/15, not thirds | Brightness and volume were going off by accident, because a third of the picture each is a lot of picture for two gestures you rarely want and cannot undo. They now take 15% down each edge and reaching them takes aim; the middle 70% keeps the full-screen drag, the dismiss and play/pause, which are the ones worth having under any thumb. `SIDE_ZONE` is one constant and the gesture map is laid out from it, so the picture you are shown once is the map you are actually using. The cost, accepted deliberately: double-tap-to-seek shares those zones, so the seek targets narrowed too. Splitting taps from drags was rejected — a map where a tap and a drag at the same point mean different zones cannot be drawn, let alone learned. |
| 2026-09-10 | The player has a rotation lock, and it says when the system will ignore it | `PlayerOrientation` (Auto / Portrait / Landscape) sits with Speed in the playback sheet, remembered across films because a lock you set every time is not a lock. Android 16 stopped honouring `requestedOrientation` on large screens: a device whose SMALLEST width is 600dp or more decides for itself. Verified on the Fold — locking Landscape rotates the cover screen and does nothing on the inner display. So the control greys out and says why there, rather than pretending. Smallest width, not current width: a phone turned sideways is a wide window and obeys perfectly well. |
| 2026-09-10 | Every share carries its own Scan and Disconnect | They used to be one pair of buttons under the whole card, and Disconnect took `servers.first()` — so with two NAS boxes connected there was no way to remove the second one at all. Each row now ends in its own 40dp scan and disconnect actions (the confirm dialog was always per-row; only the button that opened it was wrong). "Add a share" is promoted from a chevron row inside the card to the section's red CTA, which is what the screen is for when nothing is connected. "Scan all" survives only when there are two or more shares, where it saves taps rather than duplicating the row above it. |
| 2026-09-10 | Every row in a Settings card sits on one `SettingsRowHeight` floor (`56.scaledDp()`), note or no note | A label-only row ("Hardware decoding") was 8dp of padding around one line of type; its neighbours carried a note and came out 22dp taller, so the card read as a ragged list rather than a rhythm. The floor is the design's own 56 put through `SIZE_SCALE`, like `ListRow`'s — the label and note inside it are scaled type, so an unscaled 56.dp lands under them and changes nothing. Scaled, a bare row and a one-line-note row land within a dp of each other, and vertical padding goes 8 → 12 so a note that wraps to two lines still has air. Share rows, the artwork-cache row and the demo row take the same floor, which is what makes the four cards on the screen one list. |
| 2026-09-10 | "On this device" gets a DRAWN illustration (`ui/components/EmptyArt.kt`, `OrbitArt`); the other three empty states stay text | It is the only empty state in the app that is a normal resting state rather than a fault — a share you have never scanned wants its Scan button, an empty folder wants one quiet line, and an unreachable share wants the notice card. This one only needs to say "nothing is wrong here, you just haven't kept anything", which is what a picture is for and what two lines of grey type were not doing. It is drawn on a `Canvas` rather than shipped as a vector drawable so it takes `RegolithColors` instead of hard-coding its own greys, costs the APK nothing, and stays crisp at any size; every coordinate is expressed in a 240×180 design box scaled by `size.width`, so one `width` parameter moves the whole drawing. Its dash is `CardStyle.Empty`'s own 6/4, which is what makes the art and the card around it read as one object. |
| 2026-09-10 | The full-screen player's pills move UNDER the timeline, and the playback glyph goes with them | Up beside the title they read as part of the film's identity rather than as controls for it, and they crowded the one question a header should answer. Under the track they sit with the thing they act on. The glyph follows them because a single control left alone on the top-right edge reads as forgotten, not as deliberate. |
| 2026-09-10 | The HW/SW pill is gone from every chrome | It was a pill you could only ever tap to open the sheet it was reporting, for a setting that already has a permanent home in app Settings — three entrances to one switch. Every pill that remains does something on its own tap. The decoder is still shown, where it belongs: in the meta line under the title, which names the codec anyway. |
| 2026-09-10 | Rotation is a glyph-only pill that CYCLES Auto → Portrait → Landscape, not three pills | Three would mean two thirds of the row is always the answer you did not pick. The glyphs are a set — a device standing up, a device lying down, both of them for Auto — and red says "locked", which is the accent's job everywhere else in the app. It carries no word because "Landscape" spelled out pushed the row past 411dp and the word was the first thing clipped; the playback sheet keeps the written three-way. The pill is not drawn where `LocalConfiguration.smallestScreenWidthDp >= LARGE_SCREEN_DP` (the Fold's inner display), because Android 16 does not honour the request there and a control that changes nothing is worse than no control — the sheet's row explains it, a pill has nowhere to put that sentence. |
| 2026-09-10 | The pill row scrolls horizontally, and full screen takes the portrait frame's 18dp gutters when the window is portrait | Four pills plus the glyph do not fit across 411dp inside the landscape frame's 30dp gutters. Compose does not DROP the overflow, it squeezes it: the last pill gets its Text laid out at zero width and you get a pill with nothing written on it. The gutters make it fit; the scroll is the guard for a longer label, a bigger font scale, or a fifth pill. |
| 2026-09-11 | Full screen draws `Transport`, not its own three buttons | It was the one chrome you could not walk a folder from — previous and next were missing there and nowhere else, because that row predates `Transport` and was never folded into it. The gap closes from 40dp to 18 in a portrait window: five cells 40dp apart is 442dp and a phone is 411. |
| 2026-09-11 | The ambient light is the film's OWN frame, dissolving once per 10 s, over the backdrop as a base coat | It was the title's backdrop artwork and nothing else: multi-coloured, but chosen once per file and fixed for the whole runtime. It now rides the scrub-thumbnail cache — the same pipeline as the preview, the filmstrip and the chapter wall — so it costs no new machinery and the reads are already bounded and already prefetched two buckets ahead of the playhead. 10 s is the cache's own bucket size; asking more often would return the same frame. Because the glow is the FRAME blurred and not a colour averaged out of it, the left of the screen glows what is on the left of the shot: a bias light with no zones to define. Saturation is lifted to 1.45 — film is graded for a screen you look at, and under that much blur an ungraded average is grey. |
| 2026-09-11 | A dissolve between ambient frames, not a cross-fade | Fading the old out while the new fades in leaves both part-transparent in the middle, and the composite dips about a quarter every change — on a 2.2 s wash that reads as a pulse in the corner of the eye. The new frame instead rises over the old at full strength underneath, so the light only ever moves between two colours. |
| 2026-09-11 | "Scrub thumbnails" is the off switch for the ambient light too, rather than a setting of its own | Both are the same key-frame seek over the share, from the same worker and the same cache. A switch that says "do not read extra data off the share while playing" would be lying if one of the two ignored it, and a second switch beside it would be two names for one cost. The note now says what it covers. |
| 2026-09-11 | The ambient light samples the VIDEO SURFACE eight times a second, not the scrub cache | The cache path (F17) was ten-second key-frame seeks over the share — the granularity a seek preview needs and far too slow for a light meant to move with the film. Reading the surface costs no network, and the rate is ours to choose. 8 Hz is where a blurred wash stops reading as steps and starts reading as live, at 7.5× less work than per-frame. `ui/player/AmbientSampler.kt`. |
| 2026-09-11 | Reading the surface needs a `TextureView`, so the surface type follows the Ambient light setting | A `SurfaceView` goes straight to the compositor and cannot be read back at all; a TextureView draws through the view hierarchy and can, at a real cost in how the video reaches the screen. That trade IS the switch: with the light off the player keeps the SurfaceView it has always had and none of the sampling runs. The view is found by walking down from the Compose root rather than constructed, because Media3's `ContentFrame` owns it and its sizing and content-scaling are worth more than a handle; the player has exactly one, and a null costs the static backdrop, not a crash. |
| 2026-09-11 | The light is a 32×18 bitmap, and the smoothing happens in those 576 pixels | Three things fall out of sampling small. Each sample is a 2 KB texture upload rather than a frame, which is what makes eight a second affordable. Blown up to a phone it is already most of the softness, so the blur drops from 56dp to 32 — and blur radius is the cost here. And the temporal smoothing can be an EMA blend (0.3 per sample, a cut settles in about half a second) instead of a cross-fade: a cross-fade animates a full-screen layer's alpha, which dirties the blurred layer sixty times a second and makes the blur the most expensive thing on the screen, where blending leaves exactly one redraw per sample. |
| 2026-09-11 | A transparent sample is dropped; a black one is not | A TextureView with no frame drawn on it reads back fully transparent, and letting that through blinks the light off every time a file loads. Five probe pixels tell that apart from a genuinely dark shot, which is opaque and passes — the room should go dark with the film. |
| 2026-09-11 | Ambient light is its own setting, not a rider on "Scrub thumbnails" | F17 put it there because both were the same seek over the share. They no longer are: one is network, the other is GPU readback and the surface type the video renders into. Two costs, two switches. It sits under Display, on by default, and its note says what it costs rather than only what it does. |
| 2026-09-11 | Brightness belongs to the `PlayerViewModel`, not the screen | The screen re-runs its window setup (`DisposableEffect(immersive, flex, orientation)`) on every layout change, and the brightness reset lived in that effect's `onDispose` — so entering full screen, or turning the phone, quietly undid the drag you had just made. The value now lives in the ViewModel, is applied by a `LaunchedEffect` whenever it changes, and is let go of by a `DisposableEffect(system)` that disposes exactly once, when the player leaves. Inline and full screen are one window; the window has one brightness. |
| 2026-09-11 | One `PlayerDetails` column for the phone, the unfolded portrait and the unfolded landscape's left column | The landscape column was a hand-built copy of the phone's `PortraitDetails` and had grown its own pill set (no speed pill) and an inline settings panel the phone never had, so folding the device changed what the player offered. The layouts now decide only where the folder goes (`showNext`); what the column holds is decided once. The inline settings panel is gone with it — the playback sheet is the one settings surface everywhere, reversing F10's "settings under the picture". |
| 2026-09-11 | One `PillRow` everywhere: speed · A–B (armed, or over the picture) · Chapters · rotation — gap — download · settings | Three chromes drew three different rows. The left group scrolls if it must (a phone at 411dp is at its limit), the right group never moves, so the two glyphs are always under the same thumb. The settings glyph moved INTO the row from beside it; the download pill is new and follows `TransferRepository.observeForFile` for the file on screen, so autoplay carries it to the next episode. Hold, not tap, cancels or removes: a single tap that could throw away ten gigabytes is not a tap anyone means. |
| 2026-09-11 | A glyph-only `PillButton` is a circle | With the text padding kept it came out 38 wide by 34 tall — a pill with nothing in the gap, which read as a mistake. Empty text now means width = height, centred glyph, no side padding. `progress` draws a determinate ring in place of the glyph for the download. |
| 2026-09-11 | `ScrubThumbnails.requestAll` — a batch queue beside the finger's conflated one | THE CHAPTER BUG: `request()` feeds a `CONFLATED` channel, which is right for a finger (skip what it passed) and wrong for a wall — twelve requests fired in a loop came out as two, the first and the last, and the ten chapters between stayed dark on the `skeleton` ground (#141414, which is what "black squares" were). The batch channel is `UNLIMITED`, drained by the same worker through a `select` biased to the finger's clause, with no neighbour prefetch (radius 0). The flex filmstrip takes the same path. |
| 2026-09-11 | Ambient light is switchable from the playback sheet too | The wash is the most visible thing on the player and its switch was two tabs away under Settings › Display. Same preference, second door — the pattern Decoder and Keep playing already follow. |
| 2026-09-11 | Library roots inside a share: `share_roots` (schema v5), `Share.roots`, the Choose folders drill-down | A share is often `media/` with `Films/`, `Series/` and a `Backups/` nobody wants walked, and the only choice was the whole share or none. Rows in `share_roots` narrow a share to chosen folders; NO rows means the whole share, so every existing install reads exactly as before. Picks may sit at any depth and at different depths from each other. Picking a folder turns the share on; toggling the share whole clears the choice. The picker lists straight off the server (`SourceRepository.listFolders`) because nothing is scanned yet, one level per nav key so back climbs out a level and Done pops them all. |
| 2026-09-11 | `rootsCover(roots, relPath)` is the one rule, and it gates every read of a narrowed share | The scan asks it before listing a folder and Browse asks it again on every folder you open — which is the part a first attempt missed: `refreshFolder` only special-cased the share ROOT, so opening an in-between folder in Browse re-listed it over SMB and pulled back the siblings the user had excluded. A path is readable only if it IS a chosen folder or sits under one. Unit-tested, including that "Films Archive" is not inside "Films" (only the separator says so). |
| 2026-09-11 | A narrowed share keeps the real tree, on a skeleton of unlisted rows | The first build hung every chosen folder directly off the share root, which put `Season 02` beside `Films` with "Series/Severance" thrown away — one `Season 02` looks much like another, and it is what the owner reported as the drill-down "only working at the surface level". `refreshSkeleton` now writes a row for each folder on the way down and parents each pick under its real parent. A skeleton folder is a row and nothing more: never listed, no files, classified COLLECTION because it holds folders. It hands back ALL its children so the scan's queue walks the skeleton (at zero network cost) down to the chosen folders and lists those for real. `FolderDao.upsert` had to start merging `parentId`, since a row can now change parent without changing path. |
| 2026-09-11 | The full-screen player reads bottom-up like Spotify's, and the ±10s keys are gone | Order is now header (back, collapse) · picture · title and meta · timeline · shuffle / previous / play / next / repeat · the pill row. The title moved off the top edge to sit on the timeline, where the controls for it are. The two seek keys went because they were two of five cells duplicating the gesture everyone already uses — a double-tap on either side, which stacks and needs no aiming — and losing them is what makes room for shuffle and repeat without the row growing. `Transport(modes = true)` draws the five; the 16:9 inline strip keeps the three, because five cells crowd a picture that small. |
| 2026-09-11 | Shuffle and repeat are real controls, not just buttons in the right places | `PlaybackState.shuffled` / `repeat`, `PlaybackSession.setShuffle` / `setRepeat`. Shuffle makes the running order "this file, then everything else scrambled", so what you are watching is not interrupted; off drops the queue back to folder order. A queue handed over by Play all › Shuffle reads as off, because the order it was made from is gone and there is nothing to put it back to. Repeat ONE is handed to ExoPlayer (`REPEAT_MODE_ONE`), which loops without ever reaching an end, so autoplay never sees one and nothing else has to know; ALL is ours and only changes what `upNext` answers. `PlaybackState.upNext` / `upPrevious` wrap the running order's ends while repeating all, and repeat-all also arms autoplay on its own — it was set on this film, about this folder. |
| 2026-09-11 | Both rows of the full-screen player are three groups, with the middle centred on the ROW | Previous · play · next sit dead centre with shuffle and repeat at the far edges; under them A–B and the rotation lock at the start, Chapters centred, download and settings at the end. Two weighted cells either side of the middle group split whatever is left equally, so the centre is centred on the screen rather than on whatever happens to be beside it — a `Box` with three alignments would have overlapped once the A–B pill grew its label. The modes go to the edges because they are a different kind of control: they change what the row will do next rather than doing it now, and the distance says so without a label. The speed pill is gone — it only ever opened the playback sheet, which is one tap away at the end of the same row, so it was a second door to one setting. There is now no readout of a non-default speed on the player; the sheet is the only place it shows. |
| 2026-09-11 | The A–B pill is on the row at every width, and the sheet's span labels size themselves | The pill used to appear under the picture only once a loop was already running — a width compromise from when the row was one scrolling group with a speed pill in it. The effect was that a loop could only be STARTED in full screen, so the feature did not exist for anyone who never went there. In the sheet, the three time labels under the span bar lived in a fixed `height(14.dp)` box holding an 11sp line, which cut the bottom off every number; a clipped digit reads as a rendering fault rather than a tight layout. The box now sizes to its text, and each label is held a label's width clear of the last so a loop running to the end no longer prints B on top of the runtime. |
| 2026-09-11 | Artwork is generated by a background walk after a scan, not only when a tile scrolls into view | Every picture is a key-frame seek over SMB costing one to three seconds, so the library filled in slowly while you looked at it and a title's backdrop cost another wait on top. `ArtworkWorker` walks a share when its scan finishes — folders first (the Library wall), then files newest first (Home's Newly added) — as a foreground job with a progress notification carrying a Stop action. It skips whatever is already cached, so a rescan that found nothing new costs a pass over the table and no network, and it returns `Result.retry()` the moment the share stops answering rather than grinding through a thousand files that will all fail. |
| 2026-09-11 | `ArtworkRepository.prefetch` writes ALL THREE kinds from one extraction | The on-demand path grabs a frame for the stills when a tile appears and grabs the same frame AGAIN for the backdrop when the title is opened: two trips over the share for one picture. The grab is already 1334x750, comfortably bigger than a 1280x720 backdrop, so the second trip was never needed — it was only ever an artefact of `resolve` taking one `kind` at a time. Measured on the emulator: before the walk, backdrops lagged the stills 12 to 21; after, all three kinds sit level. |
| 2026-09-12 | **A folder pick can have holes: "this folder, minus these"** (`Selection.excludedFiles` / `excludedFolders`, schema v7) | The owner's report: pick a season, drill in, and every episode is checked but none can be unchecked — the only way to leave one behind was to unpick the season and re-pick the rest by hand. The model was purely additive; a file inside a pick had no way to be "out". Exclusions are the fix rather than exploding the folder into file picks, because a folder pick is also the promise to fetch files the app has never seen — explode it and an unscanned season silently loses them. Tapping a checked row inside a pick now EXCLUDES it, tapping again puts it back, and the same holds for a subfolder (subtree and all); a file under an excluded subfolder can still be picked directly. Three things fall out. Nothing is inert any more — the grey locked box is gone, every row is live, which is a simpler screen. "Select all" uses `include*` rather than `toggle*`, because on a screen of covered rows toggling would be exclude-all. And the exclusions have to reach the download job or the walk fetches them anyway: two columns on `download_picks` (comma-joined ids, newline-joined paths — a pick lives for one drain, so a child table would outlive the only thing it describes), the walk is told not to enter excluded subtrees (`listSubtree(prune =)`), and excluded ids are dropped before rows are queued. Picking a folder afresh, or unpicking it, drops the exclusions beneath it: they were only ever "minus these" against that pick. A folder with holes says "All but 2" from the level above, since it would otherwise look exactly like one without. |
| 2026-09-12 | **A fresh build is copied ONTO the live state, never the other way round** (`withWall`, `withRows`) | The bug the owner reported as "the selection disappears when you drill into a folder". Library's wall collector took the freshly built `LibraryUiState` and copied an allowlist of transient fields (sort, view mode, the device page…) back onto it; anything not on the list was silently reset every time Room re-emitted the wall — which is constantly, since a listing landing, an artwork row arriving or a progress tick all count. `selection` was not on the list, so a multi-selection lasted until the next emission. Holding a tile poked the store and brought it back for a moment, which is what made it look like the hold "re-engaged" something. The device collector had the identical shape for the device selection, where an in-flight download re-emits every 500 ms. Inverted: the built fields are copied onto the existing state, so a field the merge does not know about survives by default. That property — a new transient field cannot be forgotten — is worth more than the two it fixes today, and it is why the merge is a named, unit-tested function rather than an inline `copy`. Browse was already right; it updates with `it.copy(...)`. |
| 2026-09-12 | Back never cancels a selection, on any screen | It cancelled in two guises and both were wrong. Browse's contextual bar grew a back arrow that cancelled — but the resting bar has none, because there the pill and the system gesture navigate, so the arrow was both new chrome and a third meaning for "back" on one screen; it is gone. Library's contextual bar keeps the screen's real `onBack`, which walks out of the collection with the picks intact. Cancelling is the X in the bar above and Cancel in the bar below, which is what the owner asked for and now the only thing that does it. |
| 2026-09-12 | The On-this-device page gets its own selection, and it is NOT `SelectionStore` | Same gesture, opposite act: one picks things to fetch, the other picks copies to delete. `SelectionStore` is app-scoped because a download pick three folders deep must survive walking the tree; a removal cannot leave the tab it is on, so it lives on `DeviceUiState.picked` as a plain `Set<Long>`. Sharing one store would also let a batch mean "download these" and "delete these" in the same breath. Switching tab ends whichever selection you walked away from — the nav graph's rule, one level down. |
| 2026-09-12 | Every destructive path on that page asks first, and the dialog stores WHAT it is asking about | "Clear all" is one tap from a resting page and a selection's Remove can be tens of gigabytes of re-download, so both confirm through one dialog that names the count — the two acts are visibly different. `confirmRemove` holds a `RemoveTarget` rather than a number, because the first version inferred "clear all" from an empty selection: deselect your last row, tap through, and it would have wiped the device. Intent stored, not inferred, and guarded again at the point of action. |
| 2026-09-12 | "Clear all" freed the word, so the failed section's button became "Clear failed" | The page now has a page-level Clear all beside "Ready offline", mirroring where the failed section already put its own action. Two buttons both saying "Clear all" and meaning different amounts is a trap, and the one under "Failed · 30" is the narrower of the two. Clear all takes the arriving copies with it: a Clear all that left a download running would be refilling the page it just emptied, so the queue is stopped before the files are deleted rather than after. |
| 2026-09-12 | A copy is one target; the check rides on its thumbnail | The row split that folders need does not apply here — a copy has nowhere to walk into — so the whole row picks and the mark sits over the 60dp thumb behind a scrim rather than taking a column of its own and reflowing the three sections. The row's own trailing action (Cancel, Try again) stands down while selecting, since a row cannot be both picked and individually acted on. |
| 2026-09-12 | **A folder in a selection is two targets: the box picks, the row opens** (fixes a first-build mistake) | The first build made the whole row pick while selecting, which cost the user the ability to walk the tree — and walking the tree is the feature. Worse, a picked folder swallows everything under it, so the first tap made the rest unreachable. The Choose-folders picker had already hit this exact wall and its `FolderRow` doc comment says so; the fix is to reuse that split rather than invent a second answer. `RowLeading.PickBox` + `ListRow(onLeadingClick =)` on rows, `MediaTile(onCheckClick =)` on tiles, and the marker's target is 38dp around a 20dp mark because a tap that can add a hundred files should not need aiming. A file keeps one target: there is nowhere to walk into. |
| 2026-09-12 | Back navigates; it does not cancel the selection | The other half of the same mistake. A `BackHandler` that ate the press to leave selection mode meant you could not climb back out of a folder without losing the picks, which again makes a deep pick impossible. Back now does what it always did and the selection comes with it. What ends a selection is arriving somewhere that cannot act on it — only Browse, Library and Search draw the bar — which is ONE rule in the nav graph covering back, the pill and a push into the player, where three separate rules had already started to drift. |
| 2026-09-12 | A folder says how many picks are inside it | Falls out of the split: once picking and walking are different taps, a folder you walked into and picked inside looks exactly like one you never opened. `SelectionUiState.picksInside` reads the picked paths, and the row says "3 picked inside" — the picker's own signpost, needed here for the same reason. |
| 2026-09-12 | The nav dot is red | It was drawn with `UnwatchedDot`, which paints `ink` — and on a nav cell white is what "you are here" means, so the dot said the wrong thing in the one place it had to be unambiguous. The accent is the app's attention colour and a notification dot is attention. |
| 2026-09-12 | Espresso pinned forward to 3.7.0, and the UI gets Compose instrumented tests | `compose-ui-test-junit4` brings Espresso **3.5.0** in transitively, and 3.5.0 reflects on `android.hardware.input.InputManager.getInstance`, which **API 37 removed** — so every instrumented test died in `Espresso.onIdle` with a `NoSuchMethodException` before running a single assertion. Found while trying to verify this feature on the emulator, where the same API-37 shift also has the `uiautomator` shell command SIGKILLed and argent's accessibility tree frozen on whatever screen it first captured. `app/src/androidTest/SelectionChromeTest` is the answer to that: Compose's test harness drives the composition directly, so the hold-versus-tap distinction, the inert covered row and the disabled Download are verified repeatably on the device without depending on the platform's accessibility service. |
| 2026-09-12 | **One queue-draining worker, not one job per file** (reverses the Phase 5 row above) | Twelve picked files meant twelve `TransferWorker`s: twelve parallel 1 MiB SMB read loops through the same box the player streams from, so the film you actually wanted arrived last, and twelve foreground notifications all claiming id 42, each overwriting whatever the last one wrote. `TransferQueueWorker` copies one file at a time and asks Room for the next pending row after each one — which is also what makes `ExistingWorkPolicy.KEEP` correct rather than lossy: rows added mid-drain join the job already running, and the worker re-checks for pending work before returning success to close the one race that leaves. The cost is per-file retry granularity: a share that drops now pauses the whole batch rather than one file, which is the right answer anyway, since the cause is the share. |
| 2026-09-12 | The batch's progress bar measures BYTES, in permille | A batch whose first file is 40 GB and whose other eleven are 200 MB each would sit at "1 of 12" for an hour if files were the unit, which reads as a stalled download rather than a working one. Permille rather than percent because `setProgress` takes an integer max, and 4 MiB into a 4 GB file moves a 1000-step bar where it rounds to nothing on a 100-step one. The file count stays in the text, where it answers a different question. |
| 2026-09-12 | Notification ids live in one object (`data/RegolithNotifications`) | `ArtworkWorker` and `TransferWorker` each hard-coded 42 in their own file, so an artwork pass during a download meant two foreground jobs fighting over one row in the shade. Collecting the ids is the fix; renumbering the collision would have left the next worker free to pick 43. |
| 2026-09-12 | The download selection is in memory, app-scoped, and a SINGLETON | `share_roots` is persisted because it defines the library's shape and the scan reads it back; a selection is a modal gesture inside one visit, and an app returning from a process kill already in selection mode — a count, a red Download button, no memory of how it got there — would be a bug that looked like a feature. But it could not live in `BrowseViewModel` either: that is `@AssistedInject`-created per `folderId`, so drilling from `Series/Severance` into `Season 01` builds a new one and would drop the picks on exactly the gesture the feature exists for. One `@Singleton SelectionStore` is also what lets Browse, Library and Search contribute to one batch without knowing about each other. |
| 2026-09-12 | A picked folder is walked over SMB at download time, and the walk is part of the job | A folder the user has never opened has no `media_files` rows, so expanding from Room alone would silently download nothing from it — the owner's call, and the one that always works. The walk reuses `LibraryRepository.refreshFolder`, the same call `ScanWorker` drives, so a share narrowed to chosen folders cannot be widened by a download. The cost is real and made visible rather than hidden: the count is unknown until the walk finishes, so the bar reads "Counting…" / "At least N" and the notification reads "Finding files… 34" before it can show a bar. Files are queued per folder as they are found, so copying starts after the FIRST folder rather than the last, and a share that drops mid-walk keeps its `download_picks` row and resumes. |
| 2026-09-12 | Picked folders are a Room table (`download_picks`, schema v6), not worker input data | By the time Download has been tapped the app has promised to fetch everything inside the folder, and that promise has to survive the process dying mid-walk. Input data would also be dropped by `KEEP` when a second batch arrives during a drain. Additive table, `@AutoMigration(5, 6)`, exactly as v4 added `transfers`. |
| 2026-09-12 | The hold is the platform's ~500 ms, not the two seconds first asked for | At two seconds with nothing moving under the finger the row reads as dead, and most people let go around 800 ms — so the gesture would mostly read as "long press does not work". 500 ms is the reflex Gmail, Photos, Files and every file manager have trained, and `combinedClickable` gives it for free along with the ripple, the accessibility action and the semantics that a hand-rolled `detectTapGestures` would drop. `SELECT_HOLD_MS` records the number and the route to changing it (a `LocalViewConfiguration` override), so raising it stays one line. |
| 2026-09-12 | A pick swallows what is beneath it, and a covered row is checked and INERT | The "Choose folders" rule, reused rather than reinvented: picking `Films` drops a pick of `Films/Arrival (2016)`, so a batch can never count a file twice, and a row inside a pick is drawn with its check and does nothing on tap, because offering the tap would say otherwise. One subtlety the unit tests caught: for a FOLDER the covering test is exclusive (a picked folder is not "covered" by itself, so it can still be unpicked), but for a FILE it is inclusive — a file directly inside a picked folder is coming either way. Two functions, `coveredByAncestor` and `coversFileIn`, rather than one with a flag. |
| 2026-09-12 | The tally is an estimate from folder aggregates; the exact id list is built once, at Download | `FolderEntity.fileCount`/`byteCount` are direct per-folder aggregates already written at listing time, so summing every folder in a covered subtree is exact and costs one Flow that already exists. Expanding to real file ids on every toggle would mean `WHERE folderId IN (…)` over thousands of ids, past SQLite's 999-variable limit. `filesUnder` chunks at 900 for the one expansion that has to be right. |
| 2026-09-12 | The Settings dot has no "mark as read" | It is lit while anything is queued, copying, paused or failed, and every way of clearing it already exists — the queue draining, a retry, or Clear all. "Unseen since you last opened Settings" would need a new preference and a dot that can get stuck on; this one cannot. Accent rather than ink, because white on a nav cell is what "selected" means. |
| 2026-09-12 | Downloads get a Settings row, not a destination | The design says it outright ("Downloads live here rather than in a tab of their own"), and Library › On this device is already built. The row is the answer to "a dot appeared, what is it": the same 8dp status dot the share rows use, the live count, the artwork walk's own inline bar reused verbatim, and Stop / Open. Two background jobs reporting themselves two different ways would read as two features. |
| 2026-09-11 | The walk may hold only one of the two extraction slots | `prefetchSlots = Semaphore(1)` is taken before the shared `extractionSlots = Semaphore(2)`, so whatever is on screen always has a slot. A background job that fills both would make the app feel slower than having no cache at all, which is the opposite of the point. The walk also joins the same in-flight entry as a UI stills request, so a tile scrolling into view waits for work already running instead of starting it again. |
| 2026-09-12 | User-written chapters are a Room table (`user_chapters`, schema v8) keyed by file id, with an external-content FTS index over their names | The same footing as playback progress: the id is stable across rescans (G3) and the rows cascade away with the share. A file's chapters are written as one set in one transaction (`replaceForFile`), never a row at a time, because a chapter's meaning depends on its neighbours. Only `title` is indexed, so an unnamed mark is not findable — it has no words to find. Additive, `@AutoMigration(7, 8)`. |
| 2026-09-12 | **Corrects the 2026-09-09 search row.** `ftsMatch` writes the star INSIDE the quotes: `"samou*"`, not `"samou"*` | The outside form is FTS5 syntax; on FTS4 (SQLite 3.44 under Robolectric, and the CLI) it quietly matches the whole word only, so "samou" never found "Samouraï" and the migration test passed only because `a` is a whole token of `a.mkv`. Found while writing the chapter-name search, which shares the helper. `"lin* ope*"` is FTS3/4's documented prefix-in-phrase form; quoting each word still keeps `or`, `not` and `-` from acting as operators. |
| 2026-09-12 | Chapters the user writes beat the file's own markers, which beat the even split; `chapterSource` names which is showing | The owner's call: a chapter they marked is the one they meant, even on a file that came with its own. The order is one getter on `PlaybackState`, and revert is a delete — the next kind down fills in, so nothing has to be kept aside to restore. The sheet's subtitle ("Yours · 4 chapters") says whose they are, because it changes what the names mean. |
| 2026-09-12 | The chapter editor lives in the player, seeded from the chapters showing now; the draft is ViewModel state | Marking a place needs the scrubber, and the scrubber is in the player; a screen of its own would mean a second timeline to keep in step. Seeding from the current list means there is always something to rename rather than a blank to fill; 0:00 is pinned because a film starts in a chapter. The draft is editing state, not playback state (G4), and it outlives a rotation, which a `remember` in the screen would not. Edit pauses the film so the playhead holds still. |
| 2026-09-12 | The scrubber draws chapters as segments with a 2dp gap, and the preview names the part | Ticks over one bar said "there is a boundary here"; segments say "you are in this part", and the finger's segment growing (YouTube's gesture) says it before the preview's name does. Every layer — buffered, loop, fill — respects the gaps, so the timeline reads as one drawing. Even divisions keep saying "Part n", the way the sheet does. |
| 2026-09-12 | Points of interest are their own Search group, above the files, and never part of a selection | A red match inside "The heist" under a film's thumbnail must not be mistaken for a match in a filename, so the group has its own eyebrow and each row a tag. A moment is a place, not a file: nothing to download, so long-press does nothing on it and it only shows under "All" — the other filters are about the file. A tap opens the player at that time via `Player(fileId, startMs)`, a key that already existed. |
| 2026-09-12 | `ConfirmDialog` is one component, and it sets `testTagsAsResourceId` itself | The Disconnect dialog was about to be copied three times (revert, discard, clear all); CLAUDE.md calls two copies a bug. Promoting it also surfaced that a Compose `Dialog` is a window of its own, which the root Scaffold's `testTagsAsResourceId` never reached — so no dialog button had ever had a resource id. |
| 2026-09-12 | **Reverses the scrubber-flags half of the row above.** Marks are moved on the editor's own 64dp strip with 30×40dp handles; the picture's scrubber only scrubs; one mark is editable at a time | Owner feedback on the first APK: "dragging start points is very finicky and easy to mess up". A flag on a 3dp track competes with the scrub gesture, so a near miss scrubbed. Big handles on a strip that exists only to hold them cannot be missed, and locking every other mark while one row is open (the owner's must-have) means a drag can only ever move the thing you opened. The typed Start field is the precise path; it commits on Done or on losing focus, never per keystroke, and names the allowed range when refused. The open row lifts to `lifted` #1C1C1C with a `liftedBorder` hairline so it reads apart from the list. Mock approved as-is. |
| 2026-09-12 | User chapters live in a sidecar next to the film (`<basename>.chapters.txt`, mkvmerge simple format); the phone's table is its cache and index | The owner wants the chapters to travel with the film and to be written by a desktop app later. mkvmerge's format is what a text editor, a desktop tool and `mkvpropedit` all understand; not dot-prefixed because listings hide dot-files; not inside the container because rewriting a multi-gigabyte MKV over SMB in place is how a film gets corrupted. `docs/CHAPTERS.md` is the specification. |
| 2026-09-12 | The sync rule is newest-wins, whole; writes are rows first, file in the background; Settings › Clear is the phone only | A merge of two chapter lists is nonsense, and a prompt is a question nobody can answer well; a lost edit is rare, visible (the sheet says the share replaced it) and easy to redo. Done waiting on a sleeping NAS would make Done fail. The owner's rule for Settings: it clears the local cache and never the share; Revert on the sheet is the one action that deletes a file, and it says so. |
| 2026-09-13 | A downloaded film gets a local copy of its chapter file, rewritten on every edit | The owner's rule: an edit on a downloaded film updates the local and the network copy. The rows already served offline play; the file beside the copy makes the phone's copy durable and readable by anything that can see the downloads directory, and the download step fetches the share's file so a fresh download is complete. Removing the copy removes the file; Settings › Clear removes every local one, since it clears the phone. |
| 2026-09-13 | Tapping the current tab's cell brings that tab back to its top | Library three folders deep, tap Library: the expectation from every tab bar since iOS 2, and the app used to do nothing. `navigateToTab` now checks whether the stack's top is the tab's root key rather than whether the tab is current, so a stray tap at the top still does nothing. |
| 2026-09-12 | ACCESS_DENIED maps to `Forbidden`, even though jcifs raises it as an auth exception | Seen on the first write against the fixture's read-only share: "Sign-in failed" for an account that had just listed and read the share. `Forbidden` was already documented as "signed in, refused this request"; it is now what the status actually produces, so a read-only share reads as read-only. |

## Phase plan

| Phase | Goal | Contracts fixed |
|---|---|---|
| 0 | Scaffold: theme, components, nav shell, DI, QA loop | G7, G8, G9 |
| 1 | Connect → browse → play (manual `smb://`, share picker, raw folders, playback, resume) | G1, G2, G3, G4, G6 |
| 2 | Player complete: chrome, speed, decoder, A–B loop, gestures, playback sheet | `PlayerUiState`, `ScrubThumbnails` interface |
| 3 | Artwork pipeline, Browse grid, Title Detail, scrub previews (v1: on demand) | G5, `FrameSource`, `ScrubThumbnails` |
| 4 | Library scan, filename parsing, search (FTS), Home, sort, collections, Settings shares | parse rules, progress-in-Room, `FolderKind` |
| 5 | Downloads and offline ("On this device"), out-of-reach states, local playback | `TransferScheduler` seam, `transfers` rows |
| 6 | Design audit (1:1 against the frames), LAN discovery, splash + onboarding, gesture map | design tokens in `ui/theme`, `rg_ic_*` icons |
| F0–F5 | Foldable inner display: window shape, nav rail, Library list-detail, Browse two panes, player fully open and flex mode (`FOLDABLE_PLAN.md`) | G10, `WindowShape`, `LocalNavPillInsets` |
| F6 | Round two: reset on the divider, Browse as a list pane, the retracting rail and its spine, autoplay next (`FOLDABLE_PLAN.md`) | `NavRailSpine`, `railHidden` / `autoHideRail` / `autoplayNext` preferences |
| F7 | Round three: the rail's own collapse button, autoplay's second switch, a visible middle drag, Play all / Shuffle on a real queue, moving tiles (`FOLDABLE_PLAN.md`) | `PlaybackState.queued`, `RegolithKey.Player.queue`, `ArtworkKind.PREVIEW`, `movingTiles` / `autoplayImmediately` preferences |
| F8 | Round four: 15/70/15 gesture zones, the player's rotation lock, per-share scan and disconnect | `SIDE_ZONE`, `PlayerOrientation` |
| F9 | Round five: the landscape two-pane player, the midpoint frame grab, folder mosaics, moving tiles removed (`FOLDABLE_PLAN.md`) | `SideColumn`, `ArtworkStore.GENERATION`, `ArtworkSource.MOSAIC` |
| F13 | Round nine: the split is fixed at 50/50 and the divider is gone; the rail resizes its column again; landscape always plays two-pane (`FOLDABLE_PLAN.md`) | `EVEN_SPLIT` |
| F12 | Round eight: drawer panes on both sides, the rail's honest inset, chapter stills, Previous/Next, "Open with Regolith" (`FOLDABLE_PLAN.md`) | `DrawerPane`, `Transport`, `PlaybackState.chaptersScanned`, `RegolithKey.Player.external` |
| F11 | Round seven: even-division chapters, Media3 frame extraction, the on-demand backdrop, the asymmetric split divider (`FOLDABLE_PLAN.md`) | `ChapterMarks`, `FrameGrabber`/`Media3Frames`, `ArtworkKind.BACKDROP`, `DismissiblePane` |
| F10 | Round six: settings under the picture and the folder beside it, one column in wide portrait, chapters, bottom sheets everywhere, ambient letterbox bars, the runtime fallback (`FOLDABLE_PLAN.md`) | `ChapterParser`, `ChapterRepository`, `DurationProbe`, `PlaybackState.chapters` |
| P1 | Player refinements: shared brightness, ambient light in the sheet, the chapter-wall fix, download in the pill row, circular glyph pills, one details column for every width | `ScrubThumbnails.requestAll`, `PlayerViewModel.brightness`, `PlayerDetails` |
| P7 | Background artwork walk after a scan, with a progress notification; all three kinds from one frame grab | `ArtworkWorker`, `ArtworkPrefetcher`, `ArtworkRepository.prefetch` |
| P3 | Full-screen player restructured (Spotify order), seek keys removed, shuffle and repeat added | `RepeatMode`, `PlaybackState.shuffled`/`repeat`/`upNext`/`upPrevious`, `Transport(modes)` |
| P8 | Multi-select in Browse, Library and Search; one batched download behind a queue worker, with an SMB discovery walk, an aggregate progress notification and a Settings dot | `SelectionStore`, `Selection`/`pathCoveredBy`/`coversFileIn`, `SelectionPresenter`, `download_picks` (schema v6), `TransferQueueWorker`, `QueueProgress`, `RegolithNotifications`, `NavPill(dots)` |
| P2 | Library roots inside a share: the Choose folders drill-down at any depth, on a skeleton of unlisted rows that keeps the share's real tree | `share_roots` (schema v5), `Share.roots`, `rootsCover`, `refreshSkeleton`, `RegolithKey.AddServer.Folders` |
| P9 | User chapters: mark and name chapters in the player (marks on their own strip, one open at a time, typed start time), segments and names on the scrubber, points of interest in Search, clear-all in Settings | `user_chapters` + `user_chapter_fts` (schema v8), `UserChapterRepository`, `ChapterDraft`, `ChapterSource`, `PlaybackState.userChapters`/`chapterSource`, `ChapterEditorContent`/`MarksTimeline`, `ChapterDraft.parseClock`/`bounds`, `SearchHit.Moment`, `ConfirmDialog` |
| P10 | Chapter sidecars: the durable copy of a film's user chapters is `<basename>.chapters.txt` beside it on the share; the scan imports, Done writes, newest wins; Settings clears the phone only | `ChapterSidecar`, `SmbGateway.write/rename/delete`, `SidecarWriter`, `chapter_sync` + `shares.writeChapters` (schema v9), `ChapterSyncRepository`, `ChapterSyncWorker`, `CredentialSource`, `ChapterSyncState` |

The design (`design/docs/SMB Video Player Design/`) is the source of truth
for every screen and state. Section 12 of it lists features deliberately not
built (cast/PiP, subtitle and audio tracks, sleep timer, skip intro, private
folder); G4 keeps the door open for them.
