# Regolith Chapters (macOS)

A desk-sized companion to the phone app with one job: write chapters for
the films on a share. Connect, open a film, mark and name its chapters,
Save. The file it writes, `<film>.chapters.txt` next to the film, is the
one the phone reads (`CHAPTERS.md`), produced by the same code.

It is not a second player. There is no library, scan, artwork or
download; the share is the only record, and the Mac keeps nothing but the
list of servers.

## Run and test

```
brew install --cask vlc          # until the packaged app bundles libvlc
./gradlew :desktop:run           # the window
./gradlew :desktop:test          # unit tests + a Compose UI test
./gradlew :desktop:smoke         # headless SMB → libvlc → sidecar check
```

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
  player/FilmPlayer.kt      what the editor needs from a player
  player/VlcPlayer.kt       libvlc through vlcj, reading via SeekableByteSource
  ui/App.kt                 theme + back stack + which screen the top route draws
  ui/LeaveGuard.kt          asks before Back or closing the window drops unsaved chapters
  ui/servers, browse, editor   XScreen.kt + XViewModel.kt + XUiState.kt, as on the phone
  ui/components/Controls.kt the few controls, styled from the phone's tokens
  ui/theme/                 the phone's palette and fonts, copied (sharing is a later branch)
```

- **Same rules, same file.** The editor's rules (the start mark is pinned,
  marks stay a second apart, opening a mark takes the film there, one open
  row locks the rest) are `ChapterDraft` from `:core`. Save writes with
  `SidecarWriter` (a `.part` file renamed into place), formatted by
  `ChapterSidecar`.
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

## Editing

The rules are the phone's (`ChapterDraft`), and so is the wording:

- A film with a chapter file opens with its chapters; one without starts
  from a single unnamed start mark at 0:00. The start mark can be renamed,
  never moved or deleted.
- **Mark here** (or M) adds a chapter where the film is. Clicking a row, or
  its segment on the strip under the scrubber, opens it and takes the film
  there; the other rows are locked until it closes (Done, Enter in the name,
  or Esc).
- An open row has its name, a **Start** field that takes `12:30`,
  `0:12:30`, `1:02:15.5` or bare seconds (committed on Enter or when the
  field loses focus: "Use 12:30, 0:12:30 or 1:02:15.5", "Between 0:01 and
  4:59 here"), the ±½ s / ±5 s nudges, and Delete.
- **Remove all chapters** goes back to one unnamed start mark. **Revert
  chapters** deletes the chapter file from the share after asking.
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

A packaged `.app` that bundles libvlc is the next commit on this branch.
