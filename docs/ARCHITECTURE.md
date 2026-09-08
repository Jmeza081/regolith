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
