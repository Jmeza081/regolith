# Regolith

A native Android video player for files on your own SMB share, with
Jellyfin-style thumbnail scrubbing. Nothing is copied off the share and
nothing leaves your network. Kotlin + Jetpack Compose.

## Status

Phase 5 of 6: downloads and offline. "Keep on this device" on any title
copies it into the app's own storage in the background, resuming from
where it stopped if the share drops out and naming the cause when it
cannot finish (share gone vs. no room). Library's "On this device" tab
lists what plays with no network, what is arriving and what failed; Home
summarises it. When a share is out of reach the Network wall says so with
Try again and a way across to the device tab, and the player reads the
local copy through the same path as the share. Phases 1 to 4 are verified
on a real phone against a NAS. See `docs/ARCHITECTURE.md`.

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
