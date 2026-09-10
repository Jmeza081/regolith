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
             ── PlayerViewModel ── PlaybackSession ── ExoPlayer (StateFlow: rebuilt on HW/SW switch)
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
PlayerScreen ── Chapters pill ── PlaybackState.chapters ── ChapterRepository ── ChapterParser (pure: Matroska Chapters / MP4 chpl)
PlayerScreen ── Scrubber.onScrub(ms) ── PlayerViewModel ── ScrubThumbnails.request(ms)
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

## Downloads and offline (Phase 5)

```
TitleDetail "Keep on this device" ── TransferRepository.start(fileId)
     └─ transfers row (QUEUED, bytesDone = 0, localPath = "{id}.{ext}")
     └─ TransferScheduler.enqueue(fileId)          interface; WorkManagerTransferScheduler today
          └─ TransferWorker (foreground, dataSync, unique per file, network constraint, exponential backoff)
               ├─ StorageCheck.hasRoom(free, total, done)?  no → FAILED · NO_ROOM · shortfall in the row
               ├─ gateway.open(...).readAt(bytesDone …) → append to "{localPath}.part"; row updated every 500 ms
               ├─ SmbFailure → PAUSED · SHARE_DROPPED, Result.retry() ("resumes on its own"), server marked unreachable
               └─ done → rename .part → DONE

PlaybackSession.load ── MediaUriResolver.playableUriFor(fileId)
     └─ finished copy?  file:// (DefaultDataSource reads it)  :  regolith://file/{id} (SmbDataSource)

Reachability: every SMB caller (listing, scan, transfer) marks the server reachable or not on `servers`;
Library's Network tab shows the out-of-reach card from that column, and "Try again" is one root listing.
```

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
| 2026-09-10 | Both panes are drawers: measured at a floor width, placed at the start, clipped | The divider was still resizing *content* rather than the *view* of it. Each pane now measures its content at a floor — the wall at rail + 364dp, the detail at 360dp — and places it at the start edge, so a title that fitted on one line still fits on one line while half of it is behind the divider. A `Layout` rather than a `Box`, because a Box asked to hold something wider than itself does not promise where it puts it, and the overflow was leaving on the OUTER edge (cutting the first tile) instead of under the divider. Only the pane that may be dismissed also fades. |
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
| 2026-09-10 | The split divider stops on the left and dismisses on the right | Dragged either way it used to resize the content on both sides, and at the old 0.85 anchor the detail became a column of one-word lines. The two ends now do different things, the way every foldable mail app does it. LEFT stops at the wall's minimum (rail + 240dp, two poster columns): a two-pane screen with no list is just the detail with a stripe down the side, and the rail is already how you leave. RIGHT goes all the way and the detail is dismissed — `DismissiblePane` lays its content out at a fixed 260dp however narrow the pane gets and clips the overflow, so the words never reflow on the way out; it slides under the divider and fades, and below 40dp it is not composed at all. The handle's reset button brings it back. |
| 2026-09-10 | The letterbox bars carry the film's colour instead of black | Verified on the Fold, and the finding is the useful part: media3's `ContentFrame` sizes the video **surface** to the content, so the bands a 16:9 film leaves in a near-square window are ordinary window pixels and ours to paint. `AmbientGlow(spill = true)` fills them with the same blurred poster the windowed player already sits on, knocked back behind a flat scrim so it reads as spill rather than a second, blurrier picture. Black stays underneath, so a film whose poster never loaded looks exactly as it did. **Bars burned into the frames themselves — a 2.39:1 film encoded into a 16:9 raster — cannot be touched: those pixels are the picture.** The wash is the poster, not the live frame; sampling the playing frame would need a TextureView or an ImageReader per frame. |
| 2026-09-10 | Moving tiles removed (reversing the F7 entry below) | The sheet's cells are 240×135, which is what made a wall of them affordable, and at tile size that reads as a smeared thumbnail beside the crisp 500×750 still it plays over. The still is the better picture and it was already there. Removing it also takes back the twelve seeks per file and the ~3× disk. `ArtworkKind` is back to the two stills. |
| 2026-09-10 | A frame grab comes from the MIDPOINT of the runtime, not 10% | 10% is where a title card or a studio ident usually still is, so a folder of episodes sharing an intro produced a folder of identical tiles — the failure the whole artwork pipeline exists to avoid. Half way in is past every intro and well short of the credits. `ArtworkStore.GENERATION` is the migration: bumping it drops every row the app GENERATED (frame grabs, mosaics, placeholders) and leaves everything found on the share alone, so an existing library rebuilds itself on demand instead of needing Settings › Media › Clear. |
| 2026-09-10 | A folder with no sidecar gets a 2×2 mosaic of its contents, composed once per kind | A collection with no `folder.jpg` was a grey wedge, which tells you nothing; four frames from the films inside tell you what it holds. Composed separately at 500×750 and 320×180 rather than once and cropped — a 16:9 grid squeezed into a 2:3 poster slices the outer column in half. The frames are the expensive part and they are shared. A folder holding fewer than four videos takes several frames from each; a show folder walks down into its seasons (breadth-first, 24 folders max) to find any at all. All-or-nothing: a grid with a hole in it looks broken where a plain placeholder does not. A scan that finds a sidecar has appeared drops the mosaic row (`ArtworkRepository.onFolderListed`), so adding `folder.jpg` and rescanning replaces it. |
| 2026-09-10 | ~~Moving tiles are a sprite sheet of stills, not video and not an animated image~~ (removed, see above) | Playing the real files fails on decoders, not bandwidth: the inner-display wall shows eighteen tiles, and a phone will not give you eighteen hardware decoder instances — Media3 fails hard rather than degrading when they run out. An animated WebP or GIF is impossible for a different reason: Android can DECODE both and ENCODE neither, with no public API. So `ArtworkKind.PREVIEW` is twelve 240×135 frames packed into one 960×405 JPEG, drawn a cell at a time at 4fps. One image is one Coil entry, one decode and one memory-cache line per tile, where twelve frames would be twelve of each. Measured: 42KB a sheet against 15KB for a title's poster and thumb together. |
| 2026-09-10 | Previews are generated lazily, one at a time, and the setting defaults off | A sheet is twelve key-frame seeks over SMB against a thumbnail's one, through the same connection playback reads from, so `previewSlots` is a semaphore of ONE and the tile asks for nothing until it has been on screen for 700ms — flinging through a wall queues no work, because a tile that scrolls past leaves the composition and cancels its own request. Off by default so a library that never turns it on pays nothing. `ArtworkKind.stills` exists so the frame-grab and sidecar paths, which write both still kinds in a loop, do not try to write a sheet from one frame. |
| 2026-09-10 | The picture's zones are 15/70/15, not thirds | Brightness and volume were going off by accident, because a third of the picture each is a lot of picture for two gestures you rarely want and cannot undo. They now take 15% down each edge and reaching them takes aim; the middle 70% keeps the full-screen drag, the dismiss and play/pause, which are the ones worth having under any thumb. `SIDE_ZONE` is one constant and the gesture map is laid out from it, so the picture you are shown once is the map you are actually using. The cost, accepted deliberately: double-tap-to-seek shares those zones, so the seek targets narrowed too. Splitting taps from drags was rejected — a map where a tap and a drag at the same point mean different zones cannot be drawn, let alone learned. |
| 2026-09-10 | The player has a rotation lock, and it says when the system will ignore it | `PlayerOrientation` (Auto / Portrait / Landscape) sits with Speed in the playback sheet, remembered across films because a lock you set every time is not a lock. Android 16 stopped honouring `requestedOrientation` on large screens: a device whose SMALLEST width is 600dp or more decides for itself. Verified on the Fold — locking Landscape rotates the cover screen and does nothing on the inner display. So the control greys out and says why there, rather than pretending. Smallest width, not current width: a phone turned sideways is a wide window and obeys perfectly well. |
| 2026-09-10 | Every share carries its own Scan and Disconnect | They used to be one pair of buttons under the whole card, and Disconnect took `servers.first()` — so with two NAS boxes connected there was no way to remove the second one at all. Each row now ends in its own 40dp scan and disconnect actions (the confirm dialog was always per-row; only the button that opened it was wrong). "Add a share" is promoted from a chevron row inside the card to the section's red CTA, which is what the screen is for when nothing is connected. "Scan all" survives only when there are two or more shares, where it saves taps rather than duplicating the row above it. |

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
| F12 | Round eight: drawer panes on both sides, the rail's honest inset, chapter stills, Previous/Next, "Open with Regolith" (`FOLDABLE_PLAN.md`) | `DrawerPane`, `Transport`, `PlaybackState.chaptersScanned`, `RegolithKey.Player.external` |
| F11 | Round seven: even-division chapters, Media3 frame extraction, the on-demand backdrop, the asymmetric split divider (`FOLDABLE_PLAN.md`) | `ChapterMarks`, `FrameGrabber`/`Media3Frames`, `ArtworkKind.BACKDROP`, `DismissiblePane` |
| F10 | Round six: settings under the picture and the folder beside it, one column in wide portrait, chapters, bottom sheets everywhere, ambient letterbox bars, the runtime fallback (`FOLDABLE_PLAN.md`) | `ChapterParser`, `ChapterRepository`, `DurationProbe`, `PlaybackState.chapters` |

The design (`design/docs/SMB Video Player Design/`) is the source of truth
for every screen and state. Section 12 of it lists features deliberately not
built (cast/PiP, subtitle and audio tracks, sleep timer, skip intro, private
folder); G4 keeps the door open for them.
