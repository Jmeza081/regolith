repo: Jmeza081/micro-budget
branch: main

## Last sync

date: 2026-09-05T23:11:00Z

### Updated in this project

- Replaced all 114 icons in Sable with Pixelarticons — the set the micro-budget app itself draws (`ui/icons/MbIcons.kt`), filled on a 24px integer grid.
- Glyphs present in `MbIcons.kt` (arrow-left, chevrons, check, warning, grid) are that file's verbatim path data; the rest are pulled verbatim from upstream `halfmage/pixelarticons@master`.
- Confirmed the app's real type stack in `ui/theme/Type.kt` — Space Grotesk (display) + Manrope (body) — and kept it as typography option 2a.
- Added four typography pairings to the canvas for review (2a–2d).

## Screen map

| Screen | Built from |
|---|---|
| All Sable screens | docs/monochrome-starter.md §2 colour, §3 type, §5 layout, §9 states |
| Iconography, every screen | app/.../ui/icons/MbIcons.kt · docs/design-system.md §5 · halfmage/pixelarticons |
| Typography options 2a–2d | app/.../ui/theme/Type.kt · docs/monochrome-starter.md §3 |
| Empty / failed / loading states | docs/monochrome-starter.md §9, §4 voice |
| Connecting + scanning | docs/monochrome-starter.md §8 illustration (pixel grid) |
| Settings, dialogs | docs/monochrome-starter.md §6 reversibility |

## Sync history

- 2026-09-01T19:28:41Z — read `docs/monochrome-starter.md` in full; adopted the monochrome dark palette, Space Grotesk + Manrope, the four-state rule and the no-spinner loading pattern.
