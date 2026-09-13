package com.regolith.data.transfer

import android.content.Context
import android.os.StatFs
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The downloads directory: `filesDir/downloads/{fileId}.{ext}`. App-private
 * like the artwork, so no storage permission and uninstalling removes it.
 * Files are appended to as a transfer resumes; a `.part` suffix marks one
 * that is not finished yet.
 */
@Singleton
class DownloadStore @Inject constructor(@ApplicationContext context: Context) {
    val root: File = File(context.filesDir, "downloads")

    fun relPathFor(fileId: Long, ext: String): String = "$fileId.$ext"

    fun fileFor(relPath: String): File = File(root, relPath)

    fun partFor(relPath: String): File = File(root, "$relPath.part")

    /** The chapter file kept beside a copy (P10): `12.mkv.chapters.txt`. */
    fun sidecarFor(relPath: String): File = File(root, "$relPath.chapters.txt")

    /** Every local chapter file, for Settings › Chapters › Clear. */
    fun sidecars(): List<File> = root.listFiles()?.filter { it.isFile && it.name.endsWith(".chapters.txt") }.orEmpty()

    /** Bytes free on the volume that holds the directory. */
    fun freeBytes(): Long {
        root.mkdirs()
        return StatFs(root.path).availableBytes
    }

    /** Bytes the volume holds in total, for "7.8 of 32 GB". */
    fun totalBytes(): Long {
        root.mkdirs()
        return StatFs(root.path).totalBytes
    }

    fun usedBytes(): Long = root.walkBottomUp().filter { it.isFile }.sumOf { it.length() }

    fun delete(relPath: String) {
        fileFor(relPath).delete()
        partFor(relPath).delete()
        sidecarFor(relPath).delete()
    }
}
