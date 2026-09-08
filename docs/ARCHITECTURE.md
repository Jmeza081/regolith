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
           preferences, credential store. Only place third-party IO lives.
player/    Media3 (ExoPlayer) session, the SMB DataSource, frame extraction.
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

## Phase plan

| Phase | Goal | Contracts fixed |
|---|---|---|
| 0 | Scaffold: theme, components, nav shell, DI, QA loop | G7, G8, G9 |
| 1 | Connect → browse → play (manual `smb://`, share picker, raw folders, playback, resume) | G1, G2, G3, G4, G6 |
| 2 | Player complete: chrome, speed, decoder, A–B loop, gestures, playback sheet | `PlayerUiState`, `ScrubThumbnails` interface |
| 3 | Artwork pipeline and thumbnail scrubbing | G5 |
| 4 | Library scan, filename parsing, search (FTS), Home, sort, collections | parse rules, progress-in-Room |
| 5 | Downloads and offline ("On this device") | `TransferScheduler` seam |
| 6 | LAN discovery, onboarding, settings, polish, saved QA flows | |

The design (`design/docs/SMB Video Player Design/`) is the source of truth
for every screen and state. Section 12 of it lists features deliberately not
built (cast/PiP, subtitle and audio tracks, sleep timer, skip intro, private
folder); G4 keeps the door open for them.
