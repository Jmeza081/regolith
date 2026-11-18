# Regolith VR — product requirements (Meta Quest 3)

Status: draft 1, 2026-11-18. Design canvas: <https://claude.ai/artifact/1cfHmNJ7p4jn9vVPeVTh78>

Regolith VR is a **new app**, not a port. It takes what Regolith does on a
phone (your SMB share as a video library: posters, chapters, resume points,
thumbnail scrubbing) and re-expresses it for a headset, then adds what only
a headset can do: a screen of any size, a room lit by the film, and **Relief**,
which turns ordinary 2D video into 3D as it plays.

Web analogy for the whole document: the phone app is a responsive site, and
this is the same product rebuilt as a desktop app with a different input
device. Same data, same vocabulary, different layout and interaction rules.

## 1. Goals and non-goals

**Goals**

1. Watch anything on the share on a big, comfortable virtual screen, in
   under three selections from putting the headset on.
2. Relief: real-time 2D→3D on the headset, with a depth control and a
   one-press way back to flat.
3. Keep Regolith's library: collections, posters, chapters and moments,
   resume points, search. A phone user should recognise it instantly.
4. Work the same with **controllers or bare hands**.
5. Stay private: nothing leaves the network, no account, no companion server.

**Non-goals (v1)**

- Uploading from the headset, and the phone-library features (nothing to map to).
- Social or shared viewing, and DRM streaming services.
- Blu-ray 3D ISO / MVC decoding (4XVR's niche; large effort, small overlap
  with a home SMB library).
- A PC companion app. Everything runs on the headset.

## 2. Who it is for

One person, at home, with a NAS and a Quest 3. Usually seated or lying
back, sessions of 20–120 minutes, often in a dim room. Typing is slow in a
headset, so every flow avoids it where it can.

## 3. What Regolith does today, and what happens to each feature

Extracted from the code (`ui/`, `player/`, `domain/`) and the README.

| Area (Android) | In VR | How it changes |
|---|---|---|
| Add server: LAN finder, manual entry, share and folder picker, scan | **Keep** | Adds **Bring from phone**: a 6-digit code so the phone app hands over servers and credentials, with no typing. |
| Library: collections, mixes, posters or rows, sort, A–Z rail | **Keep** | Becomes **the Wall**: a curved poster wall around you. Pinch-drag or thumbstick to turn it. |
| Home: Continue watching, Newly added, On this device, Moments, Arrange | **Keep** | One panel, same sections, same order setting. |
| Search: titles, folders, moments; chips; recents | **Keep** | **Voice first**, keyboard second. |
| Browse (file-manager view) | **Keep, P1** | Same tree as a panel beside the Wall. |
| Collection page: poster lights the top, figures, Play all, tabs | **Keep** | Poster light becomes light on the room. |
| Title page: picture, badges, Resume/Play, chapters, path | **Keep** | Opens as a panel **beside** the Wall (the foldable's two-pane layout, reused). |
| Player gestures: tap, double-tap ±10 s, hold 2×, edge drags | **Re-express** | Thumbstick and pinch gestures (§7). No edge drags. |
| Scrub by thumbnail | **Keep** | A filmstrip arc above the bar; frames are still made ahead of time. |
| Chapters, Mark, moments, `mkvmerge` sidecars | **Keep** | Same files on the share, so a chapter marked on the phone shows in the headset and back. Naming by voice or chips. |
| Ambient light: Mirror / Color bleed | **Re-express** | **Room glow**: the film's edge colours light the virtual room, or halo the screen in passthrough. |
| Mini player, picture-in-picture | **Re-express** | **Docked screen**: the screen shrinks and parks beside the Wall while you browse. |
| Shorts | **Keep, P1** | A tall portrait screen; flick for the next. |
| Pictures: albums, mosaic, lightbox, story | **Keep, P1** | Mosaic on the Wall; lightbox at wall scale; stories play on the screen. |
| Moments reel | **Keep, P1** | Unchanged in behaviour. |
| Poster editor, Set as poster | **Keep, P2** | Frame a 2:3 crop with the ray. Writes `poster.jpg` as today. |
| File management: select, move, rename, delete | **Keep, P2** | Dock becomes the selection toolbar, as the nav pill does. Delete is hold-to-confirm. |
| Downloads: Keep on this device | **Keep, P1** | "Keep on this headset". Storage is tighter, so it shows room left before queueing. |
| Uploads from phone | **Drop** | No source of files on a headset. |
| Phone's own videos, "Open with" | **Re-express, P2** | "On this headset": local files and USB. |
| Foldable layouts | **Drop** | Replaced by posture: seated, standing, lying back. |
| App lock (biometric) | **Re-express, P2** | PIN on a number pad; Quest has no per-app biometric. |
| Demo library | **Keep** | Needed for store review and for building without a NAS. |
| Spoof mode | **Keep, P2** | More useful here: on while **casting or recording**, so a stream never shows the real library. |
| Share status dot, connection details | **Keep** | Dot sits on the dock's active tab. |

Shared for free, because it already lives **on the share** rather than in the
app: chapter sidecars, `poster.jpg`, the `.regolith-mix` marker. Not shared:
resume points and watch history (each app has its own database), until
requirement S6 lands.

## 4. What 4XVR offers (the bar to clear)

Researched 2026-11-18 from store listings and third-party guides. The
research session could not open the pages themselves (network allowlist), so
these are from search extracts: **re-check wording before quoting**.

| Area | 4XVR | Regolith VR v1 |
|---|---|---|
| Price | Free trial (time-capped); Premium **$29.99** one-time | Free, side-loaded |
| 2D→3D | "Instant 2D-to-3D" / "AI 3D" toggle, real time on the headset, adjustable strength, can be default for all flat video (since v2.1.4) | **Relief** — match, plus a per-title memory and a comfort ramp |
| Native 3D | Auto-detects SBS / OU, full and half; MVC and MV-HEVC decode | SBS / OU auto-detect (P0); MV-HEVC (P2); MVC out of scope |
| 180 / 360 | Auto-detect | P1 |
| Sources | SMB, DLNA, FTP, SFTP, WebDAV, USB, PC link | SMB only (P0); USB and local (P2) |
| Codecs | H.264, HEVC, AV1, VP9, more | Whatever Quest 3 hardware decodes: H.264, HEVC, AV1, VP9 |
| HDR | HDR and Dolby Vision profiles listed | P2, after a device test |
| Screen | 4 curve presets; cinema, space, void environments; 120 Hz | Size, distance, curve; Void, Theatre, Passthrough |
| Audio / subs | Multi-track, sync offset, 5.1/7.1; subtitles with sync | Track picker and subtitle picker (P0); sync offset (P1) |
| Library | Thumbnails; no poster wall or metadata found | Poster wall, collections, chapters, moments, search |

Where reviewers say 4XVR is weak, and Regolith VR should be strong:
unreliable SMB reconnects, no playlists, no resume-or-restart choice, a
button that exits instead of showing controls, subtitles too low, no image
viewer. Regolith already solves the first three on the phone.

Not found anywhere: how 4XVR's conversion works, its resolution or
frame-rate limits, or whether it is Premium-only. Other players with
real-time conversion on Quest: NEX Player ("Instant 3D").

## 5. The spatial model

Four things exist in the space, and each has one job.

| Thing | What it is | Where |
|---|---|---|
| **Wall** | Curved panel holding Home, Library, Search, Browse, Settings | ~1.5 m, centred slightly below eye level |
| **Dock** | The nav pill. Five tabs; becomes a selection toolbar | Under the Wall, tilted up toward you |
| **Screen** | The video itself, a compositor layer with nothing drawn on it | 2–12 m, any size, flat or curved |
| **Bar** | Transport controls and sheets | ~1 m, below the Screen, hidden while watching |

Rules that follow:

- **Nothing is ever drawn over the picture** except subtitles. Controls live
  on the Bar. (4XVR's "subtitles too low" and Regolith's own "controls stay
  out of the way" both land here.)
- **One red thing per view**, as on the phone.
- The Wall and the Screen are never both full size. Playing puts the Wall
  away; browsing docks the Screen.

## 6. Requirements

Priority: **P0** ships in the first usable build, **P1** in v1, **P2** after.

### Connect

- **C1 (P0)** LAN finder lists SMB hosts; manual address as fallback.
- **C2 (P0)** Sign in with the system keyboard; credentials stay in the
  Android Keystore (same rule as the phone, guardrail G6).
- **C3 (P1)** Bring from phone: headset shows a code, phone app sends its
  servers over the LAN. Needs a small change to the Android app.
- **C4 (P0)** Pick share and folders; scan with visible progress; the Wall
  fills in as posters arrive.
- **C5 (P0)** Share status dot and a details sheet with Check now.

### Library

- **L1 (P0)** Wall of collections and videos; posters or rows; five sorts.
- **L2 (P0)** Home with Continue watching and Newly added.
- **L3 (P0)** Title panel beside the Wall: Resume / Play, badges, chapters.
- **L4 (P0)** Search by voice and keyboard: titles, folders, moments.
- **L5 (P1)** Collection page, Moments tab, Play all, shuffle; mixes.
- **L6 (P1)** Pictures: albums, mosaic, lightbox, story.
- **L7 (P1)** Shorts.
- **L8 (P1)** Keep on this headset, with free space shown.
- **L9 (P2)** Select, move, rename, delete; poster editor.

### Watch

- **W1 (P0)** Screen with size, distance and curve; remembered per posture.
- **W2 (P0)** Bar: play, ±10 s (stacking), scrubber with thumbnail
  filmstrip, chapters, audio track, subtitles, speed.
- **W3 (P0)** Resume where you left off; an explicit **Start over** beside
  Resume.
- **W4 (P0)** Native 3D: detect SBS / OU from filename tags and aspect
  ratio, with a manual override.
- **W5 (P0)** Environments: Void and Passthrough.
- **W6 (P1)** **Relief** (§8).
- **W7 (P1)** Room glow; Theatre environment.
- **W8 (P1)** Chapter wall, Mark a moment, name by voice or chip.
- **W9 (P1)** Docked screen while browsing; Up next card.
- **W10 (P1)** Lie-back: Screen and Bar tilt to where you are looking,
  on one press.
- **W11 (P1)** Pin the Screen to a real wall in Passthrough.
- **W12 (P1)** 180° / 360° video.
- **W13 (P2)** A–B loop, repeat, HDR, subtitle and audio sync offset,
  match refresh rate to the video.

### System

- **S1 (P0)** Controllers and hands at parity (§7).
- **S2 (P0)** Demo library.
- **S3 (P1)** Settings: playback, display, Relief defaults, chapters,
  downloads, privacy.
- **S4 (P2)** PIN lock; Spoof while casting.
- **S5 (P0)** Reduced motion follows the Horizon OS setting.
- **S6 (P1)** Resume points sync between phone and headset through a small
  hidden file on the share (like `.regolith-mix`): no server, no account.
  Decided 2026-11-18. To design: the file's format and where it sits, what
  wins when both devices watched the same film, and a read-only share.
  Needs a matching change in the Android app.

## 7. Input

| Action | Controllers | Hands |
|---|---|---|
| Point and select | Ray + trigger | Ray + pinch |
| Scroll / turn the Wall | Thumbstick, or grip-drag | Pinch-drag |
| Show / hide the Bar | Trigger on empty space | Pinch on empty space |
| Play / pause | A | Double-pinch |
| ±10 s (adds up) | Thumbstick left / right | Pinch-drag on the scrubber |
| Hold for 2× | Hold trigger on the Screen | Hold pinch on the Screen |
| Relief on / off | X | Bar button |
| Mark a moment | B | Bar button |
| Resize / move the Screen | Grip + move; both grips to scale | Pinch the corner handle |
| Back | B (outside the player) / Meta-style back on the Dock | Dock back button |

Constraints from Meta's guidance: targets at least 48 dp (≈3° of view), 60 dp
for primary controls with hands; text 14 px minimum, 18 px for reading;
ray-cast panels around 1 m; the **palm-up pinch is reserved** by the system
and is never used. Every controller shortcut also has a visible button, so
hands lose nothing.

## 8. Relief (2D→3D)

**What the user gets.** One button on the Bar. Off, or On with a **Depth**
slider (0–100, default 40) and a **Pop** slider that moves the scene toward
or away from the screen plane. The setting is remembered per title, with a
default for the library in Settings.

**Behaviour**

- Turning it on **ramps depth up over ~600 ms** rather than snapping, and
  down the same way. A sudden change in depth is what causes eye strain.
- A scene cut resets the depth smoothing so one shot never bleeds into the next.
- If the headset can't keep up, Relief lowers its own quality first (depth
  resolution, then how often depth is recomputed), and only then says
  **"Relief paused — this video is too heavy"**, falling back to flat.
- Files that are already 3D show **3D** in place of Relief; no conversion.
- Subtitles sit at a fixed, comfortable depth just in front of the scene.
- First use shows a short comfort note and the way back (X, or the button).

**How it works (for the implementation plan)**

1. Each video frame is downscaled and passed to a small monocular depth
   model (an ML model that guesses distance from a single picture).
2. The depth map is smoothed over time, then used to shift pixels sideways
   in a shader, giving a left-eye and right-eye picture. This is
   depth-image-based rendering; holes at edges are filled by stretching.
3. Both pictures go to the Screen as a stereo layer.

**Why it is a separate phase.** The only public number is about **8 fps**
for Depth Anything V2 at 518 px on comparable hardware, and whether an app
can reach the chip's neural accelerator on Horizon OS is unconfirmed. The
realistic plan is depth at about 256 px, recomputed every second or third
frame, with flat video capped at 1080p30 while Relief is on at first. It
needs a **spike on a real headset before any UI is built on it** (§11).

## 9. Platform and stack

**Recommendation: Meta Spatial SDK** (Kotlin, runs Jetpack Compose panels in
3D space). The problem: we need 3D placement, a stereo video surface and
passthrough, without throwing away what the owner knows. The choice: Spatial
SDK feeds ExoPlayer straight into a video panel (`stereoMode` for SBS/OU,
cylinder for a curved screen, equirect for 360), and Compose draws the Wall
and Bar. Rejected: Unity or native OpenXR (new language and toolchain, no
Compose); a plain 2D Android panel (max 1440×1000 dp, no free placement, no
Relief).

| Concern | Choice | Note |
|---|---|---|
| UI | Compose panels + Horizon OS UI Set, themed with Regolith tokens | Same tokens as `ui/theme/` |
| Video | Media3 into `VideoSurfacePanelRegistration` | Direct-to-compositor for sharpness |
| SMB | jcifs-ng, written fresh | The Android repo is a reference to read, not code to import |
| Passthrough / walls | `enablePassthrough`, MRUK for wall anchors | |
| Audio | `SpatialAudioFeature` + Media3 session | Sound comes from the Screen |
| Relief | Depth model via ExecuTorch or QNN, custom shader on the readable surface path | Decided by the spike |
| SDK levels | minSdk 32–34, **targetSdk 34** | Horizon OS is Android 14; the phone app targets 37 |

Known constraints: Quest 3 hardware decode tops out at 8192×4096 @ 60;
refresh is 72/80/90/120 Hz (60 allowed for media players), with no
documented 24 fps matching; the SDK is pre-1.0 and breaks between minors.

## 10. Design decisions that differ from the phone

Validated against Emil Kowalski's design-engineering rules and the UI/UX Pro
Max database. Both are written for flat screens, so each rule was kept only
where its reason still holds in a headset.

**Motion (Emil Kowalski review)**

| Before (phone habit or first assumption) | After (VR) | Why |
|---|---|---|
| Hover scales the tile | Hover is a white outline and a 6 mm lift, 100 ms; no scale on tiles | A ray sweeps across dozens of tiles a second; frequent motion must be tiny |
| Thumbstick seek animates the scrubber | Seek is instant; only the time label changes | It is a keyboard-style action, repeated constantly |
| Bar fades in and out at one speed | In: 180 ms ease-out, rising 8 mm. Out: 120 ms | Enter is watched, exit should get out of the way |
| Panels pop from nothing | Enter from 96% scale and 0 opacity | Nothing appears from nothing |
| Sheets open from the centre | Sheets grow from the Bar button that opened them | Origin-aware popovers; only dialogs centre |
| Poster "flies" across the screen | Poster moves toward you into the title panel, ≤300 ms ease-in-out; a cross-fade under reduced motion | Keeps the phone's signature move without sliding the world sideways |
| Screen resize snaps to limits | Resize follows the hand 1:1 and resists past the limits | Friction, not hard stops |
| Relief toggles instantly | Depth ramps over ~600 ms | The one place slower is correct: eye comfort beats snappiness |
| Delete confirms with a dialog | Hold-to-delete: 1.2 s linear fill, 200 ms release | Deliberate press, fast release; no dialog to aim at |
| Wall tiles all appear at once | 30 ms stagger, first 12 tiles only, never blocks input | Stagger is decoration |
| Environment switch cuts | 400 ms cross-fade | Rare, so it can afford to be gentle |
| Large surfaces animate on their own | The Wall and Screen only move while a hand or stick is moving them | Big self-moving surfaces cause motion sickness; a VR rule Emil's list doesn't have |

**Look and layout (UI/UX Pro Max)**

The database's match was "Spatial UI (visionOS)": frosted glass, Inter, a
white accent. Taken: depth as hierarchy, a visible focus state on every
control, focus scale ≤1.04, one or two moving things per view, skippable
onboarding. Rejected: the glass and the typeface. Regolith stays black,
Michroma and Space Grotesk, one red.

| Phone | VR | Why |
|---|---|---|
| Metadata grey `#6E6E6E` | `#8A8A8A` (the existing `NavIdle` token) | 3.7:1 fails 4.5:1, and headset optics are softer than a phone |
| 10–11 px labels | 14 px minimum, 17–19 px body | Meta's floor for legibility |
| 44 dp targets | 48 dp minimum, 60–64 for primary | Ray and pinch are less precise than a thumb |
| Frosted nav pill over content | Frost only for the Dock and Bar; panels are opaque | Text over passthrough must not depend on the room |
| Bottom sheets | Side panels and Bar-anchored sheets | There is no "bottom of the screen" |
| Swipe gestures carry meaning | Every gesture has a button twin | Hands and controllers must be equal |
| Long-press to select | Grip (controller) or pinch-and-hold with a ring filling | Hold needs visible progress when there is no haptic |

## 11. Phases

| Phase | Contents | Exit test |
|---|---|---|
| **0. Spikes** | Relief frame rate on a headset; SMB over LAN on Horizon OS; AGP 9 + Spatial SDK build | Depth model ≥15 fps at 256 px, or Relief is re-scoped |
| **1. Play a file** | Connect, pick folders, flat list, Screen + Bar, resume, Void | A film plays from the NAS and resumes |
| **2. The library** | Wall, Home, title panel, search, scan and artwork, native 3D, Passthrough | Parity with phone Phase 4 |
| **3. Relief** | Conversion, depth controls, fallback ladder | 1080p30 holds for a full film |
| **4. Depth of features** | Chapters and moments, Room glow, Theatre, docked screen, lie-back, Shorts, pictures, downloads | — |
| **5. Polish** | Wall pin, 180/360, file management, PIN, Spoof on cast, Bring from phone | — |

## 12. Risks

1. **Relief performance.** May only reach low depth resolution. Mitigation:
   spike first; quality ladder; ship v1 without it if needed.
2. **SDK churn.** Pin the Spatial SDK version; upgrade deliberately.
3. **24 fps judder** at 72 Hz. Test 72 vs 120 Hz early.
4. **Local network access** on Horizon OS is undocumented. Covered by the spike.
5. **Typing.** Mitigated by voice search, name chips and Bring from phone.

## 13. Decisions and open questions

Decided by the owner, 2026-11-18:

- **Clean start, new repository.** An earlier attempt to port the Android
  code failed, so nothing is shared as a module. The Regolith repo is a
  reference: read it for how a problem was solved, then write it again.
- **Resume points sync** through a hidden file on the share (S6).

Still open:

- **Q1.** Store release or side-load only? A store build must drop the demo
  clips and meet Meta's performance checks.
- **Q2.** Is "Relief" the name for 2D→3D?

## 14. Sources

Research was done through search extracts; the pages could not be opened
directly from the build session. Treat figures as leads to verify.

- 4XVR store listing: <https://www.meta.com/experiences/4xvr-video-player/5936567899722707/>
- 4XVR Premium: <https://www.meta.com/experiences/4xvr-premium-version/608908774737540/>
- 4XVR FAQ: <https://www.4xvr.net/faq/>
- NEX Player Instant 3D: <https://nexplayer.net/en/blog/nex-vr-player-3d-guide-turn-2d-videos-into-real-time-3d-on-meta-quest-3>
- Spatial SDK media playback: <https://developers.meta.com/horizon/documentation/spatial-sdk/spatial-sdk-media-playback>
- Spatial SDK samples: <https://github.com/meta-quest/Meta-Spatial-SDK-Samples>
- Spatial SDK passthrough: <https://developers.meta.com/horizon/documentation/spatial-sdk/spatial-sdk-passthrough/>
- Hit targets: <https://developers.meta.com/horizon/design/styles_inputs_hit_targets/>
- Typography: <https://developers.meta.com/horizon/design/styles_typography/>
- Hands: <https://developers.meta.com/horizon/design/hands/>
- Media requirements: <https://developers.meta.com/horizon/documentation/android-apps/media-requirements/>
- Refresh rates: <https://developers.meta.com/horizon/documentation/native/android/mobile-display-refresh-rate/>
- Depth Anything V2 on XR2 Gen 2 (estimate): <https://aihub.qualcomm.com/jobs/jp4vdo12p>
- ExecuTorch on Quest: <https://ai.meta.com/blog/executorch-reality-labs-on-device-ai/>
