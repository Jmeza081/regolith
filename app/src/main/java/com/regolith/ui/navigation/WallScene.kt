package com.regolith.ui.navigation

import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.scene.Scene
import androidx.navigation3.scene.SceneStrategy
import androidx.navigation3.scene.SceneStrategyScope
import androidx.navigation3.ui.LocalNavAnimatedContentScope
import com.regolith.ui.theme.RegolithTheme
import com.regolith.ui.theme.Spacing

/**
 * How a wall (Library or Browse) uses a wide window — a foldable's inner
 * display, a tablet. On a compact window it does nothing, and every key is
 * the plain full-screen push it has always been.
 *
 * - **Nothing open:** the wall has the whole window beside the rail.
 * - **A title open:** its page (Title Detail) slides in from the end edge and
 *   takes half the window; the wall fades through into the other half.
 * - **Closed again** (its ✕, back, or the ringed tile tapped a second time):
 *   the page slides back out and the wall fades through to the full width.
 *
 * Same back stack as a phone either way — `[…, Library, TitleDetail]` — so
 * this decides only how the top keys are laid out (guardrail G10).
 *
 * "The wall" and "the wall with a page beside it" are two DIFFERENT scenes on
 * purpose. NavDisplay then animates between them with its own machinery,
 * which is what gives three things for free: a popped Title Detail stays on
 * screen for its slide-out, predictive back drags it out under your thumb,
 * and the wall's composition — scroll position, loaded posters, which tab it
 * is on — moves between the two layouts instead of being rebuilt, because
 * Navigation 3 wraps every entry in `movableContentOf`.
 *
 * Web analogy: a route whose layout changes with the URL. `/films` renders
 * `<Wall/>`, `/films/42` renders `<Split><Wall/><Page/></Split>`, and the
 * router's transition group slides the page in.
 *
 * Replaces Material's `ListDetailSceneStrategy` (F2), which kept a "Choose a
 * title" placeholder beside the wall until something was picked, re-measured
 * the wall on every frame of a pane animation, dropped a popped page's content
 * the instant it left the stack, and — its default back behaviour — popped the
 * wall along with the page, so back from an open title went all the way Home.
 */
class WallSceneStrategy<T : Any>(private val wide: Boolean) : SceneStrategy<T> {

    /** Shared by every scene this strategy makes, so each layout can start a wall where the other left it. */
    private val widths = WallWidths()

    override fun SceneStrategyScope<T>.calculateScene(entries: List<NavEntry<T>>): Scene<T>? {
        if (!wide) return null
        val top = entries.last()
        val below = entries.getOrNull(entries.lastIndex - 1)
        return when {
            top.role == Role.WALL -> WallScene(wall = top, page = null, previousEntries = entries.dropLast(1), widths = widths)
            // A page with no wall under it (opened from Home or Search) is not
            // ours: it falls through to the default scene and fills the window.
            top.role == Role.PAGE && below?.role == Role.WALL ->
                WallScene(wall = below, page = top, previousEntries = entries.dropLast(1), widths = widths)
            else -> null
        }
    }

    companion object {
        /** Metadata for a wall's entry: Library and Browse, at any depth. */
        fun wall(): Map<String, Any> = mapOf(ROLE_KEY to Role.WALL)

        /** Metadata for the page that opens beside a wall: Title Detail. */
        fun page(): Map<String, Any> = mapOf(ROLE_KEY to Role.PAGE)
    }
}

/**
 * One wall, alone or with a page beside it. The two cases have different
 * [key]s, which is what makes opening and closing a page a scene change that
 * NavDisplay animates (see [WallSceneStrategy]). Swapping one page for another
 * keeps the key, so the page beside the wall changes without a transition.
 */
internal class WallScene<T : Any>(
    val wall: NavEntry<T>,
    val page: NavEntry<T>?,
    override val previousEntries: List<NavEntry<T>>,
    private val widths: WallWidths = WallWidths(),
) : Scene<T> {

    override val key: Any = if (page == null) WallAlone(wall.contentKey) else WallWithPage(wall.contentKey)

    override val entries: List<NavEntry<T>> = listOfNotNull(wall, page)

    /**
     * Alone, the wall's own transitions apply (a tab cross-fades in, as on a
     * phone). With a page, the scene itself does not move at all and the
     * page animates on its own ([paneScene]), so the wall never slides.
     */
    override val metadata: Map<String, Any> = if (page == null) wall.metadata else paneScene

    override val content: @Composable () -> Unit = {
        if (page == null) {
            WallAloneLayout(wall, widths)
        } else {
            WallWithPageLayout(wall, page, widths)
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is WallScene<*>) return false
        return wall == other.wall && page == other.page && previousEntries == other.previousEntries
    }

    override fun hashCode(): Int = (wall.hashCode() * 31 + page.hashCode()) * 31 + previousEntries.hashCode()

    override fun toString(): String = "WallScene(wall=${wall.contentKey}, page=${page?.contentKey})"
}

/**
 * The wall with the window to itself. Arriving from its own split — the page
 * beside it closing — it starts as wide as it was beside the page ([WallAt]).
 */
@Composable
private fun <T : Any> WallAloneLayout(wall: NavEntry<T>, widths: WallWidths) {
    val key = wall.contentKey
    val from = remember { widths.beside[key] }
    DisposableEffect(key) { onDispose { widths.alone.remove(key) } }
    WallAt(wall, from, Modifier.fillMaxSize()) { widths.alone[key] = it }
}

/**
 * The split: the wall in the start half (the rail's inset is inside it, as it
 * is inside every tab screen), the page in the end half. Even, and fixed (F13).
 *
 * The page slides; the wall stays where it is and fades through from one
 * width to the other ([WallAt]). During a pop the wall is drawn by the
 * full-width scene underneath, so this half is left empty and the page slides
 * out over the wall as it fades through to the full width.
 */
@Composable
private fun <T : Any> WallWithPageLayout(wall: NavEntry<T>, page: NavEntry<T>, widths: WallWidths) {
    val motion = LocalNavAnimatedContentScope.current
    val key = wall.contentKey
    val from = remember { widths.alone[key] }
    DisposableEffect(key) { onDispose { widths.beside.remove(key) } }
    Row(Modifier.fillMaxSize()) {
        WallAt(wall, from, Modifier.weight(1f).fillMaxHeight()) { widths.beside[key] = it }
        Spacer(Modifier.width(PANE_GAP))
        Box(
            with(motion) { Modifier.animateEnterExit(enter = paneEnter, exit = paneExit) }
                .weight(1f)
                .fillMaxHeight()
                // Title Detail draws on the window's ground below its hero; on
                // the way out that ground has to cover the wall beneath it.
                .background(RegolithTheme.colors.ground),
        ) {
            CompositionLocalProvider(LocalBesideWall provides true) { page.Content() }
        }
    }
}

/**
 * Lays [wall] out in the layout it is arriving in. Arriving from its other
 * layout — [from], the width it had there — it fades through: out at that
 * width, across to the new one while it cannot be seen, and back in
 * ([wallFadeThroughAlpha]). Arriving from anywhere else ([from] null), it is
 * simply laid out. [report] hears the width it is given on every pass, for
 * the other layout to start from in turn.
 *
 * Before, the wall jumped to the new width on the first frame of the change,
 * under the moving page: every tile in a new place at once (the tearing and
 * overlap the owner saw closing a page), and that frame — composing and
 * drawing the whole wide wall at once — missed its deadline. Now the first
 * frames look exactly like the last ones, and the re-arranging happens while
 * the wall is out of sight.
 *
 * The fade runs on the scene change's own transition, so predictive back
 * scrubs it with the thumb, and a cancelled gesture lands back on the old
 * width at full alpha, which is exactly where the wall goes back to.
 *
 * While a page opens, the wall can be wider than this box until it changes
 * width: it overflows under the page, which is drawn on top of it.
 */
@Composable
private fun <T : Any> WallAt(wall: NavEntry<T>, from: Int?, modifier: Modifier, report: (Int) -> Unit) {
    val through = if (from == null) {
        null
    } else {
        LocalNavAnimatedContentScope.current.transition.animateFloat(
            transitionSpec = { tween(WALL_FADE_THROUGH_MS, easing = LinearEasing) },
            label = "wall fade through",
        ) { state -> if (state == EnterExitState.PreEnter) 0f else 1f }
    }
    Box(
        modifier
            .layout { measurable, constraints ->
                report(constraints.maxWidth)
                val switched = through == null || through.value >= WALL_FADE_THROUGH_SWITCH
                val width = if (switched || from == null) constraints.maxWidth else from
                val placeable = measurable.measure(constraints.copy(minWidth = width, maxWidth = width))
                layout(constraints.maxWidth, placeable.height) { placeable.place(0, 0) }
            }
            // Inside the width change, so the layer is the wall's own size. A
            // see-through layer is drawn off screen at the size of its box, and
            // outside the box it would cut a wall that is still the old,
            // wider width off at the new one, leaving a gap before the page.
            .then(if (through == null) Modifier else Modifier.graphicsLayer { alpha = wallFadeThroughAlpha(through.value) }),
    ) {
        wall.Content()
    }
}

/**
 * The width, in pixels, each wall last had alone and beside a page, kept for
 * as long as that layout is composed. Plain maps on purpose: they are written
 * while a layout is measured and read when the other layout first composes,
 * a frame later, and nothing should recompose because they changed.
 */
internal class WallWidths {
    val alone = HashMap<Any, Int>()
    val beside = HashMap<Any, Int>()
}

/**
 * True for a page drawn beside a wall by [WallScene] — Title Detail reads it
 * to wear a close ✕ instead of a back arrow.
 *
 * Asked of the layout rather than worked out from the back stack, because
 * the two disagree for exactly the moment that matters: a closed page is
 * already off the stack while it slides out, and a page that read the stack
 * would turn its ✕ into a back arrow halfway out of the door.
 */
val LocalBesideWall = staticCompositionLocalOf { false }

/** Between the wall's own end gutter and the page's art. */
private val PANE_GAP: Dp = Spacing.s18

private const val ROLE_KEY = "com.regolith.ui.navigation.WallSceneStrategy.role"

private enum class Role { WALL, PAGE }

private val NavEntry<*>.role: Role? get() = metadata[ROLE_KEY] as? Role

private data class WallAlone(val wallKey: Any)

private data class WallWithPage(val wallKey: Any)
