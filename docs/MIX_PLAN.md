# Mixes — plan

Status: planned 2026-11-09, not built. Decisions below are the owner's.

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
| `domain/library` | `FolderStyle { COLLECTION, MIX }` and `MixMarker.NAME = ".regolith-mix"`. Kept apart from `FolderKind`, which the scan *guesses* from names; a style is *chosen*. |
| `data/smb` | `list()` still drops dot-files but reports whether the marker was among them (`SmbListing.mix`, or a flag on the result). A `setHidden()` call after writing the marker. |
| `data/db` | `FolderEntity.mix: Boolean` (schema v19, migration adds the column, default false). |
| `data/repository` | The folder listing (`LibraryRepository`, where `FolderClassifier` runs) stores the flag, at no extra trip to the share. `markMix(folderId, mix)` writes or deletes the marker, then updates the row straight away without waiting for a rescan, as `FolderPosterWriter` does for posters. |
| `ui/library` | `LibraryTile.Collection.mix`. The ViewModel splits the sorted tiles into sections. `LibraryUiState.mixFilter`. Section headers use `GridItemSpan(maxLineSpan)`, as the profile header already does. The filter row reuses `FilterChip` from `ui/components/Chip.kt`. |
| `ui/util` | The **Mark as mix** action joins `FileActions` / the selection bar, so Library and Browse share it. |
| prefs | The remembered filter, next to the remembered view mode. |

Test tags: `library_section_collections`, `library_section_mixes`,
`library_mix_filter_<all|collections|mixes>`, `collection_mark_mix`.

## Build order

1. Domain type and marker name, with unit tests.
2. Gateway: report the marker; write it hidden; delete it.
3. Schema v19 and the scan storing the flag. Test with a marker made by
   hand on the share.
4. **Mark as mix / Mark as collection** on a folder's page.
5. Wall sections.
6. Filter chips, remembered.
7. Multi-select **Mark as mix**.
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
