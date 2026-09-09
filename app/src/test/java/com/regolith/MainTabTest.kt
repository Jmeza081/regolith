package com.regolith

import com.regolith.ui.navigation.MainTab
import com.regolith.ui.navigation.RegolithKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MainTabTest {
    @Test
    fun `tab keys map back to their tab`() {
        MainTab.entries.forEach { tab -> assertEquals(tab, MainTab.forKey(tab.key)) }
    }

    @Test
    fun `browse at any folder depth is still the browse tab`() {
        assertEquals(MainTab.BROWSE, MainTab.forKey(RegolithKey.Browse(folderId = 42)))
    }

    @Test
    fun `pushed screens hide the pill`() {
        assertNull(MainTab.forKey(RegolithKey.Player(fileId = 1)))
        assertNull(MainTab.forKey(RegolithKey.TitleDetail(fileId = 1)))
        assertNull(MainTab.forKey(RegolithKey.AddServer.Manual()))
        assertNull(MainTab.forKey(null))
    }
}
