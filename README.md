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

Regolith can lock itself (P11). Settings › Privacy asks for a fingerprint,
face or the phone's own screen lock before the library is shown, and you
choose how long the app may sit in the background first — at once, after a
minute, or after five. Backgrounding it blanks the preview in the app
switcher, and locking pauses whatever was playing. If the phone loses its
screen lock entirely the lock stands down rather than shutting you out.

Downloads take a batch: hold any video or folder in Browse, Library or
Search to start picking, tap the rest, and the nav pill stops being a nav
and becomes that selection's toolbar — Download there queues the lot, and
the line above it says what you have picked and what it will cost
— picking a folder takes everything inside it, including folders the app
has never scanned, which it walks over SMB as the download runs; drill in
and uncheck anything you don't want, and the folder comes minus those. A
foreground notification carries the progress across the whole batch and a
red dot on the Settings tab says it started; the copies themselves live
where they always have, under Library › On this device — which lists them
as rows or as a grid (the switch in the top bar, remembered separately from
the network wall's), and removes several at once through that same pill
toolbar, plus a Clear all that asks first. Home shows the first few
of them in a row of their own, beside "All".

Chapters can be yours (P9). Every file has chapters — the container's own
markers, or an even split — and the player's Chapters sheet now has a
pencil: mark a place at the playhead, open it, name it, type or nudge its
start time or drag its handle on the marks strip, and Done makes those the
film's chapters, winning over
whatever it came with. The scrubber draws chapters as segments and names
the part under your finger; Search lists chapter names as points of
interest and opens the film at that moment; Revert on the sheet takes a
film back to its defaults, and Settings › Chapters clears everything you
wrote.

Those chapters travel with the film (P10): Save writes a small text file
beside it on the share and says so at the bottom of the screen, `Heat.1995.chapters.txt`, in the format
mkvmerge reads, and the next scan picks up any such file whether Regolith,
a desktop tool or you wrote it. The phone keeps a copy so search stays
instant and offline play has chapters; when both changed, the newer wins.
A read-only share keeps chapters on the phone and says so. A downloaded
film brings its chapter file along and keeps a copy beside it, rewritten
on every edit, so an edit made offline is safe on the phone and reaches
the share when it can. Revert removes the file too; Settings › Clear only
empties the phone's copies, and the next scan brings back whatever the
share has. In the editor, "Remove all chapters" starts a film over from a
single unnamed mark; tapping the current tab's cell in the nav pill brings
that tab back to its top. `docs/CHAPTERS.md` is the
file's specification.

Files on the share can be renamed, moved and deleted from the app. A
video's own page carries Rename and Delete at the foot of the screen; in
Browse, holding a video starts a selection as it always did, and the bar
that appears now offers Move, Rename and Delete beside Download. It is
files only — a picked folder still feeds Download and greys the other
three, because a folder move drags a whole subtree behind it and a folder
delete is the one mistake with no way back. A move is a single rename on
the server, so it is instant, nothing is copied, and the share dropping
halfway leaves every video either where it was or where it was going,
never half-moved; that is measured rather than assumed
(`SmbMutationProbeTest` against a real Samba server). Deleting is
permanent and asks first, naming the size and saying that the chapters you
wrote and where you left off go with it.

## Build and run

Requirements: Android Studio 2026.1 (for its bundled JDK 21 and the SDK),
SDK platform 37, an Android 14+ device or the `Pixel_10` emulator.

```
./gradlew assembleDebug        # compile
./gradlew installDebug         # build + install on the running emulator
./gradlew test                 # JVM unit tests (one talks to the local
                               #   Samba fixture and skips without it)
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

### Naming a server

A server added by address is called by that address — `192.168.4.73` reads
the same as every other box on the network. After connecting, **Name this
server** offers a better one; leave it blank to keep what the app worked out
(`TOWER` for `tower.local`, the address itself for an IP).

You can change it later: in **Settings › Shares**, tap a server's name. The
address stays on the line underneath, so a renamed box is still findable.
The name is only a label — nothing is keyed to it, so renaming touches no
media, no progress and no downloads.

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
