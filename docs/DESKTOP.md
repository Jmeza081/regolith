# Regolith Chapters (macOS)

A desk-sized companion to the phone app with one job: write chapters for
the films on a share. Connect, open a film, mark and name its chapters,
Save. The file it writes, `<film>.chapters.txt` next to the film, is the
one the phone reads (`CHAPTERS.md`), produced by the same code.

It is not a second player. There is no library, scan, artwork or
download; the share is the only record, and the Mac keeps nothing but
(from step 3) the list of servers.

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
  player/FilmPlayer.kt      what the editor needs from a player
  player/VlcPlayer.kt       libvlc through vlcj, reading via SeekableByteSource
  ui/App.kt                 theme + back stack + which screen the top route draws
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

## Save outcomes

There is no local copy, so a failed save leaves the edits unsaved and says
why: "Saved to the share", "Not saved: the share is read-only", "Not saved:
<host> is out of reach", "Not saved: the share no longer accepts this
sign-in".

## Not yet

Saved servers and Keychain passwords, typed start times, Revert, the
unsaved-changes guard, keyboard shortcuts, and a packaged `.app` that
bundles libvlc are the next commits on this branch.
