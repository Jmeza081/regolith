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

Package layout (single module until there's a real reason to split):

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
  domain/        pure Kotlin models + use cases (no Android imports)
  di/            Hilt modules
  player/        Media3 setup, custom DataSource for SMB, thumbnail scrubbing
```

Design source of truth: `design/docs/SMB Video Player Design/Regolith Video Player.dc.html`
(12 sections, 37 screens). The phased plan and the architecture guardrails it
fixes are summarised in `docs/ARCHITECTURE.md`.

Rules of thumb:
- UI never touches `data/` directly; it goes through a ViewModel.
- `domain/` has no Android dependencies so it's trivially unit-testable.
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

Emulator control goes through the **argent MCP tools** (`mcp__argent__*`)
when they are loaded in the session. They are not always: check first, and
fall back to `adb` plus `uiautomator dump` if they are missing — that is what
argent drives underneath, so the rules below hold either way.

The AVDs on this machine (`emulator -list-avds`; there is no phone AVD, and
no `avdmanager` either — see the README for how these were made):

| AVD | What it is | Use it for |
|---|---|---|
| `Samsung_Galaxy_Main_Display` | 1848×2448 @ 400 dpi = 739×979 dp. Hand-written, generic `google_apis` at API 36.1. Hinge declared. | The wide window and `BOOK`. The one that is known to work. |
| `Pixel_9_Pro_Fold` | 2076×2152 @ 390 dpi, SDK `pixel_9_pro_fold` profile on a `google_apis_playstore` API 37.2 image, cover region declared. | Untested — it will not boot on this machine's current free disk. See `docs/FOLDABLE_PLAN.md`. |

- Check what is running first (`list-devices`, or `adb devices`) and boot
  `Samsung_Galaxy_Main_Display` if nothing is.
- Build + install: `./gradlew installDebug`, then `launch-app` with the
  applicationId (or `adb shell am start -n com.regolith/.MainActivity`).
- **Never guess tap coordinates.** Call `describe` (reads the accessibility
  tree via uiautomator) before every tap; by hand that is
  `adb shell uiautomator dump` and read the `bounds` off the node you want.
- Use `await-ui-element` to wait for screens instead of polling screenshots.
- Verify any visible UI change on the emulator, not just by compiling.
- `run-as` reads an app's private files, but ONLY for a debuggable build. On
  a release APK it fails with "package not debuggable", and a redirected
  error there reads exactly like an empty directory.

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
./gradlew test                 # JVM unit tests (domain/, ViewModels)
./gradlew connectedAndroidTest # instrumented/Compose UI tests on the emulator
./gradlew lint                 # Android lint
repomix                        # regenerate the full-repo snapshot
```

There is no system JDK on this machine. Run Gradle with
`JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"` set
(the `gradlew` launcher needs it before `gradle.properties` is read; the daemon
then uses `org.gradle.java.home` from there). The debug build signs
with the committed `app/debug.keystore` so reinstalls keep the on-device
database (Android refuses to update an app whose signature changed).

## Git

- Small, focused commits with a message that explains the *why*.
- Don't commit `repomix-output.xml`, `local.properties`, `.gradle/`, `build/`.

### Release trailers

A push to `main` that touches the app publishes a GitHub Release with an APK
(`.github/workflows/release.yml`). The version and the notes come from **git
trailers** — machine-readable lines in the trailer block at the foot of the
message, beside the `Co-Authored-By:` that is already there. The subject and
body stay prose; nothing about the style above changes.

```
Move, rename and delete whole folders, and make one while moving

<the usual body, explaining the why>

Release: minor
Notes: Folders can be moved, renamed and deleted whole, and the move sheet
  can make a new one to move things into.
Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
```

| Trailer | What it does |
|---|---|
| `Release: major` | Breaking change. `1.4.2` → `2.0.0`, listed under **Breaking changes**. |
| `Release: minor` | Something new. `1.4.2` → `1.5.0`, listed under **New**. |
| `Release: patch` | A fix or a refinement. `1.4.2` → `1.4.3`, under **Fixed and improved**. |
| `Release: skip` | No bump; listed under **Under the hood**. |
| `Notes: …` | The line a user reads, in their words. Indent continuation lines. |

Both are optional and both have sane defaults: a commit touching only `docs/`,
`design/` or markdown is `skip`, anything else is `patch`, and a missing
`Notes:` falls back to the subject line. **Write them anyway** — a release
whose notes are all subject lines is a changelog, and the point of the
Releases tab is to say what changed for someone holding the phone.

The highest trailer in a release wins: one `Release: minor` among six patches
makes the whole release a minor. Preview what the next release would say
before pushing:

```
.github/scripts/release_notes.py
```
