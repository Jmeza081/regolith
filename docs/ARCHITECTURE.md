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
                                        ├─ 4. frame at 10% of the runtime                                ┘ (MediaMetadataRetriever over SMB)
                                        └─ 5. placeholder row (wedge + filename; expires after a day)
                                        → writes BOTH kinds (500×750 poster, 320×180 thumb) + `artwork` rows

TitleDetail ── MediaProbe (Media3 MetadataRetriever through SmbDataSource) ── media_files probe columns
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
| 2026-09-09 | Notification permission is declared but not yet requested | The scan runs either way; the notification only tells the user why the app is busy. The runtime request joins the Add Source flow's permission UX in Phase 6. |

## Phase plan

| Phase | Goal | Contracts fixed |
|---|---|---|
| 0 | Scaffold: theme, components, nav shell, DI, QA loop | G7, G8, G9 |
| 1 | Connect → browse → play (manual `smb://`, share picker, raw folders, playback, resume) | G1, G2, G3, G4, G6 |
| 2 | Player complete: chrome, speed, decoder, A–B loop, gestures, playback sheet | `PlayerUiState`, `ScrubThumbnails` interface |
| 3 | Artwork pipeline, Browse grid, Title Detail, scrub previews (v1: on demand) | G5, `FrameSource`, `ScrubThumbnails` |
| 4 | Library scan, filename parsing, search (FTS), Home, sort, collections, Settings shares | parse rules, progress-in-Room, `FolderKind` |
| 5 | Downloads and offline ("On this device"), out-of-reach states, local playback | `TransferScheduler` seam, `transfers` rows |
| 6 | LAN discovery, onboarding, settings, polish, saved QA flows | |

The design (`design/docs/SMB Video Player Design/`) is the source of truth
for every screen and state. Section 12 of it lists features deliberately not
built (cast/PiP, subtitle and audio tracks, sleep timer, skip intro, private
folder); G4 keeps the door open for them.
