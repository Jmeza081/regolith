# Regolith Chapters (macOS)

A desk-sized companion to the phone app with one job: write chapters for
the films on a share. Connect, open a film, mark and name its chapters,
Save. The file it writes, `<film>.chapters.txt` next to the film, is the
one the phone reads (`CHAPTERS.md`), produced by the same code.

It is not a second player. There is no library, scan, artwork or
download; the share is the only record, and the Mac keeps nothing but the
list of servers.

## Run, test, package

```
./gradlew :desktop:run           # the window
./gradlew :desktop:test          # unit tests + Compose UI tests
./gradlew :desktop:smoke         # headless SMB → bundled libvlc → sidecar check
./gradlew :desktop:fetchVlc      # fetch libvlc into vlc-bundle/ (the two above do it for you)
REGOLITH_JPACKAGE_JDK=/path/to/jdk-21/Contents/Home ./gradlew :desktop:packageDmg
```

The `.dmg` lands in `desktop/build/compose/binaries/main/dmg/`. It is not
signed, so the first launch of a copy downloaded from elsewhere needs
right-click › Open. The Android Studio JDK has no `jpackage`, hence
`REGOLITH_JPACKAGE_JDK` (any full JDK 17+, e.g. a Temurin download).

To check a built app plays on its own, without a window:

```
"desktop/build/compose/binaries/main/app/Regolith Chapters.app/Contents/MacOS/Regolith Chapters" \
  --self-check smb://localhost:1445/media/Films/Long.Test.2026.mp4 /tmp/frame.png
```

It prints where libvlc came from, how long libvlc took to get ready, the
film's length and where a seek to 4:00 landed, and saves that frame.

To check a server saved in the app, the way the app signs in (its
`servers.json` row and its Keychain password), without changing anything:

```
"…/Regolith Chapters" --check-saved 1
```

It lists the share, walks two folder levels, counts films and chapter
files, and reads the chapters inside a few films. It never writes, never
prints the password, and prints counts rather than film names; empty
chapter files are listed by path, since those are what you would clean up.

The UI test and the smoke check use the local Samba fixture
(`localhost:1445`, share `media`; see the SMB test-share notes) and skip or
fail loudly when it is not running. Both put the fixture's chapter file
back afterwards. The UI test saves a PNG of each step to
`desktop/build/test-shots`. argent cannot drive a desktop JVM window, so
that test is how a change to the Mac UI is checked.

## How it is built

Web analogy: a second app in the monorepo, beside the phone app, importing
the shared framework-free package.

```
core/                       shared with the phone: domain/, JcifsGateway, SidecarWriter
desktop/src/main/kotlin/com/regolith/desktop/
  Main.kt                   application { Window }: the process and its window
  AppGraph.kt               the long-lived objects, wired by hand (no DI framework)
  navigation/Route.kt       Servers | Browse(connection, folder) | Editor(connection, folder, film)
  data/Connection.kt        host + credentials + share: what every screen past Servers needs
  data/SavedServer.kt       a remembered share (no password), as stored
  data/ServerStore.kt       servers.json in Application Support
  data/KeychainCredentialStore.kt   passwords, in the macOS Keychain
  data/ShareImage.kt        artwork beside a film: an image file on the share, loaded by Coil
  player/FilmPlayer.kt      what the editor needs from a player
  player/VlcPlayer.kt       libvlc through vlcj, reading via SeekableByteSource
  player/NativeVlc.kt       finds and loads libvlc once: bundled, checkout, installed
  player/UnavailablePlayer.kt   the player when there is no libvlc
  SelfCheck.kt              play a film headless; --self-check and :desktop:smoke
  SavedServerCheck.kt       --check-saved: a saved server, read-only
  player/VlcPluginIndex.kt  libvlc's plugin index, kept outside the signed app
  ui/editor/NameSuggestions.kt   which names to offer while naming a chapter
  ui/App.kt                 theme + back stack + which screen the top route draws
  ui/LeaveGuard.kt          asks before Back or closing the window drops unsaved chapters
  ui/servers, browse, editor   XScreen.kt + XViewModel.kt + XUiState.kt, as on the phone
  ui/components/ClickOnly.kt   clickOnly(): passed to the shared buttons so a click does not take focus
```

- **Same rules, same file.** The editor's rules (the start mark is pinned,
  marks stay a second apart, opening a mark takes the film there, one open
  row locks the rest) are `ChapterDraft` from `:core`. Save writes with
  `SidecarWriter` (a `.part` file renamed into place), formatted by
  `ChapterSidecar`.
- **Never open what a listing has not shown.** jcifs opens a file for
  reading with create-if-missing (open flags 17, `O_CREAT | O_RDONLY`, in
  jcifs-ng 2.1.10). Looking for a chapter file by opening it leaves an
  empty one on the share, which then counts as the film's chapter file.
  The editor lists the folder first and opens only the film and chapter
  file the listing shows; `--self-check` lists before it opens too.
  `--check-saved` reports empty chapter files it finds, which is what such
  an open leaves behind. Since then the shared client itself refuses to
  open a missing file (`JcifsGateway.open`), so this is a second guard.
- **Artwork.** Browse draws a thumbnail beside each film from the images
  already in its folder, chosen by the phone's rules (`ArtworkCandidates`):
  an image with the film's name, or a poster beside a film alone in its
  folder. It reads that one file off the share and keeps it in memory only.
  A film with no image draws the phone's unmatched look; frame grabs and
  covers inside a film are the phone's job. A folder shows its own poster
  (a poster, folder, cover or thumb image inside it) once Browse has looked
  inside, a few folders at a time after the list is up; one without keeps
  its icon.
- **Video.** `VlcPlayer` hands libvlc a callback media over the gateway's
  `SeekableByteSource`, the desktop twin of the phone's `SmbDataSource`, so
  both apps read a share through one SMB client. It keeps a reference to
  that media while it plays: libvlc holds only a weak one.
- **State.** Each screen has a plain state holder with one
  `StateFlow<UiState>`; see the decision log in `ARCHITECTURE.md` for why
  not an AndroidX `ViewModel`.
- **Test tags.** Every control has a `testTag` (`servers_connect_button`,
  `browse_list`, `editor_save_button`, `editor_chapter_row_N`, …), the same
  convention as the phone.

## VLC is bundled

The app carries libvlc and its plugins (82 MB of it), so VLC does not need
to be installed. The packaged app is about 230 MB and its `.dmg` about
115 MB.

- `fetchVlc` downloads VideoLAN's own `vlc-3.0.23-arm64.dmg`, refuses it
  unless it matches the pinned SHA-256 (VideoLAN's published one), mounts it
  read-only and copies `lib/` and `plugins/` into
  `desktop/vlc-bundle/macos-arm64/vlc/` (gitignored). `share/` is left out:
  Lua playlist scripts and translations a callback-media player never uses.
  Apple Silicon only. VideoLAN redirects the https link to a plain-http
  mirror (a different one each time); the download follows it, and the
  checksum is what vouches for the bytes.
- Compose copies that folder into the `.app`; `NativeVlc` finds it through
  `compose.application.resources.dir`, then the checkout's copy, then an
  installed `/Applications/VLC.app`.
- vlcj's own macOS discovery cannot be pointed at one folder, so a small
  strategy does what it does: load `libvlccore` first (libvlc names it by
  `@rpath`, which only resolves once it is loaded) and set
  `VLC_PLUGIN_PATH` to the `plugins/` beside `lib/`.
- The packaged Java runtime holds only the modules the build lists
  (`java.naming` and `java.security.jgss` for jcifs, `jdk.unsupported` for
  JNA, and the rest `suggestModules` asked for). A missing one would build
  fine and fail when the app connects or plays; `--self-check` catches it.
- With no libvlc at all, the editor still opens: it says video is
  unavailable, and **Add chapter** (or M) adds one a minute after the last,
  open for its Start to be typed.

To move to a newer VLC, change the URL and checksum in `fetchVlc`, delete
`desktop/vlc-bundle/`, and run `:desktop:smoke` and the `--self-check`.

**libvlc's plugin index lives outside the app.** VLC's
`plugins/plugins.dat` indexes its plugins by modification time and size,
and the bundled plugins are copied several times on the way into an
installed app, so the index VideoLAN ships never matches: libvlc rescans
every plugin on every launch. It cannot be rebuilt inside the app either,
because the app is code-signed and changing a file in it breaks the
signature (tried; `codesign --verify` then fails, and the DMG makes its own
copy anyway).

So `VlcPluginIndex` makes a folder in
`~/Library/Application Support/Regolith Chapters/vlc-plugins-<stamp>/` with
a link to each bundled plugin. libvlc is pointed there, follows the links
to the real plugins, and writes its index into that folder on the first
launch. The stamp covers the plugins' location, sizes and times, so moving
or updating the app makes a new folder, indexed once, and removes the old
one. If any of it fails, libvlc falls back to scanning the bundled plugins:
slower to start, same playback.

| Launch of the app from the DMG | Stale lines | libvlc ready in |
|---|---|---|
| First (builds the index, 2.1 s) | 0 | 4.7 s |
| Every one after | 0 | 0.2 to 0.3 s |
| Before this, every launch | 335 | 3.3 to 4.0 s |

## Editing

The rules are the phone's (`ChapterDraft`), and so is the wording:

- A film opens with, in the phone's order: its chapter file, else the
  chapters inside the film (an MKV's or MP4's own markers, read by the same
  parser the phone uses), else a single unnamed start mark at 0:00.
  Chapters from inside a film can be saved as a chapter file straight away,
  without an edit. The start mark can be renamed, never moved or deleted.
- **Mark here** (or M) adds a chapter where the film is. Clicking a row
  opens it and takes the film there (the scrubber shows where the chapters
  fall); the other rows are locked until it closes (Done, Enter in the name,
  or Esc).
- An open row has its name, a **Start** field that takes `12:30`,
  `0:12:30`, `1:02:15.5` or bare seconds (committed on Enter or when the
  field loses focus: "Use 12:30, 0:12:30 or 1:02:15.5", "Between 0:01 and
  4:59 here"), the ±½ s / ±5 s nudges, and Delete.
- Naming a chapter offers the names already used in the chapter files of
  the same folder, most-used first, narrowed as you type (every typed word
  must start a word of the name, so "the hei" finds "The heist"). The
  phone looks across its whole library; the Mac has no library, so it
  looks at the film's folder.
- **Remove all chapters** goes back to one unnamed start mark. **Revert
  chapters** deletes the chapter file from the share after asking; the film
  then falls back to the chapters inside it, if it has any.
- **Save** (⌘S) writes the file now. Leaving with unsaved changes, by Back
  or by closing the window, asks: Save, Discard, or Keep editing.

| Key | Does |
|---|---|
| Space | Play / pause |
| M | Mark here |
| ← / → | Back / forward 5 s (with ⇧: ½ s) |
| ⌘S | Save |
| Esc | Close the open row; leave a text field |

The keys go to the editor itself, so its buttons, rows and slider never
take focus when clicked (how buttons behave on macOS). Inside a text field
keys type as usual; ⌘S and Esc still work there.

## Servers and passwords

- A share is remembered once it connects: host, port, share and the
  username as typed, in
  `~/Library/Application Support/Regolith Chapters/servers.json`. The file
  has no password in it. Connecting the same share with the same username
  again updates the row. A file the app cannot read is moved to
  `servers.json.bad`, not overwritten.
- Passwords live in the login Keychain, one item per server: service
  **Regolith Chapters**, account = the server's id in `servers.json`. This is
  the Mac's version of the phone's guardrail G6, behind the same
  `CredentialStore` interface.
- The app drives `/usr/bin/security`. Writes go to `security -i` over stdin
  so a password never appears on a command line (`ps` can read those). The
  stored value is `b64:` + base64 of the password, because
  `find-generic-password -w` prints any non-ASCII password as hex, which
  a password made of hex digits could not be told apart from. An entry typed
  into Keychain Access by hand still works if it is plain ASCII.
- A saved row whose password is missing, or that the NAS now refuses, opens
  in the form to take a new one. Editing a row with the password field left
  blank keeps the stored password. Remove asks first, then deletes the row
  and its Keychain item; films and chapter files are never touched.

## Save outcomes

There is no local copy, so a failed save leaves the edits unsaved and says
why: "Saved to the share", "Not saved: the share is read-only", "Not saved:
<host> is out of reach", "Not saved: the share no longer accepts this
sign-in".

## Not yet

Signing and notarisation and Intel Macs. The phone's `ui/theme` is shared
through `:ui`, as are most of its `ui/components`; only the nav pill,
which is built on the phone's navigation, stays in the phone app.
