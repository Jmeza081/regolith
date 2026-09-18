<p align="center">
  <img src="docs/images/banner.png" alt="Regolith — your SMB share, as a video library" width="100%">
</p>

<p align="center">
  <strong>Kotlin</strong> · <strong>Jetpack Compose</strong> · <strong>Media3</strong> · <strong>jcifs-ng</strong> · <strong>Android 14+</strong>
</p>

<p align="center">
  A native Android video player for the files on your own SMB share.<br>
  Nothing is copied off the share, and nothing leaves your network.
</p>

---

Regolith points at the NAS you already have and treats it like a media library:
posters, chapters, resume points, thumbnail scrubbing. It streams each file in
place over SMB rather than syncing a copy to the phone, so the library is however
big your share is. There is no account, no server to run beside it, and no
service that gets told what your files are.

<table>
  <tr>
    <td width="33%"><img src="docs/images/library.png" alt="The library wall, showing four collections with their posters"></td>
    <td width="33%"><img src="docs/images/detail.png" alt="A title page with artwork, technical detail and file actions"></td>
    <td width="33%"><img src="docs/images/selection.png" alt="Browse with a file selected; the nav pill has become a toolbar"></td>
  </tr>
  <tr>
    <td align="center"><sub>The library, built from the share</sub></td>
    <td align="center"><sub>A title, and what it really is</sub></td>
    <td align="center"><sub>Select, and the nav becomes a toolbar</sub></td>
  </tr>
</table>

## What it does

### Plays straight off the share

Point it at a share, pick the folders you actually want (`Films/` and `Series/`,
not `Backups/`), and it reads the shape of what's there. Playback is a custom
Media3 data source reading SMB directly, so a file starts without being copied
first. A LAN finder sweeps the Wi-Fi subnet if you don't know the address.

### Scrub by thumbnail

Drag the scrubber and frames appear above your finger, the way Jellyfin and Plex
do it. Every poster, thumbnail and backdrop is a real frame pulled off the share
— but pulled *ahead of time*, by a background job that walks the share once its
scan finishes, rather than while you are scrolling.

### Chapters you can write

Every file has chapters: the container's own markers, or an even split. The
player's chapter sheet has a pencil — mark a place at the playhead, name it, drag
its handle — and those become the film's chapters. Save writes a small
`mkvmerge`-format text file beside the video on the share, so the work is
readable by other tools and survives a reinstall. Search finds chapter names and
opens the film at that moment.

### Manages the files, not just the library

Hold any video — or any folder — to start picking, and the floating nav pill stops
being a nav and becomes that selection's toolbar: Download, Move, Rename, Delete.
A move is a **single rename on the server** — one metadata operation, nothing
copied — so it is instant, and a share that drops halfway leaves every video
either where it was or where it was going, never half-moved. That is measured
rather than assumed, by a probe suite run against a real Samba server.

Folders move and rename the same way, whole: the server does a directory and
everything under it in that same single operation. Deleting is permanent and asks
first, naming the size and saying that the chapters you wrote go with it — and for
a folder, that everything inside goes too, including files Regolith never listed.
When the folder you want to move something into doesn't exist yet, the move sheet
makes it for you and drops the files straight in.

### Takes a batch offline

The same toolbar queues downloads. Picking a folder takes everything inside it,
including folders the app has never scanned, which it walks over SMB as the
download runs. A foreground notification carries progress across the whole batch;
the copies live under **Library › On this device**.

### Opens out on a foldable

The inner display gets its own layouts: a nav rail that retracts to a spine, the
library and browse walls beside a title pane with a divider you can reset, and
two-column and flex-mode player layouts.

### Locks itself

Settings › Privacy asks for a fingerprint, face or the phone's own screen lock
before the library is shown, and you choose how long the app may sit in the
background first. If the phone loses its screen lock entirely, the lock stands
down rather than shutting you out.

## Try it without a share

**Settings › Demo › Load** writes a pretend NAS — four collections, 18 titles, a
few part-watched — and copies four bundled test clips onto the device. Everything
plays, scrubs and shows real frame-grab posters with no network at all, which is
what makes the app reviewable on a train. **Remove** deletes it; nothing else is
touched.

The section is behind `BuildConfig.DEMO_LIBRARY`, true in both build types today
because the side-load (`assembleRelease`) build is the one that gets tested on a
phone. Set it false — and delete `res/raw/demo_*.mp4` — before any store upload.

## Build and run

You need Android Studio 2026.1 (for its bundled JDK 21 and the SDK), SDK platform
37, and an Android 14+ device or emulator.

```
# the gradlew launcher needs a JVM before it reads gradle.properties
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"

./gradlew assembleDebug        # compile
./gradlew installDebug         # build + install on a running emulator
./gradlew test                 # JVM unit tests
./gradlew connectedAndroidTest # Compose UI tests on a device
./gradlew lint
```

`local.properties` (gitignored) must contain
`sdk.dir=/Users/<you>/Library/Android/sdk` — Android Studio writes it on first
open, or create it by hand.

One JVM test talks to a local Samba fixture and skips without it. Emulator
driving and QA go through the argent MCP tools; see [`CLAUDE.md`](CLAUDE.md).

<details>
<summary><strong>Foldable emulator</strong></summary>

The inner display gets its own layouts ([`docs/FOLDABLE_PLAN.md`](docs/FOLDABLE_PLAN.md)).
A second AVD, `Pixel_Fold`, uses the SDK's Pixel 10 Pro Fold profile (inner
2076×2152 @ 390 dpi, cover 1080×2364) on the same API 37 image as `Pixel_10`.
This machine has no `avdmanager`, so it was written by hand:
`~/.android/avd/Pixel_Fold.ini` plus `Pixel_Fold.avd/config.ini` copied from
`Pixel_10` with the profile's `hw.lcd.*`, `hw.sensor.hinge.*` and
`hw.displayRegion.0.1.*` keys.

Postures, once it is running:

```
adb emu fold                              # cover screen (compact)
adb emu unfold                            # inner display, fully open
adb shell cmd device_state print-states   # lists the ids: closed / half-opened / opened
adb shell cmd device_state state 1        # half open (flex mode); `state reset` releases it
adb logcat -s Regolith                    # prints "window shape: …" on every change (debug builds)
```

`WindowShape` (`ui/adaptive/`) is what the app sees: `wide` is true on the inner
display and false on the cover, so every wide layout can be checked on one
emulator by folding it.

</details>

<details>
<summary><strong>Naming a server, and choosing folders inside a share</strong></summary>

A server added by address is called by that address — `192.168.4.73` reads the
same as every other box on the network. After connecting, **Name this server**
offers a better one; leave it blank to keep what the app worked out (`TOWER` for
`tower.local`, the address itself for an IP). You can change it later in
**Settings › Shares**. The name is only a label — nothing is keyed to it, so
renaming touches no media, no progress and no downloads.

"Choose a share" takes whole shares; the chevron on each share opens **Choose
folders**, where you pick the folders you actually want in the library. In each
row the box picks the folder and the rest of the row opens it, so you can walk
down and pick at any depth — `Films` at the top and `Series/Severance/Season 02`
three levels in, with nothing chosen in between. An unpicked folder says how many
picks are below it, so a deep one is easy to find again.

Picks are written as `share_roots` rows; a share with none means the whole share,
so nothing changes for an install that never uses it. The library keeps the
share's real shape: the folders on the way down to a pick get a row each, but
they are never read off the share, and neither is anything outside a chosen
folder.

</details>

<details>
<summary><strong>Artwork is made ahead of time</strong></summary>

Every poster, thumbnail and backdrop is a frame pulled off the share, which costs
a second or two each. Rather than doing that while you scroll, a background job
walks the share when its scan finishes and makes them all up front, showing a
progress notification you can stop. One frame grab writes all three sizes.

A library scanned before this existed has no walk queued for it: tap **Settings ›
Media › Prepare artwork**, or rescan the share.

</details>

## How it's put together

- **Kotlin + Jetpack Compose**, Material 3, one Activity.
- **MVVM** with unidirectional data flow — each screen owns a single `StateFlow<UiState>`.
- **Navigation 3**, where the back stack is a plain list of serializable keys you hold as state.
- **Hilt** for dependency injection, **Room** for the library, **DataStore** for settings.
- **Media3 (ExoPlayer)** with a custom SMB data source; **jcifs-ng** for the share itself.
- Parsing, matching and artwork are all local — nothing is looked up online.

```
app/src/main/java/com/regolith/   ui/ · data/ · domain/ · di/ · player/
app/src/main/res/font/            Michroma + Space Grotesk (OFL), bundled
design/docs/                      the high-fidelity design export
docs/                             ARCHITECTURE.md · NAVIGATION.md · CHAPTERS.md
```

[`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) is the decision log: every
architectural choice, what it replaced, and why.
[`docs/CHAPTERS.md`](docs/CHAPTERS.md) specifies the chapter file format.

## License

The bundled typefaces (Michroma, Space Grotesk) are under the SIL Open Font
License.
