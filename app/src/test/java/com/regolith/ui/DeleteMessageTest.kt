package com.regolith.ui

import com.regolith.ui.util.FileOpMessages
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What the delete dialog says when only videos are going, in Browse and on
 * a video's own page: the files that go with them are named, because
 * nothing on screen shows them going.
 */
class DeleteMessageTest {

    @Test
    fun `one video on its own reads as it always did`() {
        assertEquals(
            "beach.mp4 leaves the share for good — 1.2 GB. This can't be undone, and the chapters you wrote and where you left off go with it.",
            FileOpMessages.forDeletingVideos(listOf("beach.mp4"), "1.2 GB", companions = 0),
        )
    }

    @Test
    fun `one video says the files that share its name go too`() {
        assertEquals(
            "beach.mp4 and the 3 files that share its name leave the share for good — 1.2 GB. This can't be undone, and the chapters you wrote and where you left off go with it.",
            FileOpMessages.forDeletingVideos(listOf("beach.mp4"), "1.2 GB", companions = 3),
        )
        assertEquals(
            "beach.mp4 and the file that shares its name leave the share for good — 1.2 GB. This can't be undone, and the chapters you wrote and where you left off go with it.",
            FileOpMessages.forDeletingVideos(listOf("beach.mp4"), "1.2 GB", companions = 1),
        )
    }

    @Test
    fun `several videos are counted by size, with their files`() {
        assertEquals(
            "4.1 GB leaves the share for good, with the 5 files that share the videos' names. This can't be undone, and the chapters you wrote and where you left off go with them.",
            FileOpMessages.forDeletingVideos(listOf("beach.mp4", "sunset.mp4"), "4.1 GB", companions = 5),
        )
    }
}
