# Regolith

A native Android video player for files on your own SMB share, with
Jellyfin-style thumbnail scrubbing. Nothing is copied off the share and
nothing leaves your network. Kotlin + Jetpack Compose.

## Status

Phase 6 of 6: the design audit. Every screen was rebuilt against the
design export frame by frame: the exact type pairs (Michroma titles, Space
Grotesk everything else), the colour tokens, the frosted pill nav, the
design's own icon set and photographs, the splash and the three-page
onboarding, the LAN finder (a TCP sweep of the Wi-Fi subnet), the A–B loop
panel under the picture, the drag rails and the one-time gesture map.
Phases 1 to 4 are verified on a real phone against a NAS; Phases 5 and 6
on the Pixel 10 emulator against a local Samba share. See
`docs/ARCHITECTURE.md`.

On top of that, the Galaxy Z Fold's inner display has its own layouts
(F0–F6 in `docs/FOLDABLE_PLAN.md`): a nav rail that retracts to a spine, the
Library and Browse walls beside a title-detail pane with a resettable
divider, and the player's two-column and flex-mode layouts. Autoplay next
came with that round and works on the phone too.

## Build and run

Requirements: Android Studio 2026.1 (for its bundled JDK 21 and the SDK),
SDK platform 37, an Android 14+ device or the `Pixel_10` emulator.

```
./gradlew assembleDebug        # compile
./gradlew installDebug         # build + install on the running emulator
./gradlew test                 # JVM unit tests
./gradlew connectedAndroidTest # Compose UI tests on the emulator
./gradlew lint
```

No separate Java install is needed, but the `gradlew` launcher itself needs a
JVM before it can read `gradle.properties`, so point it at Android Studio's
bundled JDK once per shell:

```
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
```

`local.properties` (gitignored) must contain `sdk.dir=/Users/<you>/Library/Android/sdk`;
Android Studio writes it on first open, or create it by hand.

Emulator driving and QA go through the argent MCP tools (see `CLAUDE.md`).

### Foldable emulator

The owner's phone is a Galaxy Z Fold, and the inner display gets its own
layouts (`docs/FOLDABLE_PLAN.md`). A second AVD, `Pixel_Fold`, uses the SDK's
Pixel 10 Pro Fold profile (inner 2076×2152 @ 390 dpi, cover 1080×2364) on the
same API 37 image as `Pixel_10`. This machine has no `avdmanager`, so it was
written by hand: `~/.android/avd/Pixel_Fold.ini` plus
`Pixel_Fold.avd/config.ini` copied from `Pixel_10` with the profile's
`hw.lcd.*`, `hw.sensor.hinge.*` and `hw.displayRegion.0.1.*` keys.

Postures, once it is running:

```
adb emu fold                              # cover screen (compact)
adb emu unfold                            # inner display, fully open
adb shell cmd device_state print-states   # lists the ids: closed / half-opened / opened
adb shell cmd device_state state 1        # half open (flex mode); `state reset` releases it
adb logcat -s Regolith                    # prints "window shape: …" on every change (debug builds)
```

`WindowShape` (`ui/adaptive/`) is what the app sees: `wide` is true on the
inner display and false on the cover, so every wide layout can be checked
on one emulator by folding it.

### Choosing folders inside a share

"Choose a share" takes whole shares; the chevron on each share opens
**Choose folders**, where you pick the folders you actually want in the
library (`Films/` and `Series/`, not `Backups/`). In each row the box picks
the folder and the rest of the row opens it, so you can walk down and pick
at any depth — `Films` at the top and `Series/Severance/Season 02` three
levels in, with nothing chosen in between. An unpicked folder says how many
picks are below it, so a deep one is easy to find again.

Picks are written as `share_roots` rows; a share with none means the whole
share, so nothing changes for an install that never uses it. The library
keeps the share's real shape: the folders on the way down to a pick get a
row each, but they are never read off the share, and neither is anything
outside a chosen folder.

### Artwork is made ahead of time

Every poster, thumbnail and backdrop is a frame pulled off the share, which
costs a second or two each. Rather than doing that while you scroll, a
background job walks the share when its scan finishes and makes them all up
front, showing a progress notification you can stop. One frame grab writes
all three sizes.

A library scanned before this existed has no walk queued for it: tap
**Settings › Media › Prepare artwork**, or rescan the share.

### Trying it without a share

Settings › Demo › **Load** writes a pretend NAS — four collections, 18
titles, a few part-watched — and copies four bundled test clips onto the
device, one per title. Everything plays, scrubs and shows real frame-grab
posters with no network at all, which is what makes the app reviewable on
a train. **Remove** deletes it; nothing else is touched.

The section is behind `BuildConfig.DEMO_LIBRARY`, true in both build types
today because the side-load (`assembleRelease`) build is the one that gets
tested on a phone. Set it false — and delete `res/raw/demo_*.mp4` — before
any store upload.

## Layout

```
app/src/main/java/com/regolith/   Kotlin sources (ui/, data/, domain/, di/, player/)
app/src/main/res/font/            Michroma + Space Grotesk (OFL), bundled
design/docs/                      the high-fidelity design export (source of truth)
docs/                             ARCHITECTURE.md, NAVIGATION.md
```
