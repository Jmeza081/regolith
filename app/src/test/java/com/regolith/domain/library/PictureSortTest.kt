package com.regolith.domain.library

import org.junit.Assert.assertEquals
import org.junit.Test

/** How an album's pictures are ordered: the same order on its tab, in the lightbox and in a story. */
class PictureSortTest {

    private data class P(
        override val name: String,
        override val takenAtMs: Long? = null,
        override val modifiedAtMs: Long = 0,
        override val addedAtMs: Long? = null,
    ) : PictureKeys

    private fun List<P>.names(order: PictureOrder) = sortedWith(order.comparator()).map { it.name }

    @Test
    fun `newest taken first, a picture with no date standing in with its file's`() {
        val pictures = listOf(
            P("old.jpg", takenAtMs = 100),
            P("screenshot.png", takenAtMs = null, modifiedAtMs = 250),
            P("new.jpg", takenAtMs = 300),
        )
        assertEquals(listOf("new.jpg", "screenshot.png", "old.jpg"), pictures.names(PictureOrder()))
    }

    @Test
    fun `reversed, the oldest comes first`() {
        val pictures = listOf(P("b.jpg", takenAtMs = 2), P("a.jpg", takenAtMs = 1))
        assertEquals(listOf("a.jpg", "b.jpg"), pictures.names(PictureOrder().pick(PictureSort.DATE_TAKEN)))
    }

    @Test
    fun `pictures taken together are in name order`() {
        val pictures = listOf(P("IMG_2.jpg", takenAtMs = 5), P("img_1.jpg", takenAtMs = 5))
        // Newest first runs the whole order backwards, names included: a reversed list reads as one.
        assertEquals(listOf("IMG_2.jpg", "img_1.jpg"), pictures.names(PictureOrder()))
        assertEquals(listOf("img_1.jpg", "IMG_2.jpg"), pictures.names(PictureOrder(PictureSort.DATE_TAKEN, SortDirection.ASCENDING)))
    }

    @Test
    fun `by name, A to Z whatever the case`() {
        val pictures = listOf(P("beach.jpg"), P("Attic.jpg"), P("cove.jpg"))
        assertEquals(listOf("Attic.jpg", "beach.jpg", "cove.jpg"), pictures.names(PictureOrder(PictureSort.NAME)))
    }

    @Test
    fun `date added is when a scan found it, or its file's date from before that was kept`() {
        val pictures = listOf(
            P("found today.jpg", addedAtMs = 900, modifiedAtMs = 10),
            P("listed long ago.jpg", addedAtMs = null, modifiedAtMs = 500),
            P("found yesterday.jpg", addedAtMs = 800, modifiedAtMs = 20),
        )
        assertEquals(listOf("found today.jpg", "found yesterday.jpg", "listed long ago.jpg"), pictures.names(PictureOrder(PictureSort.DATE_ADDED)))
    }

    @Test
    fun `picking the sort in use reverses it, a new one starts its own way`() {
        assertEquals(SortDirection.ASCENDING, PictureOrder().pick(PictureSort.DATE_TAKEN).direction)
        assertEquals(PictureOrder(PictureSort.NAME, SortDirection.ASCENDING), PictureOrder().pick(PictureSort.NAME))
    }
}
