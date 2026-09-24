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
4. Create a foldable AVD. ~~From the SDK's `pixel_10_pro_fold` skin
   (`avdmanager create avd -n Pixel_Fold …`)~~ — **this is not what happened.**
   There is no `avdmanager` on this machine, so the AVD was written by hand
   and is `Samsung_Galaxy_Main_Display`; `adb emu fold` / `unfold` do nothing
   on it and postures go through `adb shell cmd device_state state <id>`
   alone. The README carries the real config and the ids.

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

**What the emulator can actually reach (2026-09-19).** The
`Samsung_Galaxy_Main_Display` AVD now declares a hinge, so the "Inner, book"
column is checkable there: half open in portrait, the app reports
`posture=BOOK, hinge=Rect(924, 0, 924, 2448)`. The other two columns are not:

- **Cover (compact)** — a generic `google_apis` image ignores
  `hw.displayRegion.0.1.*`, so folding to CLOSED does not switch panels. Use
  `adb shell wm size 1248x1972` (the Fold 8's outer panel) as a stand-in, or a
  phone AVD.
- **Inner, half-open (flex deck)** — unreachable by device state. The hinge
  rotates with the window as it should, to `Rect(0, 924, 2448, 924)`, but
  Material 3's `Posture.isTabletop` stays false, so `rememberWindowShape`
  falls through to `FLAT` and the flex branch never runs. `state 2` plus
  `user_rotation 1` is not enough.

So flex mode is verified by `WindowShapeFoldTest` (`androidTest`), which
publishes a fake `FoldingFeature` through `WindowLayoutInfoPublisherRule` and
asserts the posture and the split position. That covers the decision; the
*look* of the deck still needs real hardware, as the paragraph above says.

**Amendment (2026-09-20): those two limits are `Samsung_Galaxy_Main_Display`'s,
not every emulator's.** The reason given above — a generic `google_apis` image
carries no device-specific framework overlay — is a claim about THAT image, and
it predicts its own exception: an AVD built on a real device profile with a
vendor overlay should manage both. There is one sitting on this machine,
`Pixel_9_Pro_Fold`: the SDK's own `pixel_9_pro_fold` profile, 2076×2152 @ 390
dpi, on a `google_apis_playstore` API 37.2 image, and it declares the cover
region (`hw.displayRegion.0.1` = 1080×2424) whose absence is the whole
explanation for the first bullet.

**It is untested.** It would not boot: the emulator wants roughly three times
its `disk.dataPartition.size` free on the host, and this machine had 3.4 GB.
Shrinking the partition does not escape the ratio. So the two bullets stand as
*observations about the AVD they were made on*, and whether an emulator here
can reach tabletop and the cover screen is an open question, not a settled no.
Free ~8 GB, boot `Pixel_9_Pro_Fold`, and the checks are: `adb shell cmd
device_state state 1` for the cover panel, and `state 2` plus
`user_rotation 1` watching for `posture=TABLE_TOP` in `adb logcat -s Regolith`.
If it works, the flex deck becomes checkable by eye without a real Fold, and
`WindowShapeFoldTest` keeps its value as the part that runs on CI.

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

- **Idle** (`autoHideRail`, on by default): `NavHideAfter` (10 s by default) after the last touch
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

Verified on the `Pixel_Fold` AVD in both postures: reset from both extremes,
reset on close, Browse panes, idle retract, pin and unpin, autoplay through
two episodes, cancel, and the cover screen unchanged. (That AVD was 2076×2152
@ 390 dpi and hand-made; it has since been replaced by
`Samsung_Galaxy_Main_Display`, a different panel — see the emulator table in
`CLAUDE.md`.)

---

## F7 — Round three (shipped 2026-09-10)

Four changes and one exploration that turned into a fifth, from the artifact
"Four builds and a costing". The owner approved all of them, including the
one I had recommended parking.

### The rail's collapse button leaves the pill

A 44dp `IconCircleButton` — the same object the detail pane closes with —
`s12` below the rail, both centred on the window and sliding as one group.
`NavPill` loses the `onHide` parameter it grew in F6 and is four cells again.

### Autoplay is two switches

`Settings › Playback` now reads **Keep playing** ("When a file ends, start
the next one in the folder") with **Don't ask first** ("Skip the ten-second
Up next card and go straight in") nested under it, indented behind a
hairline and disabled while the parent is off. `autoplay_immediately` is a
new preference, off by default, and both switches mirror into the player's
playback sheet.

### The middle drag is visible

The accumulated drag became real state, so the layout can read it every
frame; on release an `animateFloatAsState` spring settles it, instead of the
layout snapping.

- **Up** (windowed): the picture scales toward full-bleed, the details
  column fades and slides down behind it.
- **Down** (windowed): both shrink together and a scrim deepens over the
  lot — the player being put away.
- **Down** (full screen): the picture shrinks back toward the strip.
- Crossing the existing 12% commit point fires one haptic tick, so you can
  feel that letting go now will do something.

**The constraint this is designed around**, measured on the Fold before
anything was built: a `SurfaceView` is composited by the system in its own
layer rather than painted into the Compose one, so a parent `graphicsLayer`
**scale does apply** (0.6 visibly moved the picture) and **alpha does not**
(0.25 changed nothing). Hence: scale the picture, fade the ordinary
composables, and darken with a scrim drawn over the top rather than a fade
applied to it. Switching to a TextureView would make alpha work, at the cost
of a GPU copy per frame and HDR passthrough; it is not worth it for a fade
we can get another way.

### Play all and Shuffle, on a real queue

A red `PlayAllButton` sits under the top bar of a Library collection or a
Browse folder that holds files — never the Library root or the share list,
where "all" would mean the whole NAS. It opens `PlayAllSheet`, built like
the sort sheet, offering **In order** and **Shuffle**.

Underneath, `PlaybackSession` gained an `activeQueue`. When one is running,
`state.next` is the rest of the queue instead of the rest of the folder, so
the up-next list, the Up next card and autoplay all inherit it for free.
The order is the wall's own — shuffled in the nav graph, since the session
has no business knowing what a wall is — and the ids ride on
`RegolithKey.Player.queue` so a queue survives process death. A queue is
dropped as soon as a file outside it is opened, and `state.queued` makes it
beat the "Keep playing" switch.

### Moving tiles, and why they are gone again (F9)

F7 added `Settings › Display › Moving tiles`: twelve 240×135 frames packed
into one JPEG sprite sheet and drawn a cell at a time at 4fps over the
still. The mechanism worked. The picture did not — 240×135 is what made a
wall of eighteen affordable, and at tile size it reads as a smear playing
over the crisp 500×750 still underneath it.

So the whole feature is out: `ArtworkKind.PREVIEW`, the sheet builder, the
`previewSlots` semaphore, the setting and its preference key. What is left
is the still, which was always the better picture, and the twelve seeks per
file and ~3× disk it cost are back in the bank.

Kept from the entry for the record, since it is the reason nobody should
try it as an animated file: **Android can decode animated WebP and GIF but
cannot encode either** — there is no public API. And real video is out
because no phone will give you eighteen concurrent hardware decoder
instances; Media3 fails hard rather than degrading when they run out.

## F9 — round five

### The landscape player stops being full screen

Landscape used to mean full screen, full stop: `immersive = landscape ||
fullscreen`. On a phone that is right — 411dp of height has nothing else to
usefully hold. Unfolded it threw away the best screen in the app. The wide
*portrait* player already shows the film over two columns of title, pills,
playback settings and "Next in this folder"; turning the device sideways
replaced all of it with a picture.

Now `forcedFullscreen = landscape && !wide`, and a wide window turned
sideways gets `SideColumn`: the picture keeps its 16:9 and sits centred in
the left pane, and the right pane is ONE scrolling column — title and meta,
the A–B pill, the folder, then Speed / Rotation / Decoder. YouTube's shape,
for YouTube's reason: what plays next belongs beside the film, not under it.

Full screen is then a deliberate act everywhere it is possible — the corner
glyph, or a drag up through the middle third — and back or a drag down
leaves it. `canToggleFullscreen` is simply `!forcedFullscreen && !flex`,
which is also what decides whether the collapse glyph is drawn.

The column is `windowShape.width * 0.34`, clamped to 300–460dp: a fraction
so a tablet gives the picture more room than an unfolded phone, a clamp
because a list of 84×47 thumbs and a filename stops improving past ~460dp.

### Thumbnails come from the middle of the film

See `ARCHITECTURE.md` for the decision and the `ArtworkStore.GENERATION`
migration. The short version: a season of episodes that share an intro was
a grid of the same frame, because the grab was at 10%. It is now at 50%.

### Folders without a picture get a mosaic

A collection with no `folder.jpg` drew a grey wedge. It now draws four
frames from the videos inside it, stitched 2×2 and composed separately at
poster and thumb aspect. A sidecar still wins: dropping one in and
rescanning drops the mosaic row and the next request finds the real image.


Verified on the `Pixel_Fold` AVD — 2076×2152 @ 390 dpi, since replaced by
`Samsung_Galaxy_Main_Display` — the detached rail button, both Settings
sections, the drag in both directions mid-gesture, Play all from both a
Library wall (queue of 7) and a Browse folder (queue of 4), shuffle, and
twelve-frame sheets generated one at a time with a `moving_tile_*` canvas
per tile. The cover screen is unchanged apart from the Play all button,
which it gets too.

---

## F8 — Round four (shipped 2026-09-10)

Three pieces of feedback from using the player and Settings for real. None
of them is foldable-specific; two of them behave differently on the inner
display, which is why they are recorded here.

### The picture's zones are 15/70/15

Brightness and volume were being changed by accident. A third of the picture
each is a lot of picture for two gestures you rarely want and cannot undo,
so they now take 15% down each edge and the middle 70% keeps the full-screen
drag, the dismiss and play/pause.

`SIDE_ZONE` is a single constant in `PlayerGestures.kt`, and the gesture map
is laid out with `Modifier.weight(SIDE_ZONE)` from the same number — the
picture you are shown once is the map you are actually using. The edge
columns' copy was cut to fit ("Back 10s, stacking." / "Drag for brightness")
rather than left to wrap to death.

**The cost, taken deliberately:** double-tap-to-seek shares those zones, so
the seek targets narrowed with them. Splitting taps from drags was rejected
— a map where a tap and a drag at the same point belong to different zones
cannot be drawn, let alone learned.

### A rotation lock on the player

`PlayerOrientation` (Auto / Portrait / Landscape) sits with Speed in the
playback sheet — you decide it about the film in front of you, not about the
app — and is remembered, because a lock you set every time is not a lock.

**It cannot work on the inner display, and says so.** Android 16 stopped
honouring an app's `requestedOrientation` on large screens: a device whose
*smallest* width is 600dp or more decides for itself. Verified on the Fold:
locking Landscape rotates the cover screen and does nothing at all on the
inner display. So the control greys out there with a line saying why, rather
than pretending to work. The test is `smallestScreenWidthDp`, not the
current width — a phone turned sideways is a wide window and obeys fine.

### Every share carries its own Scan and Disconnect

A real bug: the Shares card had one pair of buttons underneath it, and
Disconnect took `servers.first()`. With two NAS boxes connected there was no
way to remove the second one at all.

Each row now ends in its own 40dp scan and disconnect actions (`RowAction`);
the confirm dialog was always per-row, only the button that opened it was
wrong. A running scan greys its own button, since the status line already
says "Scanning · N files". **Add a share** is promoted out of the card into
the section's red CTA. **Scan all** survives only when there are two or more
shares, where it saves taps rather than repeating the row above it.

## F10 — round six

### The settings moved under the picture, and the folder moved beside it

F9 put the whole right-hand pane in one column: title, A–B, the folder, then
speed / rotation / decoder. In use the folder ended up level with the
settings, which made the settings look like the reason the pane existed.

So the landscape player is now two columns level at the top: the film with
its own controls under it on the left — the same order the portrait player
reads in — and the folder alone on the right, beside the picture.

**Wide portrait is one column**, in the order you use it: the film, what
follows it, then the controls for the film. The two-column wide portrait
layout from F4 is gone; `PortraitDetails` is a single `Column` for every
width, and `wide` now only decides whether the settings panel is inline
(it is) and whether the speed and decoder pills are drawn (they are not,
because the panel below them is the control).

### Chapters

The pill has been drawn since Phase 2 with `onClick = {}`. It now opens a
bottom sheet listing the file's chapters — name, how long each runs, its
start time, and a red bar against the one the playhead is inside. Tapping
one seeks and closes; a chapter list is a way to get somewhere.

`ChapterParser` reads the container by hand (see `ARCHITECTURE.md` for why
nothing on the platform will). **A file with no chapters gets no pill** —
absent beats a control that promises something it cannot do.

The sheet closes when the file changes: autoplay can swap the film out from
under it, and the last film's chapters over the next one is worse than none.

### One sheet shape

`PlayerSheetHost` lost its `landscape` branch and its 344dp side panel.
Everything the player opens is a bottom sheet now, in every orientation.

### Ambient letterbox bars

The spike answer, worth writing down: **media3 sizes the video surface to
the content**, so the bands a 16:9 film leaves in the Fold's near-square
window are window pixels, not surface pixels, and can be painted. They now
carry the film's own colour. Bars *burned into* the frames cannot be, and
never will be — those pixels are the picture.

### A frame grab that knows how long the film is

`framePositionMs` is only right if the runtime is. `ArtworkRepository.
runtimeOf` tries the open FrameSource, then the `media_files` row, then
Media3's extractors through the `DurationProbe` seam, and writes the answer
back. Before this, a container the platform retriever could not time got
`duration = 0` — and 0 is the first frame, the title card, the exact tile
the midpoint change exists to avoid.

`adb logcat -s Regolith/Artwork` now says, per file, where the frame came
from and how the runtime behind it was arrived at.

## F11 — round seven

### Chapters are for every file, not the lucky ones

A chapter list exists so you can jump through a film. Tying that to whether
the file happened to be muxed with markers made it a feature almost nothing
had. Now the container's markers win when there are any, and everything else
gets `ChapterMarks.evenly`: the runtime cut at a round interval off a ladder
(10 s, 15 s, 30 s, 1/2/5/10/15 min), the first one that gets the count to
twelve or under.

Round rather than arithmetic on purpose — "every 10 minutes" is a thing you
can hold in your head. The sheet's subtitle says which kind you are looking
at, because it changes what the names mean.

### The thumbnail bug, and what it actually was

The midpoint fix (F9) and the runtime fallback (F10) were both right and
neither was enough, because the last link was never checked: **the platform
retriever does not tell you which frame it gave you.** Ask it to seek, and
if it cannot, it returns the opening frame and says nothing.

So artwork extraction moved to Media3's `FrameExtractor` — the same
extractors and the same `SmbDataSource` the player uses, and a
`presentationTimeMs` on every frame. The log now prints where the frame
really came from and warns when it is more than 30 s from where it was
asked for.

**How it was proven**, worth keeping for the next time: a fixture whose hue
sweeps once over ten minutes, so the colour of any frame states its own
timestamp. `ffmpeg -f lavfi -i "color=c=red:s=1280x720:r=10:d=600" -vf
"hue=H=2*PI*t/600"`, pushed over the demo library's files, and every
thumbnail read back as 300.7 s of a 600 s clip.

Found on the way: `BufferedByteSource` was not thread safe, and its LRU
mutates on reads. Two concurrent reads could hand back an evicted block —
over a share, never on a local file.

**The cost**, taken deliberately: an ExoPlayer and a GL context per
extraction, 1–3 s against the retriever's 250 ms on a local file. It is one
still per file, the gap narrows over SMB where I/O dominates, and the
retriever stays the fallback and stays the scrub-preview path.

### A backdrop that is not a blown-up thumbnail

Title Detail's hero was drawing the 320x180 thumb across 2076px. There is
now a 1280x720 `BACKDROP`, generated on demand for the one title you opened
rather than for every tile on a wall. The player's ambient glow takes it too:
16:9 is the right shape for spill behind a 16:9 picture.

### The divider stops one way and dismisses the other

Both ends used to squeeze. Now dragging LEFT stops at the wall's minimum —
rail plus 240dp, two poster columns — and dragging RIGHT dismisses the
detail entirely. `DismissiblePane` lays the detail out at a fixed 260dp
however narrow the pane gets and clips it, so nothing reflows into a column
of one-word lines: it slides under the divider and fades out, and below
40dp it is not composed at all. The handle's reset button brings it back.

## F12 — round eight

### The divider resizes the view, not the layout

F11 gave the detail pane a floor width and clipped it. The wall did not have
one, and `GridCells.Fixed(3)` meant its tiles simply got narrower as the
divider came left — resizing content, which is the thing the drawer idea
exists to avoid.

Both panes are drawers now: content measured at a floor (the wall at
rail + 364dp, the detail at 360dp) and placed at the start edge, with the
overflow clipped. A `Layout` rather than a `Box` — a Box asked to hold
something wider than itself does not promise where it puts it, and the
overflow was leaving on the OUTER edge, cutting the first tile in half
instead of tucking the last one under the divider.

### The rail's inset tells the truth

**Reverses part of F6.** Reserving the rail's 102dp while the rail was slid
away meant an empty stripe down the side of the screen, and the two ways of
hiding the rail reserving different amounts of space. It now follows what is
on screen and animates, which is what "nothing should jump" actually wanted.

The pane scaffold keys off the *pinned* state only. It is keyed on the pane
width, and an animated one would rebuild it on every frame of a retraction.

### Chapters are pictures

The sheet is a wall of 16:9 stills at two to four columns, taken a quarter
of the way into each part — a chapter boundary is usually a cut, and the
frame on a cut is often black. They come through the same scrub pipeline the
flex filmstrip uses, so a chapter sheet costs one key-frame seek per card
and no new machinery.

The pill spins until the list has **settled**: `chaptersScanned` separates
"this file has no chapters" from "we have not looked yet". Without it the
sheet opened on even divisions and recounted itself when the real ones
arrived, which is the height jump.

### Previous and Next

One `Transport` composable now, shared by all three chromes. The skip keys
are always drawn and dimmed when there is nowhere to go — a transport row
that changes width as you walk a folder moves the play button out from under
your thumb.

### Open with Regolith

A `VIEW` intent on a video plays it. No row, so no artwork, no folder, no
resume point, no scrub previews; the player itself is all there, chapters
included, because even divisions need nothing but a runtime. The landscape
two-pane layout stands down when there is no folder to put beside the film.

## F13 — round nine: taking the divider out

Three rounds went into making a draggable split behave, and each one made
the app worse. The pattern is worth writing down, because it is the useful
part:

Every version had to answer "what happens to the content as the pane
narrows?", and every answer was wrong somewhere. Resize it, and the wall's
tiles shrink to nothing. Clip it, and a drawer that hides the wall under the
divider ALSO stops the rail from resizing it — which is the one resize that
should happen. Fade it, and you have a pane that is neither there nor gone.
Each fix moved the problem to the other side of the screen.

**So the split is fixed at 50/50 and the divider is not a control.** It
needs no handle, no anchors, no reset, and no rule about the extremes. What
dragging was really for — seeing the whole wall — is what closing the detail
already does, in one tap, with no state to restore afterwards.

Removed: `PaneHandle`, `DrawerPane`, `pane_reset_split`, the reset-on-close
effect, `MIN_WALL_WIDTH`, `DETAIL_MIN_WIDTH`, `PANE_GONE_WIDTH`.

### The rail resizes what is beside it

The other half of the same mistake. With the wall behind a drawer, hiding
the rail stopped giving its space back: the column held its floor width and
clipped. Now that nothing is dragged, the width only changes when the rail
does — and then an ordinary resize is exactly right. Measured: tiles are
224dp wide with the rail out and 289dp with it away, three columns either
way, nothing clipped.

### Landscape always plays two-pane

`sideBySide` briefly also required `state.next.isNotEmpty()`, added so a
film from another app would not leave an empty column. It also caught the
last episode of a season, which then opened into the portrait layout while
the device was sideways. The test is the window plus "this came from the
library"; when the folder is merely finished, the column stays and says
"Nothing after this one", because a column that goes blank reads as a
screen that failed to draw.
