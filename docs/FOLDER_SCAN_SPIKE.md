# Spike: scan one folder, and notice changes without a full rescan

Branch `spike/folder-scan`, from main 3abec75 (2026-11-06). Findings only, no app code yet.

## The question

Regenerate artwork already works on one collection. Can a *scan* work the
same way, and better, can the app learn that a folder it knows about has
changed without walking the whole share?

## What already exists

- **A folder-scoped walk is already in the code.** `LibraryRepository.refreshTree(start)`
  is the scan's own loop, and a collection's pull to refresh calls it from
  the collection (2026-10-11). It lists that folder and everything under it,
  marks vanished files missing, and feeds `ArtworkRepository.onFolderListed`
  and `ChapterSyncRepository.onFolderListed`, exactly as a scan does.
- What the pull lacks compared with a scan:
  - It runs in the page's coroutine scope, so leaving the page stops it. A
    scan is a foreground WorkManager job that survives that.
  - It never queues the artwork walk or chapter walls afterwards. New videos
    found by a pull wait for the next full scan for their posters.
  - It is not offered anywhere but the pull gesture.

So "Scan this folder" is mostly wiring: a `ScanWorker` input key
`scopeFolderId` (the same pattern as `ArtworkWorker`'s `scopeFolderId`),
`refreshTree(folder)` instead of `refreshTree(root)`, then the scoped
`ArtworkPrefetcher.regenerate`-style follow-up without the forgetting.
The menu entry sits beside "⋯ › Regenerate artwork".

## Detecting change without walking

Web analogy: a full scan is polling every URL; what we want is an ETag or
a WebSocket.

### 1. Folder modified time (the ETag) — cheap, but shallow

Every listing already returns each subfolder's modified time (`SmbEntry.modifiedAtMs`;
`FolderEntity` does not store it today). On NTFS, ext4/Samba and ZFS a
folder's time changes when a **direct child** is added, removed or renamed.

It does **not** change when:
- something deeper changes (a grandchild's new file does not touch the grandparent);
- a file is overwritten in place (a replaced `poster.jpg` keeps its folder's time).

So a stored stamp lets the walk skip *reconciling* an unchanged folder
(no Room writes, no artwork/chapter hooks), but it still has to list it to
learn its children's stamps. It saves CPU and database work, not round
trips. Worth having, not the answer on its own.

### 2. SMB change notifications (the WebSocket) — the real answer

jcifs-ng 2.1.10, already a dependency, has `SmbResource.watch(filter, recursive)`
(SMB2 CHANGE_NOTIFY). The server holds the request open and answers with
the names that changed (added, removed, renamed, modified) anywhere under
the folder when `recursive = true`.

Limits that shape the design:
- **Only while connected.** It is a long-poll on an open handle. Nothing is
  heard while the app is closed, the phone sleeps or the VPN drops. It
  complements scans; it cannot replace them.
- **Overflow.** A big copy can overflow the server's buffer; the answer is
  then "too much changed" (`STATUS_NOTIFY_ENUM_DIR`) and the only fix is to
  re-list the folder. That is just `refreshTree(folder)`.
- **Server support varies.** Windows supports recursive watches. Samba does
  through its notify daemon, but some NAS builds turn it off
  (`change notify = no`). Must be detected and fall back to polling.
- One handle per watched folder per connection, so watch a few folders
  (the open collection, the Library's tops), not every folder.

## Suggested shape

1. **Scan this folder** (small, safe): scoped `ScanWorker` + artwork follow-up,
   in ⋯ More on a wall/profile and in Browse. Release: minor.
2. **Store folder stamps** (`FolderEntity.modifiedAtMs`, schema bump): a rescan
   skips the reconcile for unchanged folders, and on app resume the Library
   can list just the top folders and re-walk only the ones whose stamp moved.
   Catches changes made while the app was closed, one level deep.
3. **Live watch** (experiment): while a collection is open, `watch(recursive)`
   on it; a notification re-lists the folders named; overflow re-walks it.
   Needs testing on the owner's real server first, since support is the
   unknown.

## Open questions for the owner

- Which server software is the share on (Windows, Synology, TrueNAS, plain Samba)?
  That decides whether step 3 works at all.
- Should "Scan this folder" also regenerate artwork, or only make what is missing?
  (Recommendation: only what is missing; Regenerate already exists for the other.)
