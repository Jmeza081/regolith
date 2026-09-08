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
over the `NavDisplay`, and only when the top key belongs to a tab.

| Tab | Key | Screen | testTag |
|---|---|---|---|
| Home | `Home` | `HomeScreen` | `nav_home` |
| Library | `Library` | `LibraryScreen` | `nav_library` |
| Browse | `Browse(folderId?)` | `BrowseScreen` | `nav_browse` |
| Settings | `Settings` | `SettingsScreen` | `nav_settings` |

Switching tab resets the stack to `[Home, tab]` (just `[Home]` for Home), so
system back from any tab returns to Home and back from Home leaves the app.
`Browse(folderId)` at any depth is still the Browse tab.

## Pushed screens (pill hidden)

| Key | Reached from | Phase |
|---|---|---|
| `Onboarding` | first launch only; "Find my server" → `[Home]` | 0 (stub), 6 (full) |
| `TitleDetail(fileId)` | Library, Home, Search, Browse | 3 |
| `Player(fileId, startMs?)` | TitleDetail, Home resume row, Browse | 1 |
| `AddServer.Manual` | Home / Library empty states, Settings | 1 |
| `AddServer.Connecting(serverId)` → `Shares(serverId)` → `Scanning(serverId)` | Manual entry | 1 (scan: 4) |
| `AddServer.Search` | Home / Library empty states, Onboarding | 6 |

Bottom sheets (sort, playback, A–B loop) are not routes; they are state in
the owning screen's `UiState`.
