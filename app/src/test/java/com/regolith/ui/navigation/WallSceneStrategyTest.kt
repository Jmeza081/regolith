package com.regolith.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.scene.Scene
import androidx.navigation3.scene.SceneStrategyScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [WallSceneStrategy]: which keys a wide window lays out as "the wall" or
 * "the wall with a page beside it", and which it leaves to the default
 * full-screen scene. The animation itself is NavDisplay's; what these pin
 * down is the decision, and the keys that make NavDisplay animate (or not).
 */
class WallSceneStrategyTest {

    private fun entry(key: RegolithKey, metadata: Map<String, Any> = emptyMap()) =
        NavEntry<NavKey>(key, metadata = metadata) { }

    private val home = entry(RegolithKey.Home, tabScreen)
    private val search = entry(RegolithKey.Search())
    private val library = entry(RegolithKey.Library(), tabScreen + WallSceneStrategy.wall())
    private val films = entry(RegolithKey.Library(folderId = 2), tabScreen + WallSceneStrategy.wall())
    private val browse = entry(RegolithKey.Browse(folderId = 7), tabScreen + WallSceneStrategy.wall())
    private fun page(fileId: Long) = entry(RegolithKey.TitleDetail(fileId), WallSceneStrategy.page())
    private val player = entry(RegolithKey.Player(fileId = 4))

    private fun scene(entries: List<NavEntry<NavKey>>, wide: Boolean = true): Scene<NavKey>? =
        with(WallSceneStrategy<NavKey>(wide)) { SceneStrategyScope<NavKey>().calculateScene(entries) }

    @Test
    fun `a phone is never handled, wall or page`() {
        assertNull(scene(listOf(home, library), wide = false))
        assertNull(scene(listOf(home, library, page(1)), wide = false))
    }

    @Test
    fun `a wall on top has the window to itself`() {
        val s = scene(listOf(home, library, films))!!
        assertEquals(listOf(films), s.entries)
        assertEquals(listOf(home, library), s.previousEntries)
    }

    @Test
    fun `a page on a wall shares the window with it, and back closes only the page`() {
        val s = scene(listOf(home, library, films, page(4)))!!
        assertEquals(listOf(films, page(4)), s.entries)
        assertEquals("back pops the page and nothing else", listOf(home, library, films), s.previousEntries)
    }

    @Test
    fun `browse is a wall too`() {
        val s = scene(listOf(home, browse, page(9)))!!
        assertEquals(listOf(browse, page(9)), s.entries)
    }

    @Test
    fun `a page with no wall under it is left to fill the window`() {
        assertNull(scene(listOf(home, page(1))))
        assertNull(scene(listOf(home, library, search, page(1))))
    }

    @Test
    fun `anything else is left alone`() {
        assertNull(scene(listOf(home)))
        assertNull(scene(listOf(home, library, page(1), player)))
    }

    @Test
    fun `opening and closing a page changes the scene key, so NavDisplay animates it`() {
        val alone = scene(listOf(home, films))!!
        val withPage = scene(listOf(home, films, page(4)))!!
        assertNotEquals(alone.key, withPage.key)
    }

    @Test
    fun `swapping one page for another keeps the key, so the swap does not slide`() {
        val first = scene(listOf(home, films, page(4)))!!
        val second = scene(listOf(home, films, page(5)))!!
        assertEquals(first.key, second.key)
    }

    @Test
    fun `two different walls are two different scenes`() {
        assertNotEquals(scene(listOf(home, library))!!.key, scene(listOf(home, library, films))!!.key)
    }

    @Test
    fun `alone a wall keeps its own transitions, and with a page the scene stands still`() {
        assertEquals(films.metadata, scene(listOf(home, films))!!.metadata)
        assertSame(paneScene, scene(listOf(home, films, page(4)))!!.metadata)
    }

    @Test
    fun `a page opening or closing beside its wall stays on the same wall`() {
        val alone = scene(listOf(home, films))!!
        val withPage = scene(listOf(home, films, page(4)))!!
        assertTrue(withPage.isSameWallAs(alone))
        assertTrue(alone.isSameWallAs(withPage))
    }

    @Test
    fun `the wall's back arrow lands on another wall, or off the walls, so the scene cross-fades`() {
        val withPage = scene(listOf(home, library, films, page(4)))!!
        assertFalse("Films with a page, back to Library", withPage.isSameWallAs(scene(listOf(home, library))!!))
        assertFalse("Films with a page, back to Home", withPage.isSameWallAs(NotAWall))
        assertFalse(NotAWall.isSameWallAs(withPage))
    }

    /** Stands in for the default full-screen scene Home is drawn in. */
    private object NotAWall : Scene<NavKey> {
        override val key: Any = "home"
        override val entries: List<NavEntry<NavKey>> = emptyList()
        override val previousEntries: List<NavEntry<NavKey>> = emptyList()
        override val content: @Composable () -> Unit = {}
    }
}
