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
