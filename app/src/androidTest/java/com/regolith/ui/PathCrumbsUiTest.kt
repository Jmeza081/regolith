package com.regolith.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.regolith.ui.components.PathCrumb
import com.regolith.ui.components.PathCrumbs
import com.regolith.ui.theme.RegolithTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** A path whose steps open their folders: each name is its own target, and a step with no folder is not one. */
class PathCrumbsUiTest {

    @get:Rule
    val compose = createComposeRule()

    private val tapped = mutableListOf<Pair<Int, PathCrumb>>()

    private fun show(crumbs: List<PathCrumb>, leadingSlash: Boolean = true) = compose.setContent {
        RegolithTheme {
            PathCrumbs(crumbs = crumbs, onOpen = { i, crumb -> tapped += i to crumb }, testTag = "path", leadingSlash = leadingSlash)
        }
    }

    @Test
    fun eachNameOpensItsOwnFolder() {
        val crumbs = listOf(PathCrumb("Regolith", 1), PathCrumb("Full-Length Films", 2), PathCrumb("De Lora", 3))
        show(crumbs)
        compose.onNodeWithTag("path_1").assertIsDisplayed().performClick()
        compose.onNodeWithTag("path_2").performClick()
        assertEquals(listOf(1 to crumbs[1], 2 to crumbs[2]), tapped)
    }

    @Test
    fun aStepWithNoFolderIsTextOnly() {
        show(listOf(PathCrumb("DCIM", null), PathCrumb("Camera", 7)), leadingSlash = false)
        compose.onNodeWithText("DCIM").assertIsDisplayed()
        compose.onNodeWithTag("path_0").assertDoesNotExist()
        compose.onNodeWithText("DCIM").performClick()
        assertTrue("a step with no folder does nothing", tapped.isEmpty())
        compose.onNodeWithTag("path_1").performClick()
        assertEquals(listOf(1 to PathCrumb("Camera", 7)), tapped)
    }
}
