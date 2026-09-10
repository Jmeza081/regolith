<!-- Companion to the design canvas "Regolith on the Fold". Written 2026-09-10; the phase table in ARCHITECTURE.md tracks what has shipped. -->

# Regolith on the Fold — implementation plan

Companion to the design canvas (Regolith on the Fold, 5 artboards). Goal: the
five inner-display layouts from the canvas, with the phone (and the Fold's
cover screen) untouched. Written for a web developer learning Android.

## The one idea underneath

Treat the inner display as a **media query**, not a device. Two signals:

- **Window width class** (`compact` < 600 dp ≤ `medium` < 840 dp ≤ `expanded`).
  The Fold's cover screen is compact, the inner display is medium in book
  posture and expanded when half-folded landscape. Everything in this plan
  keys on one boolean, `wide = width ≥ 600 dp`. Tablets get it for free.
- **Posture** from Jetpack WindowManager's `FoldingFeature`: `Flat`, `Book`
  (hinge vertical) or `TableTop` (hinge horizontal, half open). Only the
  player reads posture; every other screen only needs `wide`.

What we get for free: the Activity is **not** recreated on fold/unfold because
the manifest already lists `screenSize|screenLayout` in `configChanges`; it
recomposes. ViewModels live either way, `PlaybackSession` is a singleton (G4)
so the film keeps playing across the fold, and the Navigation 3 back stack is
a plain list of serializable keys, so the "where am I" survives (G7).

## Libraries

| Artifact | Version | Why | Metadata check |
|---|---|---|---|
| `androidx.compose.material3.adaptive:adaptive` | 1.3.0 | `currentWindowAdaptiveInfo()` → window size class + posture | kotlin-stdlib 2.1.20 ✔ |
| `androidx.compose.material3.adaptive:adaptive-navigation3` | 1.3.0 | `ListDetailSceneStrategy` for Navigation 3 | kotlin-stdlib 2.1.20 ✔ |

Both are under the Kotlin 2.3 metadata ceiling that bit Coil 3.6 (AGP 9.4's
Kotlin 2.2.10). `adaptive-navigation3` 1.3.0 was built against navigation3
1.0.0; we are on 1.1.7, which is binary compatible. Gate step F0 on a clean
`assembleDebug` before anything else.

Rejected: a second navigation structure for wide screens (breaks G7, doubles
every route), and hand-rolled two-pane state in each ViewModel (works, but
the scene strategy already models "Library, then Title Detail" from the
stack we have).

## Phases

Each phase ends the way every change does: `./gradlew installDebug`, verified
on the emulator with argent in **both** postures, docs updated, a release
APK sent to the owner.

### F0 — Foundation (S)

1. Add the two artifacts (`adaptive` already depends on `androidx.window` 1.5.0, so
   the hinge comes with it and there is no separate window pin) to `libs.versions.toml` and `app/build.gradle.kts`.
   Build. If the metadata check fails here, stop and report.
2. `ui/adaptive/WindowShape.kt`: one `@Immutable data class WindowShape(val
   wide: Boolean, val posture: Posture, val hingeBounds: Rect?)` and a
   `LocalWindowShape` composition local (web: a `useMediaQuery()` hook
   exposed through context). `RegolithNavGraph` computes it once from
   `currentWindowAdaptiveInfo()` plus a `WindowInfoTracker` flow on the
   Activity and provides it to the tree.
3. Replace the eight hard-coded `112.dp` nav-pill bottom insets across
   screens with a `LocalNavPillInsets: PaddingValues` set by the nav graph
   (bottom on the phone, start on wide). This is the prerequisite for the
   rail and a cleanup in its own right.
4. Create a foldable AVD from the SDK's `pixel_10_pro_fold` skin on the API
   37 image already installed (`avdmanager create avd -n Pixel_Fold -d
   pixel_10_pro_fold -k <image>`). Posture switches with `adb emu fold` /
   `adb emu unfold` and `adb shell cmd device_state state <id>` for
   half-open. Record the ids in README.

Docs: ARCHITECTURE decision-log entry **"Adaptive layouts key on window
shape, not device"** and guardrail G10 (one back stack, panes are scenes).

### F1 — Nav rail + Home (S)

- `NavPill` gains `vertical: Boolean`. Same composable, same haze, same four
  cells; a `Column` instead of a `Row`, 84 dp wide, hugging the left edge at
  vertical centre. No second component.
- `NavGraph` aligns it `CenterStart` when `wide`, sets `LocalNavPillInsets`
  accordingly, and the slide transitions stay as they are.
- `HomeScreen`: when `wide`, "Newly added" is a `LazyVerticalGrid(
  GridCells.Fixed(6))` of the same poster box instead of the `LazyRow`;
  resume cards size to a third of the row instead of the fixed 256 dp.
  Everything else (pull-to-refresh, empty states) is untouched.

Verify: rail visible and tappable on the inner display, pill unchanged on the
cover screen; `describe` shows the same `nav_*` tags in both.

### F2 — Library list-detail (M)

- `NavDisplay` gets `sceneStrategies = listOf(rememberListDetailSceneStrategy())`.
- `entry<Library>` metadata adds `ListDetailSceneStrategy.listPane(
  detailPlaceholder = { ... })`; `entry<TitleDetail>` adds `detailPane()`.
  On compact the strategy does nothing and the push we have today happens;
  on wide the two top keys render side by side. **The back stack does not
  change shape**, which is the whole point.
- `LibraryScreen` takes `selectedFileId: Long?` (the nav graph reads it off
  the stack) and draws the 2 dp ink outline on that tile.
- `TitleDetailScreen` takes `inPane: Boolean`: hides the on-art back circle,
  shows the close circle (which pops the detail key), keeps everything else.
  The placeholder for "nothing selected" is a centred muted eyebrow.
- Browse's file rows push `TitleDetail` too, so Browse gets `listPane()` in
  the same change; that is the pane behaviour the Browse artboard implies.

Open question for the owner: when you tap a tile in the pane layout, should
the wall keep its scroll position (yes by default; the list entry is not
recomposed) and should the Resume button in the pane open the player
full-screen or keep the pane? Plan assumes full-screen (Player is its own
key, not a pane).

### F3 — Browse two panes (M)

- `BrowseViewModel` exposes `tree: StateFlow<List<TreeNode>>`: each share as
  a root, its top-level folders as children with counts. Data already exists
  in Room (`folders` table, `parentId`); this is one new DAO query,
  `observeTopLevel(shareId)`.
- New `ui/browse/ShareTree.kt` (browse-specific, so not `ui/components/`):
  the 220 dp column from the artboard. Tapping a node does what tapping a
  folder does today, `navigate to Browse(folderId)`, but replacing the Browse
  chain rather than pushing (clear back to Home, add the tab key, add the
  folder) so back from a tree jump leaves Browse.
- `BrowseScreen` when `wide`: `Row { ShareTree; folder contents }`; the
  folder pane keeps the existing rows, cards and eyebrows verbatim.

### F4 — Player, fully open (S)

- `PortraitDetails` when `wide`: two columns. Left: title, meta, pill row, then
  `PlaybackSheetContent` inline (already a composable; add `showClose = false`)
  or `AbLoopSheetContent` when a loop is set. Right: "Next in this folder".
- The `Sheet.Playback` case is skipped on wide (the content is already on
  screen); `Sheet.AbLoop` likewise. Landscape on the inner display stays
  `FullChrome`, unchanged.

### F5 — Player, flex mode (L)

- `PlayerScreen` gains a third branch ahead of `immersive`: `posture ==
  TableTop`. Layout: a `Column` whose top box height is `hingeBounds.top`
  (exact split at the hinge, from `FoldingFeature.bounds`), video letterboxed
  inside it with the `FullChrome` header only (back, title, pills); below the
  hinge a new `FlexDeck` composable: filmstrip, 4 dp scrubber with knob,
  52/74/52 transport, up-next row and the two pills.
- Filmstrip data: `PlayerViewModel.strip: StateFlow<List<StripFrame>>` with
  nine timestamps at `duration / 9` steps. `ScrubThumbnails.request(ms)` is
  already fire-and-forget and `updates` already re-emits as frames land, so
  the strip is nine `request()` calls plus `nearest()` on each update; no
  new pipeline. Gate on the existing "Scrub thumbnails" preference (it reads
  from the share) and request the frames nearest the playhead first.
- Tapping a strip frame is `seekTo(ms)`. Dragging the scrubber highlights the
  nearest frame instead of showing the floating `ScrubPreview`.
- Half-fold on the emulator: `adb shell cmd device_state state 1` (id from
  `cmd device_state print-states`) with the Fold AVD.

## Verification matrix

| Screen | Cover (compact) | Inner, book | Inner, half-open |
|---|---|---|---|
| Home | pill, rows as today | rail, 6-wide wall | same as book |
| Library → title | pushes | side-by-side pane | same as book |
| Browse | pushes folders | tree + folder | same as book |
| Player | portrait / landscape | picture + two columns | flex deck |

Each cell: argent `describe` for the tags, screenshot, and once per phase a
`screenshot-diff` against the phone baseline to prove the compact path did
not move. Then the release APK on the real Fold; the emulator's hinge is not
the Fold 8's hinge.

## Risks

- **Adaptive 1.3.0 against navigation3 1.1.7.** Probably fine, gated by F0. If
  not, F2 falls back to a `wide`-keyed `Row` inside `LibraryScreen` that
  hosts `TitleDetailContent` directly and never pushes on wide. Same UI,
  one more state to hold.
- **Cover screen is narrower than the Pixel the app was tuned on** (~370 vs
  411 dp with the 1.28× type). Not part of this plan, but worth one pass on
  the Fold AVD's cover screen while F0 is on the bench; Settings rows and
  the Title Detail chips are the likely offenders.
- **Fold 8 dp.** The artboards assume ~760×840. `wide` does not care; only
  the flex-mode split does, and it reads the hinge from the device.
- **Filmstrip bandwidth.** Nine frames from SMB on every tabletop entry;
  capped by the preference and by `ScrubThumbnails`' existing cache.
- **`ContentFrame` re-attach** when the video box changes height on fold.
  ExoPlayer keeps playing; check for one black frame and, if seen, keep the
  `SurfaceView` in a stable parent and animate the size only.

## Order and size

F0 (S) → F1 (S) → F2 (M) → F4 (S) → F3 (M) → F5 (L). F4 before F3 because it
is a small change on a screen the owner uses constantly; F5 last because it
is the only phase with new data plumbing and the only one that needs the
hinge.

---

## F6 — Round two (shipped 2026-09-10)

Three changes asked for after living with F0–F5 on the real Fold. Written up
first as a review artifact ("The split, the rail, and up next"); this is what
was built.

### The divider carries its own reset

The old restore control was drawn by Title Detail and only at the collapsed
anchor. That missed the 85% anchor, where the detail is a 114dp sliver and the
button was *inside* it — no way back but the handle. Now:

- `PaneHandle` shows a 28dp reset button (`pane_reset_split`) whenever the
  divider is off its default anchor, from either extreme, with the drag bar
  still below it so the handle stays grabbable. Double-tap resets too.
- `TitleDetailScreen.onShowList` is gone, and `rg_ic_split_pane` with it.
- Closing the detail animates the divider back to the default, so a collapse
  belongs to one title rather than to the session.
- `Browse` declares `listPane()` again, revising the F3 decision that took it
  away: three columns still do not fit, but the tree now yields for the
  moment a detail is open (the pane drops below `SHARE_TREE_MIN_WIDTH`) and
  comes back when it closes, rather than Browse giving up the detail
  permanently. Its selected row lifts onto `skeleton`, one step up from the
  `surface` card it sits on.

### The rail retracts

102dp of a ~760dp window went to four nav cells, and because the rail sits
inside the list pane the wall paid for it twice: the pane asks for 466dp, is
capped at 55%, and handed the wall 316 of the 364 it wants.

- **Idle** (`autoHideRail`, on by default): three seconds after the last touch
  the rail slides out, leaving `NavRailSpine` — four dots, the current tab
  lit. The reserved inset is unchanged, so nothing reflows.
- **Pinned** (`railHidden`, remembered): the chevron under the rail's cells
  collapses it and the inset drops to `NAV_RAIL_SPINE_INSET` (22dp). Content
  reflows once, deliberately.
- Tapping the spine restores the rail, and un-pins it when it was pinned.
- `Settings › Display › Auto-hide the rail` governs the timer only, and the
  section is drawn on wide windows only.

Not built: collapsing the rail automatically when a detail pane opens. It was
in the proposal's "hide for space" sketch, but it changes `listPaneWidth`,
which rekeys the pane scaffold, which fights the reset-on-close rule above.
The pin covers the same ground without the churn.

### Autoplay next

`filesAfter()`, `PlaybackState.ended` and `playNext()` already existed; this is
a trigger, a card and a switch.

- Armed when the setting is on, `STATE_ENDED` arrives with `playWhenReady`
  still set, the folder has a next file, and this one was not cancelled.
- The countdown runs inside `repeatOnLifecycle(RESUMED)`, so a film that ends
  while the app is in the background does not pull the next file off the
  share; the card is waiting (with a fresh ten seconds) on return.
- `UpNextCard` rides over the ended frame in every layout — portrait, full
  screen and flex — with a ten-second ring, *Play now* and *Cancel*. Cancel
  declines this one file; it does not touch the setting.
- The switch is `Settings › Playback › Autoplay next` and is mirrored in the
  player's playback sheet, the way Decoder mirrors hardware decoding.

Verified on the Pixel_Fold AVD in both postures: reset from both extremes,
reset on close, Browse panes, idle retract, pin and unpin, autoplay through
two episodes, cancel, and the cover screen unchanged.
