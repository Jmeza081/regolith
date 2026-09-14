# CLAUDE.md — Regolith

Regolith is a native Android video player that streams from SMB shares, with
Jellyfin-style thumbnail scrubbing. Kotlin + Jetpack Compose.

## Who you're working with

The owner is a **web developer learning Android**. Every explanation, commit
message, and code comment should assume that background:

- Translate Android concepts into web terms when it helps
  (Activity ≈ a page/route, ViewModel ≈ a store/hook that survives re-renders,
  Compose ≈ React with `@Composable` functions instead of components,
  Gradle ≈ npm + webpack, Hilt ≈ a DI container / React context for services).
- When you make an architectural or library choice, give a **2–4 sentence "why"**:
  the problem, the choice, the alternative you rejected. Then move on.
  No essays, no restating things already explained in the docs.
- Be concise. Prefer a short, accurate answer over a thorough one.
- Do not assume familiarity with Android jargon (lifecycle, configuration
  change, Parcelable, manifest, etc.) — define a term the first time it matters
  in a session.

## Tech stack & architecture (defaults — explain if deviating)

| Concern | Choice | Web analogy |
|---|---|---|
| Language | Kotlin | TypeScript |
| UI | Jetpack Compose + Material 3 | React + a design system |
| Architecture | MVVM + unidirectional data flow (UI state as a single `StateFlow<UiState>` per screen) | Redux-ish: state down, events up |
| Navigation | Navigation 3 (`androidx.navigation3`; the back stack is a plain list of `@Serializable` keys) | React Router, with the history stack as state you own |
| DI | Hilt | DI container / context providers |
| Async | Coroutines + Flow | async/await + observables |
| Video | Media3 (ExoPlayer) | `<video>` + hls.js |
| SMB | jcifs-ng (or SMBJ if jcifs-ng can't do what we need) | a fetch client for a file share |
| Build | Gradle Kotlin DSL + version catalog (`gradle/libs.versions.toml`); AGP 9 compiles Kotlin itself | package.json + lockfile |
| Icons | Lucide via `com.composables:icons-lucide-android` (vector drawables, `R.drawable.lucide_ic_*`) | lucide-react |
| SDK levels | minSdk 34 · targetSdk 37 · compileSdk 37 | browserslist |

Modules. `:core` is the only code the phone and the Mac app share; it may not
depend on Android, Compose, Room or Hilt (guardrail G11 in `docs/ARCHITECTURE.md`).

```
core/     pure Kotlin/JVM: domain/, data/smb (jcifs), data/media/SidecarWriter,
          data/credentials/CredentialStore. Tests + FakeSmbGateway (testFixtures).
app/      the Android app, depends on :core
desktop/  Regolith Chapters, the macOS chapter editor (docs/DESKTOP.md), depends on :core
```

Package layout. Package names are the same in every module, so
`com.regolith.domain.*` lives in `core/src/main/kotlin` and everything
else below in `app/src/main/java`:

```
app/src/main/java/com/regolith/
  RegolithApp.kt   Application (Hilt root); MainActivity.kt (the one Activity)
  AppViewModel.kt  app-level state: onboarding gate / start destination
  ui/            screens + shared components
    components/  REUSABLE composables — check here before writing a new one
    theme/       Color, Type, Shape, Spacing, Theme (tokens from the design)
    navigation/  RegolithKey (routes), MainTab, NavGraph (the router)
    <feature>/   one package per screen: XScreen.kt, XViewModel.kt, XUiState.kt
  data/          repositories, SMB client, local persistence (DataStore/Room)
  domain/        (in :core) pure Kotlin models + use cases (no Android imports)
  di/            Hilt modules
  player/        Media3 setup, custom DataSource for SMB, thumbnail scrubbing
```

Design source of truth: `design/docs/SMB Video Player Design/Regolith Video Player.dc.html`
(12 sections, 37 screens). The phased plan and the architecture guardrails it
fixes are summarised in `docs/ARCHITECTURE.md`.

Rules of thumb:
- UI never touches `data/` directly; it goes through a ViewModel.
- `domain/` has no Android dependencies so it's trivially unit-testable. It
  lives in `:core`, where the build enforces that: an Android import there
  does not compile.
- Follow the official Android app architecture guide and Kotlin style guide.
  When unsure what "industry standard" is, say so and pick the mainstream
  option rather than the clever one.

## Reuse before you build

Before creating any composable, ViewModel helper, or utility:

1. Search `ui/components/` and the rest of the codebase for something similar
   (Grep by likely names, then read the matches).
2. If something close exists, extend it (add a parameter/slot) instead of
   duplicating it. Say which component you reused.
3. Only create a new one if nothing fits — and put shared UI in
   `ui/components/`, not inside a screen file.

Two screens with copy-pasted composables is a bug, not a shortcut.

## Documentation is part of every change

Keep these in sync with the code — update them in the **same** commit as the
change that affects them:

- `README.md` — what the app is, how to build/run it.
- `docs/ARCHITECTURE.md` — layers, data flow, key decisions (and why). Add an
  entry whenever an architectural decision is made or reversed.
- KDoc on every public class/function and on any composable in
  `ui/components/` (purpose, when to use it, notable params).
- Short inline comments where Android does something non-obvious to a web dev
  (lifecycle quirks, threading rules, permission flows).

If you change how something works, grep the docs and comments for the old
behavior and fix them. Stale docs are worse than no docs.

## Understanding the whole project: Repomix

Repomix packs the repo into a single file so the full architecture can be
reviewed at once. It's a CLI tool (already installed on this machine), not an
app dependency. Config lives in `repomix.config.json`; output is gitignored.

- Regenerate: `repomix` (writes `repomix-output.xml` at the repo root).
- Use it when: planning a feature that touches several layers, auditing for
  duplicated components, or writing/refreshing `docs/ARCHITECTURE.md`.
- Don't paste the whole output into a response; read it and summarize.

## Running & testing on the emulator (argent)

Emulator control goes through the **argent MCP tools** (`mcp__argent__*`),
which are already installed globally. Follow `~/.claude/rules/argent.md`.
Short version:

- `list-devices` first; boot the `Pixel_10` AVD with `boot-device` if it's not
  running.
- Build + install: `./gradlew installDebug`, then `launch-app` with the
  applicationId.
- **Never guess tap coordinates.** Call `describe` (reads the accessibility
  tree via uiautomator) before every tap.
- Use `await-ui-element` to wait for screens instead of polling screenshots.
- Verify any visible UI change on the emulator, not just by compiling.

To make the app navigable by argent, follow these conventions in Compose:

- Every interactive element gets a stable `Modifier.testTag("feature_element")`
  (e.g. `player_play_button`, `browser_share_list`) or a meaningful
  `contentDescription` for icons. This is what `describe` reports, like a
  `data-testid` on the web. The root Scaffold in `NavGraph.kt` sets
  `testTagsAsResourceId = true`; without it uiautomator sees no tags at all.
- Meaningful text labels beat icon-only controls where reasonable.
- These tags also serve Compose UI tests (`androidTest/`), so they're not
  test-only scaffolding.

## Commands

```
./gradlew assembleDebug        # compile
./gradlew installDebug         # build + install on the running emulator
./gradlew test                 # JVM unit tests: :core (domain/, SMB, sidecar) + :app (ViewModels, Room)
./gradlew connectedAndroidTest # instrumented/Compose UI tests on the emulator
./gradlew lint                 # Android lint
repomix                        # regenerate the full-repo snapshot

./gradlew :desktop:run         # the Mac app's window
./gradlew :desktop:test        # its unit tests + a Compose UI test against the Samba fixture
./gradlew :desktop:smoke       # headless: SMB list, libvlc seek, sidecar round-trip
```

The Mac app is verified with its Compose Desktop UI test, not argent: argent
cannot drive a desktop JVM window. The test renders the real screens, clicks
by the same `testTag`s, and saves a PNG of each step to
`desktop/build/test-shots` to look at.

There is no system JDK on this machine. Run Gradle with
`JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"` set
(the `gradlew` launcher needs it before `gradle.properties` is read; the daemon
then uses `org.gradle.java.home` from there). The debug build signs
with the committed `app/debug.keystore` so reinstalls keep the on-device
database (Android refuses to update an app whose signature changed).

## Git

- Small, focused commits with a message that explains the *why*.
- Don't commit `repomix-output.xml`, `local.properties`, `.gradle/`, `build/`.
