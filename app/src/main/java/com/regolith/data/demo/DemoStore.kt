package com.regolith.data.demo

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Where the demo library's video files live: `filesDir/demo/{fileId}.mp4`.
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

    fun usedBytes(): Long = if (root.isDirectory) root.walkBottomUp().filter { it.isFile }.sumOf { it.length() } else 0L

    fun deleteAll() {
        root.deleteRecursively()
    }
}
