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

| Tab | Key | Screen | testTag |
|---|---|---|---|
| Home | `Home` | `HomeScreen` | `nav_home` |
| Library | `Library(folderId?, onDevice)` | `LibraryScreen` | `nav_library` |
| Browse | `Browse(folderId?)` | `BrowseScreen` | `nav_browse` |
| Settings | `Settings` | `SettingsScreen` | `nav_settings` |

Switching tab resets the stack to `[Home, tab]` (just `[Home]` for Home), so
system back from any tab returns to Home and back from Home leaves the app.
`Browse(folderId)` and `Library(folderId)` at any depth are still their tabs.

## Library nests too

`Library(folderId = null)` is the poster wall of every enabled share's root:
collections and loose titles. Tapping a collection pushes `Library(folderId)`,
that folder's wall. Titles push `TitleDetail`. The search icon pushes `Search`.
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
through folders you skipped. The tree is dropped below 600dp of screen width. A file pushes `TitleDetail(fileId)`,
whose red Play pushes `Player(fileId)` (since Phase 3; in Phases 1–2 a file
opened the player directly).

## Title Detail is a pane on a wide window

`Library` carries `ListDetailSceneStrategy.listPane()` metadata and
`TitleDetail` carries `detailPane()`. On a wide window the strategy renders the
top two keys as one two-pane scene: the wall on the start edge (inside the
rail's inset), the detail beside it, with "Choose a title" in the pane until
one is picked. **The back stack is identical either way** — back pops the
detail first, then the wall — and the rail keeps the tab that owns the wall.
Picking another title replaces the open detail rather than stacking one.

A detail opened from `Home`, `Search` or `Browse` has no wall beneath it, so it
fills the window and keeps the back arrow. Browse spends its width on the share
tree instead (below). On a compact window nothing changes: the
detail is pushed and slides in, as it has since Phase 6.

## Pushed screens (pill hidden)

| Key | Reached from | Phase |
|---|---|---|
| `Onboarding` | first launch only; Skip → `[Home]`, "Find my server" → `[Home, AddServer.Search]` | 6 |
| `AddServer.Manual(prefill?)` | "Enter an address" on the finder and Home; a found host arrives as `prefill` | 1, 6 |
| `AddServer.Shares(serverId)` | Manual entry, after a successful connect | 1 |
| `Player(fileId, startMs?)` | TitleDetail; Home resume row later | 1 |
| `TitleDetail(fileId)` | Browse, Library, Home "Newly added", Search | 3 |
| `Search` | Home's and Library's search icon; a hit opens `TitleDetail` or `Browse(folderId)` | 4 |
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

Bottom sheets (sort, playback, A–B loop) and the disconnect confirm are not
routes; they are state in the owning screen's `UiState`.
