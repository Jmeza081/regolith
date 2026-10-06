package com.regolith.domain.library

import com.regolith.domain.media.MediaFileTypes

/**
 * What a folder on the share is, from its name and what it directly
 * contains (design section 08, "Expected share layout"). Decided from one
 * listing so the scan classifies as it walks, no second pass.
 */
enum class FolderKind {
    /** The share root itself. */
    ROOT,

    /** `Films/`, `Home Videos/`, `Arrival (2016)/`: holds videos or folders of them, however few; a tile on Library. */
    COLLECTION,

    /**
     * No longer produced. It was `Arrival (2016)/` read as one title and
     * shown as its largest video. Every folder that holds videos is now a
     * [COLLECTION], even one holding a single video, because more may be
     * added and its poster is the folder's own, not any one video's (the
     * owner's model, 2026-10-05). Kept so rows scanned before then still
     * parse; everything reads them as a collection.
     */
    TITLE,

    /** `Severance/`: seasons or episodes inside. */
    SHOW,

    /** `Season 01/`. */
    SEASON,

    /** Nothing playable in it or under it that we can tell; still browsable. */
    PLAIN,
}

object FolderClassifier {
    /**
     * @param name the folder's own name.
     * @param isRoot true for the share root.
     * @param entryNames names of the folder's direct children (files and folders).
     * @param isDirectory tells folders from files for each entry.
     */
    fun classify(name: String, isRoot: Boolean, entryNames: List<String>, isDirectory: (String) -> Boolean): FolderKind {
        if (isRoot) return FolderKind.ROOT
        val folders = entryNames.filter(isDirectory)
        val videos = entryNames.filter { !isDirectory(it) && MediaFileTypes.isVideo(it) }
        if (TitleParser.seasonNumber(name) != null) return FolderKind.SEASON
        if (folders.any { TitleParser.seasonNumber(it) != null }) return FolderKind.SHOW
        if (videos.isNotEmpty() && videos.all { TitleParser.parseVideoName(it).episode != null }) return FolderKind.SHOW
        // A folder of videos is a collection however few it holds, even one:
        // it is a container its owner may add to, never "the" one video.
        if (folders.isNotEmpty() || videos.isNotEmpty()) return FolderKind.COLLECTION
        return FolderKind.PLAIN
    }
}
