# Chapter sidecars

Regolith keeps the chapters you write for a film in a small text file next
to it on the share (P10). The file is the contract: anything that writes it
— Regolith on a phone, a desktop editor, a text editor — is picked up by
the next scan or the next Browse of that folder. Nothing else needs to know
about the phone's database.

## Name

`<video basename>.chapters.txt`, in the same folder as the video.

| Video | Sidecar |
|---|---|
| `Heat.1995.mkv` | `Heat.1995.chapters.txt` |
| `Severance.S01E04.1080p.mp4` | `Severance.S01E04.1080p.chapters.txt` |

Not dot-prefixed: Regolith's listings hide dot-files. One file per video;
a folder-level file means nothing.

## Format

mkvmerge's "simple" chapter format, UTF-8, LF line endings (CRLF is read
fine). Two lines per chapter: the start time and the name.

```
CHAPTER01=00:00:00.000
CHAPTER01NAME=Intro
CHAPTER02=00:12:30.000
CHAPTER02NAME=The heist
CHAPTER03=00:41:05.500
CHAPTER03NAME=
CHAPTER04=01:02:15.000
CHAPTER04NAME=Aftermath
```

- Times are `hh:mm:ss.mmm`. Regolith also reads `mm:ss`, `hh:mm:ss` and
  fractions of any length (kept to the millisecond, shown to the tenth).
- An **empty NAME** is a chapter with no name; Regolith shows it as
  "Part n" and does not index it for search. A NAME that is exactly
  `Part n` (the app's own fallback label) is read the same way, so a
  file written by a tool that insists on a name still round-trips.
- The first chapter is the start of the film. If a file has no `00:00:00`
  chapter, Regolith adds an unnamed one when reading.
- Chapters past the film's runtime are ignored. Duplicate times keep the
  first. Lines that do not match `CHAPTERnn=` / `CHAPTERnnNAME=` are
  skipped. Order in the file does not matter.
- Caps: 64 KiB, 999 chapters. Larger is not a chapter file.

The same file can be baked into an MKV with mkvtoolnix:

```
mkvpropedit "Heat.1995.mkv" --chapters "Heat.1995.chapters.txt"
```

Regolith reads container chapters too; while the sidecar exists it wins
over them (yours › the file's own › the even split).

Whether mkvmerge accepts an empty `NAME` line could not be checked on the
machine this was written on (no mkvtoolnix installed). If it refuses,
write `Part n` as the name instead; Regolith reads both as unnamed.

## Who wins

The sidecar is the durable copy. The phone keeps a cache of it (so search
is instant and offline play has chapters) and syncs:

- **Import.** Every scan and every Browse listing compares the file's
  modified time with the last one imported; a newer file replaces the
  phone's copy. A **blank file counts as nothing** and is skipped, not
  imported as "no chapters": it would otherwise delete chapters the phone
  has. (Reading a sidecar never creates one either — see
  `docs/ARCHITECTURE.md`, chapter sidecars.)
- **Write.** Pressing Done in the app writes the phone's copy first, then
  the file in the background (a `.chapters.txt.part` file, renamed over
  the real name once complete, so a half-written file is never read).
- **Conflict.** If both changed since the last sync, the newer one wins,
  whole. There is no merge.
- **Read-only share.** The chapters stay on the phone and the app says so;
  the write is retried at the next scan. Each share has a switch to turn
  writing off.
- **Revert** (on the player's Chapters sheet) deletes the phone's copy
  and the file. **Settings › Chapters › Clear** empties the phone's cache
  only and never touches the share; films with a file get theirs back at
  the next scan.

## Writing one by hand or from another app

Write the file next to the video with the exact name above and the format
above, then scan the share in Regolith (Settings › Scan all, or open the
folder in Browse). Overwriting the file with a newer modified time is
enough for the phone to pick up the change.
