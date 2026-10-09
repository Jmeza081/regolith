package com.regolith.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.regolith.R
import com.regolith.ui.components.TopBar
import com.regolith.ui.components.TopBarAction
import com.regolith.ui.theme.RegolithTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * The top bar in the wall beside a title's page on the inner display,
 * about 256dp across: its three actions fold into More rather than leave
 * the title two letters ("HO"), and More's sheet still does each of them.
 * At a phone's width nothing changes.
 */
class NarrowTopBarTest {

    @get:Rule
    val compose = createComposeRule()

    private val done = mutableListOf<String>()
    private val tags = listOf("bar_search", "bar_sort", "bar_view")

    private fun bar(width: Dp) = compose.setContent {
        RegolithTheme {
            Box(Modifier.width(width)) {
                TopBar(
                    title = "Home videos",
                    onBack = {},
                    statusBarPadding = false,
                    actions = listOf(
                        TopBarAction(R.drawable.rg_ic_search_alt, "Search", "bar_search") { done += "search" },
                        TopBarAction(R.drawable.rg_ic_sort, "Sort", "bar_sort") { done += "sort" },
                        TopBarAction(R.drawable.rg_ic_view_rows, "Show as rows", "bar_view") { done += "view" },
                    ),
                )
            }
        }
    }

    @Test
    fun aPhoneWideBarShowsEveryAction() {
        bar(411.dp)
        tags.forEach { compose.onNodeWithTag(it).assertIsDisplayed() }
        compose.onAllNodesWithTag("topbar_more_button").assertCountEquals(0)
    }

    @Test
    fun besideATitlesPageTheActionsFoldIntoMore() {
        bar(256.dp)
        compose.onNodeWithTag("topbar_more_button").assertIsDisplayed()
        tags.forEach { compose.onAllNodesWithTag(it).assertCountEquals(0) }
    }

    @Test
    fun moreListsTheFoldedActionsAndEachStillWorks() {
        bar(256.dp)
        compose.onNodeWithTag("topbar_more_button").performClick()
        compose.onNodeWithTag("topbar_more_sheet").assertIsDisplayed()
        tags.forEach { compose.onNodeWithTag(it).assertIsDisplayed() }
        compose.onNodeWithTag("bar_sort").performClick()
        compose.waitForIdle()
        assertEquals(listOf("sort"), done)
        // The sheet closes behind the action.
        compose.onAllNodesWithTag("topbar_more_sheet").assertCountEquals(0)
    }
}
