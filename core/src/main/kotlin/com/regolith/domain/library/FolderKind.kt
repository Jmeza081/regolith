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

    /** `Films/`, `Series/`: holds title folders, shows or loose files; a tile on Library. */
    COLLECTION,

    /** `Arrival (2016)/`: one title, its file(s), maybe a poster. */
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
        if (videos.isNotEmpty() && folders.isEmpty() && (TitleParser.parseFolderName(name).year != null || videos.size == 1)) return FolderKind.TITLE
        if (folders.isNotEmpty() || videos.size > 1) return FolderKind.COLLECTION
        return FolderKind.PLAIN
    }
}
