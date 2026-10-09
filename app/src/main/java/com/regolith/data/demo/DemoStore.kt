package com.regolith.data.demo

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Where the demo library's files live: its videos as `filesDir/demo/{fileId}.mp4`,
 * and its pictures as `filesDir/demo/pictures/{pictureId}.jpg` (P20).
 *
 * Separate from the downloads directory on purpose. A demo file is not a
 * download — it should not appear on "On this device", count against the
 * storage figure, or be deletable from there. Keeping it in its own
 * directory means the demo library is removed by deleting one folder, and
 * that a stray demo file can never be mistaken for a copy of something on
 * a real share.
 *
 * The lookup is by file id and nothing else, which is what lets the player
 * and the artwork pipeline ask "is there a local copy of this?" without
 * knowing demo mode exists.
 */
@Singleton
class DemoStore @Inject constructor(@ApplicationContext context: Context) {
    val root: File = File(context.filesDir, "demo")

    /** The file for [fileId], or null when this is not a demo file. */
    fun fileFor(fileId: Long): File? = File(root, "$fileId.mp4").takeIf { it.isFile }

    fun createFor(fileId: Long): File {
        root.mkdirs()
        return File(root, "$fileId.mp4")
    }

    private val pictures: File = File(root, "pictures")

    /**
     * The picture for [pictureId], a `share_files` row, or null when this is
     * not a demo picture: what the artwork pipeline, the lightbox and a story
     * read in place of the share, which the demo does not have.
     */
    fun pictureFor(pictureId: Long): File? = File(pictures, "$pictureId.jpg").takeIf { it.isFile }

    fun createPicture(pictureId: Long): File {
        pictures.mkdirs()
        return File(pictures, "$pictureId.jpg")
    }

    fun usedBytes(): Long = if (root.isDirectory) root.walkBottomUp().filter { it.isFile }.sumOf { it.length() } else 0L

    fun deleteAll() {
        root.deleteRecursively()
    }
}
