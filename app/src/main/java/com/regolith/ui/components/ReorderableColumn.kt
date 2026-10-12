package com.regolith.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.zIndex
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * A short list put in order by dragging its rows, each by a handle: Home's
 * Arrange sheet. The row under the finger follows it, drawn over the rest,
 * and the others slide out of its way as it passes their middles, with a
 * tick for each. Let go, and it settles into its place and [onReorder] gets
 * the new order: once per drag, and only if it changed. Talkback offers
 * Move up and Move down on every row, which do the same a step at a time.
 *
 * [row] draws one item. It puts `handle` on what the finger grabs, and can
 * lift itself while `dragging`. The rows sit flush, with no gap between
 * them, and keep their own padding: the slots are measured from the rows.
 *
 * Not lazy, and it never scrolls itself: it is for a handful of rows that all
 * fit on screen. A long list would want a lazy list that scrolls as a row is
 * dragged to its edge.
 *
 * Web analogy: a sortable list (SortableJS) over a few `<li>`s.
 */
@Composable
fun <T : Any> ReorderableColumn(
    items: List<T>,
    key: (T) -> Any,
    /** What Talkback calls an item in its Move up and Move down actions. */
    label: (T) -> String,
    onReorder: (List<T>) -> Unit,
    modifier: Modifier = Modifier,
    row: @Composable (item: T, handle: Modifier, dragging: Boolean) -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val reorder by rememberUpdatedState(onReorder)
    // The order on screen. A drag moves things in it at once; [onReorder]
    // hears of it on release, and the order then comes back as [items].
    var order by remember { mutableStateOf(items) }
    LaunchedEffect(items) { order = items }
    // The row being dragged, by its key, and how far it is from its slot:
    // read only where the row is drawn, so a drag recomposes nothing.
    var dragging by remember { mutableStateOf<Any?>(null) }
    var dragY by remember { mutableFloatStateOf(0f) }
    var before by remember { mutableStateOf(items) }
    var settling by remember { mutableStateOf<Job?>(null) }
    val heights = remember { HashMap<Any, Int>() }
    val slides = remember { HashMap<Any, Animatable<Float, AnimationVector1D>>() }

    // A row the dragged one has just passed: drawn where it was, [from] px
    // off its new slot, then slid home. Undispatched, so the jump back is in
    // place before the frame that lays it out in its new slot is drawn.
    fun slide(k: Any, from: Float) {
        val anim = slides.getOrPut(k) { Animatable(0f) }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            anim.snapTo(from)
            anim.animateTo(0f, spring(stiffness = Spring.StiffnessMediumLow))
        }
    }

    fun startDrag(k: Any) {
        settling?.cancel()
        dragging = k
        dragY = 0f
        before = order
        haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
    }

    // The dragged row follows the finger. Past the middle of the row beside
    // it the two change places: the dragged row takes the new slot, carrying
    // on from where the finger has it, and the other slides into the old one.
    fun dragBy(dy: Float) {
        val k = dragging ?: return
        dragY += dy
        while (true) {
            val at = order.indexOfFirst { key(it) == k }
            val to = when {
                at < 0 -> return
                dragY > 0 && at < order.lastIndex -> at + 1
                dragY < 0 && at > 0 -> at - 1
                else -> return
            }
            val other = key(order[to])
            val otherHeight = heights[other] ?: return
            val ownHeight = heights[k] ?: return
            if (kotlin.math.abs(dragY) <= otherHeight / 2f) return
            order = order.toMutableList().apply { add(to, removeAt(at)) }
            val down = to > at
            dragY += if (down) -otherHeight.toFloat() else otherHeight.toFloat()
            slide(other, if (down) ownHeight.toFloat() else -ownHeight.toFloat())
            haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
        }
    }

    fun endDrag() {
        if (dragging == null) return
        if (order != before) reorder(order)
        settling = scope.launch {
            animate(dragY, 0f, animationSpec = spring(stiffness = Spring.StiffnessMediumLow)) { y, _ -> dragY = y }
            dragging = null
        }
    }

    // Talkback's Move up and Move down: one step, kept at once.
    fun step(k: Any, by: Int) {
        val at = order.indexOfFirst { key(it) == k }
        val to = at + by
        if (at < 0 || to !in order.indices) return
        order = order.toMutableList().apply { add(to, removeAt(at)) }
        reorder(order)
    }

    Column(modifier) {
        order.forEachIndexed { index, item ->
            val k = key(item)
            // Keyed, so each row keeps its own state as the order changes under it.
            key(k) {
                // Made once per row, and kept where a drag can reach it to slide the row.
                val slid = remember { slides.getOrPut(k) { Animatable(0f) } }
                val name = label(item)
                Box(
                    Modifier
                        .zIndex(if (dragging == k) 1f else 0f)
                        .onSizeChanged { heights[k] = it.height }
                        .graphicsLayer { translationY = if (dragging == k) dragY else slid.value }
                        .semantics {
                            customActions = listOfNotNull(
                                CustomAccessibilityAction("Move $name up") { step(k, -1); true }.takeIf { index > 0 },
                                CustomAccessibilityAction("Move $name down") { step(k, 1); true }.takeIf { index < order.lastIndex },
                            )
                        },
                ) {
                    row(
                        item,
                        Modifier.pointerInput(k) {
                            detectVerticalDragGestures(
                                onDragStart = { startDrag(k) },
                                onDragEnd = { endDrag() },
                                onDragCancel = { endDrag() },
                            ) { change, dy ->
                                change.consume()
                                dragBy(dy)
                            }
                        },
                        dragging == k,
                    )
                }
            }
        }
    }
}
