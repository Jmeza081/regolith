package com.regolith.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performCustomAccessibilityActionWithLabel
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.regolith.ui.components.ReorderableColumn
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * [ReorderableColumn], driven by real drags on its handles through Compose's
 * touch injection: a row dragged past the middle of the next one takes its
 * place, one dragged less than that stays put, and the new order is reported
 * once, on release. Talkback's Move up and Move down step a row at a time.
 */
class ReorderableColumnTest {

    @get:Rule
    val rule = createComposeRule()

    private var items by mutableStateOf(listOf("A", "B", "C", "D"))
    private val reports = mutableListOf<List<String>>()
    private var rowPx = 0f

    private fun list() {
        rule.setContent {
            ReorderableColumn(
                items = items,
                key = { it },
                label = { it },
                onReorder = { order ->
                    reports += order
                    items = order
                },
            ) { item, handle, _ ->
                Row(Modifier.fillMaxWidth().height(ROW).testTag("row_$item")) {
                    Text(item, Modifier.weight(1f))
                    Box(handle.size(ROW).testTag("handle_$item"))
                }
            }
        }
        rowPx = with(rule.density) { ROW.toPx() }
    }

    /** Drags [item]'s handle by [rows] rows' height, in small steps as a finger moves, and lets go. */
    private fun drag(item: String, rows: Float) {
        rule.onNodeWithTag("handle_$item").performTouchInput {
            down(center)
            val steps = 20
            repeat(steps) { moveBy(Offset(0f, rows * rowPx / steps)) }
            up()
        }
        rule.waitForIdle()
    }

    @Test
    fun `a row dragged past the middle of the next one takes its place`() {
        list()
        drag("A", 1.3f)
        assertEquals(listOf("B", "A", "C", "D"), items)
        assertEquals("reported once, on release", 1, reports.size)
    }

    @Test
    fun `a long drag up passes every row on the way`() {
        list()
        drag("D", -3.3f)
        assertEquals(listOf("D", "A", "B", "C"), items)
        assertEquals(1, reports.size)
    }

    @Test
    fun `a short drag puts the row back and reports nothing`() {
        list()
        drag("B", 0.3f)
        assertEquals(listOf("A", "B", "C", "D"), items)
        assertEquals(0, reports.size)
    }

    @Test
    fun `there and back again reports nothing`() {
        list()
        rule.onNodeWithTag("handle_A").performTouchInput {
            down(center)
            repeat(20) { moveBy(Offset(0f, 1.3f * rowPx / 20)) }
            repeat(20) { moveBy(Offset(0f, -1.3f * rowPx / 20)) }
            up()
        }
        rule.waitForIdle()
        assertEquals(listOf("A", "B", "C", "D"), items)
        assertEquals(0, reports.size)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `talkback moves a row one step`() {
        list()
        // The actions are on each row's slot, around the row itself.
        rule.onNode(hasAnyDescendant(hasTestTag("row_B")) and SemanticsMatcher.keyIsDefined(SemanticsActions.CustomActions))
            .performCustomAccessibilityActionWithLabel("Move B down")
        rule.waitForIdle()
        assertEquals(listOf("A", "C", "B", "D"), items)
        assertEquals(1, reports.size)
    }

    private companion object {
        val ROW = 56.dp
    }
}
