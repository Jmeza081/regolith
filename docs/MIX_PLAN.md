# Mixes — plan

Status: steps 1–7 built 2026-11-09: a folder can be marked and unmarked
(one at a time from ⋯ More, or several from the selection bar), the scan
reads the mark, and a Videos wall holding both kinds is split into sections
with All · Collections · Mixes chips. Not yet seen on a device. Decisions
below are the owner's.

## The problem

Some folders are one story: `Band Practice/`, a concert, a tour. Others are
a grab bag the owner still names: `Assorted/`. A grab bag has no poster
because no one video stands for it, so it shows the deck the app deals. On
a wall of many folders, the grab bags are hard to pick out.

"Has a poster" is the wrong test. A poster says what a folder *looks
like*, not what *kind* of folder it is. A story folder that has no poster
yet would land in the wrong bucket. So would a grab bag someone gives a
poster.

## Decisions (owner, 2026-11-09)

| Question | Answer |
|---|---|
| What is it called? | **Mix.** Every other folder stays a **Collection**. |
| Who decides? | **The owner, by hand.** The app never guesses. Every folder starts as a Collection. |
| Where is it kept? | **On the share**, as a hidden marker file inside the folder, the way `poster.jpg` is. |
| How does the wall show it? | **Split into sections by default** (Collections, then Mixes), with a filter to show only one. |

## How it works for the owner

1. **Mark a folder.** On a folder's page: **⋯ More › Mark as mix**
   (**Mark as collection** undoes it). On a wall: select several folders,
   then **Mark as mix** in the selection bar, to tag the grab bags in one go.
2. **The wall splits.** Once a wall holds at least one mix, it gets two
   headed sections: **Collections** (everything as today) and then **Mixes**.
   A wall with no mixes looks exactly as it does now.
3. **Filter.** A row of chips under **Videos**: **All · Collections · Mixes**.
   *All* is the split view. Remembered across launches, like the view mode.

## The marker file

- **Name:** `.regolith-mix`, empty, inside the folder. It moves and renames
  with the folder for free, because it lives in it.
- **Hidden twice:** the leading dot hides it on a Mac or Linux box, and the
  app also sets the SMB *hidden* attribute (`ATTR_HIDDEN` in jcifs-ng) so
  Windows Explorer hides it too.
- **Why on the share and not in the app's database:** it survives a
  reinstall and works from any phone. Posters already work this way:
  the share is the truth, the app is a view of it. The cost is that it
  needs write access, which the poster tools already need.
- **Browse doesn't show it.** `JcifsGateway.list` drops every dot-file
  today (`.DS_Store` and the like). The owner's "Browse shows every file"
  rule is about *their* files, and this one is the app's bookkeeping, like
  `.DS_Store`.

## What changes in the code

| Layer | Change |
|---|---|
| `domain/library` | `MixMarker` (`NAME = ".regolith-mix"`, `foundIn`, `pathIn`) and `MixOutcome`, in `Mix.kt`. A plain `mix: Boolean` on the folder rather than a new enum. Kept apart from `FolderKind`, which the scan *guesses* from names; a mix is *chosen*. |
| `data/smb` | `list(keep = …)`: dot-files are still dropped, except the names the caller asks for; only the scan asks for the marker. `hide()` sets the SMB hidden attribute after the marker is written. |
| `data/db` | `FolderEntity.mix: Boolean` (schema v19, migration adds the column, default false). |
| `data/repository` | The folder listing (`LibraryRepository.refreshFolder`, where `FolderClassifier` runs) stores the flag, at no extra trip to the share. `MixRepository.setMix(folderId, mix)` writes or deletes the marker, then updates the row straight away without waiting for a rescan, as the poster tools do. |
| `ui/library` | `LibraryTile.Collection.mix`. The ViewModel splits the sorted tiles into sections. `LibraryUiState.mixFilter`. Section headers use `GridItemSpan(maxLineSpan)`, as the profile header already does. The filter row reuses `FilterChip` from `ui/components/Chip.kt`. |
| `ui/util` | The **Mark as mix** action joins `FileActions` / the selection bar, so Library and Browse share it. |
| prefs | The remembered filter, next to the remembered view mode. |

Test tags: `library_mark_mix`, `library_mark_collection`,
`library_section_collections`, `library_section_mixes`, `library_mix_chips`,
`library_mix_filter_<all|collections|mixes>`, `library_select_mix`,
`library_select_unmix`.

## How steps 5–6 came out

- **One place arranges the wall.** `LibraryViewModel.arranged()` sorts the
  chip's wall, then splits it with `byMix` (`domain/library/Mix.kt`, pure and
  unit-tested). Every change (new data, a sort, a chip) goes through it, so
  the sections never fall out of step. `LibraryUiState.tiles` is what's on
  screen, so Select all and Play all act on what you can see.
- **Sections and chips only when there's a choice.** A wall needs at least
  one mix *and* at least one other tile. Otherwise it looks as it always did.
- **The chips** reuse `FilterChip` with its `trailing` count, lit white like
  Videos · Moments · Images above them (the Library keeps red for Play all).
  **The headings** reuse `Eyebrow(large = true)`, the style the codebase
  uses where a label is what says one run has ended.
- **No A–Z rail on a split wall:** its letters would run A to Z twice. With a
  chip narrowing it to one kind, the rail comes back.

## How step 7 came out

- **A fifth verb in the selection pill**, between Rename and Delete, only
  while nothing but folders is picked (the pill holds five; picture
  selections already use five). It reads **Mix**, or **Unmix** when every
  picked folder is a mix already. "Collection" is too long for a pill cell.
- `FileSelectionChrome(folderVerb = …)` is the slot, so Browse could offer
  it later without a second toolbar. Greyed where Delete is (demo, phone,
  spoof mode).
- Folders are marked one by one; a read-only or unreachable server stops
  the run, since every folder after would fail the same way. The message
  says how many got done (`mixMessage`, unit-tested).

## Build order

1. ✅ Domain type and marker name, with unit tests.
2. ✅ Gateway: report the marker; write it hidden; delete it.
3. ✅ Schema v19 and the scan storing the flag. Test with a marker made by
   hand on the share.
4. ✅ **Mark as mix / Mark as collection** on a folder's page (⋯ More).
5. ✅ Wall sections.
6. ✅ Filter chips, remembered.
7. ✅ Multi-select **Mix** / **Unmix**.
8. Docs: `ARCHITECTURE.md` (scan diagram + a decision entry), `README.md`.

Each step ships on its own; steps 1–4 alone already let a folder be marked.

## Still open (small, can be settled while building)

- **Loose videos on a wall** (videos sitting next to folders, not in one):
  they go in the Collections section, as the wall shows them today.
- **Moments and Images chips:** sections and the filter apply under
  **Videos** only in the first version.
- **Read-only share:** the Mark action shows the same "can't write here"
  message the poster tools show.
- **Later, maybe:** a one-time nudge listing folders without a poster:
  "Any of these mixes?" The poster is only a hint for *which folders to
  ask about*, never the decision.
