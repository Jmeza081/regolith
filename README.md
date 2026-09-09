# Regolith

A native Android video player for files on your own SMB share, with
Jellyfin-style thumbnail scrubbing. Nothing is copied off the share and
nothing leaves your network. Kotlin + Jetpack Compose.

## Status

Phase 4 of 6: the app understands the share. A background scan walks each
share (it keeps going if you leave the app), parses `Title (Year)` and
`SxxEyy` from filenames locally, and classifies folders into collections,
titles, shows and seasons. Home has Continue watching, Newly added and
pull-to-refresh; Library is the three-across poster wall with collections,
a sort sheet and the unwatched mark; Search matches titles, raw filenames
and folders as you type, with results still arriving while a scan runs;
Settings lists shares, scans all, disconnects with a confirm, and holds
the playback preferences. Phases 1 to 3 (connect, browse, the complete
player, artwork and scrub previews) are verified on a real phone against a
NAS. See `docs/ARCHITECTURE.md`.

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

## Layout

```
app/src/main/java/com/regolith/   Kotlin sources (ui/, data/, domain/, di/, player/)
app/src/main/res/font/            Michroma + Space Grotesk (OFL), bundled
design/docs/                      the high-fidelity design export (source of truth)
docs/                             ARCHITECTURE.md, NAVIGATION.md
```
