# Navigation map

How you reach every screen. A reachability map, not a restatement of
`NavGraph.kt`. **Keep this current**: any change that adds or removes a key
or changes how a screen is reached updates this file in the same commit.

Routes are the `@Serializable` keys in `ui/navigation/RegolithKey.kt`; the
wiring is `ui/navigation/NavGraph.kt`; the tab set is `ui/navigation/MainTab.kt`.

## Model (Navigation 3)

There is no XML graph and no NavController. The back stack is a plain list
of keys that the app owns (`rememberNavBackStack`). Pushing a screen is
`backStack.add(key)`; back is `removeLastOrNull()`. Arguments are constructor
parameters on the key, never strings in a path.

Web analogy: React Router where the history stack is state you can read and
edit directly.

## Start

`AppViewModel.startDestination` reads the `onboarding_done` flag from
DataStore. Until it resolves the system splash stays up; then the stack
starts on `Onboarding` (first run) or `Home`.

## Tabs

`MainTab` has four entries. The pill is drawn once, by `RegolithNavGraph`,
over the `NavDisplay`, and only when the top key belongs to a tab. On a wide
window (`LocalWindowShape.wide`: a foldable's inner display, a tablet) the
same `NavPill` is drawn `vertical` as a rail on the start edge, and the four
tab screens are inset by `NAV_RAIL_INSET` so they sit beside it; pushed
screens keep the full width. Same keys, same tags, same back stack.

The rail can be away in two different ways, and they are not the same thing:

- **Idle** — a few seconds after the last touch anywhere in the app the rail
  slides off the start edge, leaving `NavRailSpine`: four dots, the current
  tab lit. The reserved inset does **not** change, so nothing reflows and the
  wall never jumps while you read it. Governed by
  `Settings › Display › Auto-hide the navigation` (on by default; it hides a
  phone's pill on the same timer), and the wait is `Settings › Display ›
  Hide after` (`NavHideAfter`: 5 s, 10 s or 30 s, default 10 s).
- **Pinned away** — the 44dp circle below the rail (`nav_rail_hide_button`,
  a sibling of the pill rather than a cell inside it) collapses it for good; `railHidden` is remembered
  in preferences and the inset drops from `NAV_RAIL_INSET` (102dp) to
  `NAV_RAIL_SPINE_INSET` (22dp), so the Library wall gets its third tile at
  full width. Content reflows, which is why this only happens on request.

Tapping the spine (`nav_rail_spine`) brings the rail back either way — and
un-pins it when it was pinned. Touches are observed on the root `Box` in the
`Initial` pointer pass and reported down a `MutableSharedFlow`, not into
state, so a scroll does not recompose the tree on every frame.

| Tab | Key | Screen | testTag |
|---|---|---|---|
| Home | `Home` | `HomeScreen` | `nav_home` |
| Library | `Library(folderId?, onDevice)` | `LibraryScreen` | `nav_library` |
| Browse | `Browse(folderId?)` | `BrowseScreen` | `nav_browse` |
| Shorts | `Shorts` | `ShortsScreen` | `nav_shorts` |
| Settings | `Settings` | `SettingsScreen` | `nav_settings` |

Switching tab resets the stack to `[Home, tab]` (just `[Home]` for Home), so
system back from any tab returns to Home and back from Home leaves the app.
`Browse(folderId)` and `Library(folderId)` at any depth are still their tabs.

## Library nests too

`Library(folderId = null)` is the poster wall of every enabled share's root:
collections and loose titles. Tapping a collection pushes `Library(folderId)`,
that folder's wall (on a wide window, closing an open page first — see
below). Titles push `TitleDetail`. A collection's back arrow leaves the wall,
taking an open page with it, and cross-fades as it does without one. The
search icon pushes `Search`.
`Library(onDevice = true)` lands on the "On this device" tab; Home's
"N downloads ready" row resets the stack to `[Home, Library(onDevice)]`.

## Browse is a tab that nests

`Browse(folderId = null)` is the tab root: the enabled shares. Tapping a
share creates (or finds) its root folder row and pushes `Browse(folderId)`;
tapping a folder pushes another `Browse(folderId)`. The pill stays visible at
every depth and back pops one level. On a wide window a share tree stands to
the left of the list (`ShareTree`), listing every enabled share and its
top-level folders; tapping one RESTARTS the chain (`[Home, Browse(folder)]`)
rather than pushing, so back from a jump leaves Browse instead of walking back
through folders you skipped. The tree needs 600dp of Browse's own width, so
it stands beside the list while Browse has the window and steps aside while a
file's page is open beside it. A file pushes `TitleDetail(fileId)`,
whose red Play pushes `Player(fileId)` (since Phase 3; in Phases 1–2 a file
opened the player directly).

## Title Detail is a page beside the wall on a wide window

`Library` and `Browse` carry `WallSceneStrategy.wall()` metadata and
`TitleDetail` carries `WallSceneStrategy.page()` (`ui/navigation/WallScene.kt`).
On a wide window:

- **Nothing open** (`[…, Library]`): the wall has the whole window beside the
  rail, four to seven posters across the inner display (Settings › Display ›
  Posters per row, or a pinch on the wall).
- **A title open** (`[…, Library, TitleDetail]`): its page takes the end half
  of the window; the wall reflows into the other half and rings the open
  tile. Opened from a tile, the page fades in there while the tile's poster
  flies into its picture (`PosterFlight.kt`, a flight made by hand because
  the tile stays on screen); opened any other way it slides in from the end
  edge. The split is even and does not move.
- **Closing it** — the close-panel button on the page (`detail_close_button`,
  a side panel with a chevron), system back, or the ringed tile tapped again
  — pops `TitleDetail`: the page slides back out while the wall dissolves,
  and the wall returns at full width. Back closes only the page, never the
  wall. The wall's scene answers system back itself with the close button's
  own pop, so it is not predictive here: a swipe closes the page once it is
  let go, and all three ways look the same.

**The back stack is identical to a phone's either way**, and the rail keeps
the tab that owns the wall. Picking another title replaces the open page
rather than stacking one; walking into a collection or folder from the wall
closes the page first.

A page opened from `Home` or `Search` has no wall beneath it, so it fills the
window and keeps the back arrow. On a compact window the page is pushed, as
it has been since Phase 6, but since 2026-10-07 it fades in with a small rise
instead of sliding (`pageScreen`), and the poster of the tile that opened it
flies into its picture; back reverses both, and the back swipe scrubs them.
A full-window page on a wide window does the same.

## Pushed screens (pill hidden)

One exception: while something is picked, Search brings the nav chrome up
as the selection's toolbar (never as tabs), because that toolbar is the only
place the selection's verbs live.

| Key | Reached from | Phase |
|---|---|---|
| `Onboarding` | first launch only; Skip → `[Home]`, "Find my server" → `[Home, AddServer.Search]` | 6 |
| `AddServer.Manual(prefill?)` | "Enter an address" on the finder and Home; a found host arrives as `prefill` | 1, 6 |
| `AddServer.Shares(serverId)` | Manual entry, after a successful connect | 1 |
| `AddServer.Folders(shareId, relPath)` | A share's chevron on Choose a share, and its own rows going deeper; one key per level, Done pops them all | P1 |
| `Player(fileId, startMs?, expand?)` | TitleDetail; Home resume row; a Search point of interest pushes it with `startMs` at the chapter (P9); the mini player pushes it with `expand` set, which picks the film up as it was instead of loading it again | 1 |
| `TitleDetail(fileId)` | Browse, Library, Home "Newly added", Search | 3 |
| `Search(poi?)` | Home's and Library's search icon; a chip in Home's Moments section pushes it with `poi` set, which opens it with that moment already picked and the keyboard down; a hit opens `TitleDetail` or `Browse(folderId)`; a point of interest opens `Player(fileId, startMs)` | 4 |
| `AddServer.Scanning(serverId)` | Share picker "Scan N shares"; "Run in the background" → `[Home]`, "Open the library" → `[Home, Library]` | 4 |
| `AddServer.Search` | "Add source server" on Home / Library / Settings, Onboarding; a tapped host → `AddServer.Manual(prefill)` | 6 |

`AddServer.Connecting` exists as a key but is not a route: the Connecting and
Sign-in-failed screens are states of `ManualEntryScreen` so the typed
address and credentials survive a failure. "Scan N shares" on the picker
pushes `Scanning`; leaving it (either button) resets the stack to a tab,
dropping the whole Add Server flow. Home's resume row pushes `Player(fileId,
startMs)` directly.

The Player is the only screen that changes Activity-level settings
(landscape, immersive); it applies them in a `DisposableEffect` and undoes
them on the way out.

**Putting the player away.** Back, the swipe down and the arrow pop `Player`
as before, but the film keeps playing: `PlaybackSession` keeps it, and the
mini player shows it over whatever is underneath (`ui/player/MiniPlayer.kt`).
On a phone that is a bar above the pill, docking at the bottom on its own
when the pill slides away or on a pushed page; on a wide window, a card in
the bottom corner, in the wall's half beside an open page. The player moves
itself on its own transition (`playerScreen`): its picture shrinks into the
mini player's spot as the rest of it fades, and the back swipe scrubs that.
Tapping the mini player pushes `Player(…, expand = true)`, which grows out
of it; its ✕ stops the film. A film that has ended or failed stops when the
player is left, and one that ends in the mini player with nothing after it
closes the mini player.

Bottom sheets (sort, playback, A–B loop) and the disconnect confirm are not
routes; they are state in the owning screen's `UiState`.

## Tapping the current tab

A tab cell switches tabs. The *current* tab's cell brings its stack back to
the top: `Library(folderId)` three levels deep becomes `Library`. Already at
the top, the tap does not navigate, so the screen is not rebuilt. It is
reported instead, on `tabReselects` in `NavGraph.kt`, for a screen that has
something useful to do with it. Today only Shorts listens: it reshuffles and
goes back to the first clip.
`navigateToTab` decides this by comparing the stack's top with the tab's
root key, not by comparing tabs.
