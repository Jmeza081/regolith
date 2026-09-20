package com.regolith.ui.adaptive

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.window.layout.FoldingFeature.Orientation
import androidx.window.layout.FoldingFeature.State
import androidx.window.testing.layout.FoldingFeature
import androidx.window.testing.layout.TestWindowLayoutInfo
import androidx.window.testing.layout.WindowLayoutInfoPublisherRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule

/**
 * [rememberWindowShape] under every fold posture the player cares about.
 *
 * The player's flex mode — picture above the hinge, controls below — is
 * gated on exactly one line in `PlayerScreen`:
 *
 * ```
 * val flex = windowShape.posture == FoldPosture.TABLE_TOP && hinge != null
 * ```
 *
 * so what's worth testing is that [rememberWindowShape] turns a real
 * `FoldingFeature` into the right [FoldPosture]. Getting it wrong in either
 * direction is silent: flex mode simply never appears, or it appears on a
 * device held like a book and splits the picture down the spine.
 *
 * These run without a hinge. [WindowLayoutInfoPublisherRule] swaps out the
 * `WindowInfoTracker` that Material 3 adaptive reads, so the test publishes
 * the fold itself — the web analogy is stubbing `matchMedia` rather than
 * resizing the browser. That means they pass on any emulator or phone,
 * including CI, and they cover postures a device can only be put into by
 * physically half-closing it.
 *
 * The emulator is still worth using for the real thing: the
 * `Samsung_Galaxy_Main_Display` AVD declares a hinge, so
 * `adb shell cmd device_state state 2` half-opens it for a look by eye.
 * See `docs/ARCHITECTURE.md`.
 */
class WindowShapeFoldTest {

    private val compose = createAndroidComposeRule<ComponentActivity>()
    private val publisher = WindowLayoutInfoPublisherRule()

    // The publisher has to be installed before the Activity starts reading
    // window info, so it goes outside the Compose rule rather than beside it.
    @get:Rule
    val rules: TestRule = RuleChain.outerRule(publisher).around(compose)

    /** Composes [rememberWindowShape] and hands back whatever it last reported. */
    private fun shapeUnderFold(publish: () -> Unit): WindowShape {
        var shape by mutableStateOf(WindowShape.Phone)
        compose.setContent { shape = rememberWindowShape() }
        compose.runOnIdle { publish() }
        compose.waitForIdle()
        return shape
    }

    // ── The posture that turns flex mode on ────────────────────────────

    @Test
    fun halfOpenWithTheHingeAcrossIsTableTop() {
        val shape = shapeUnderFold {
            publisher.overrideWindowLayoutInfo(
                TestWindowLayoutInfo(
                    listOf(
                        FoldingFeature(
                            activity = compose.activity,
                            state = State.HALF_OPENED,
                            orientation = Orientation.HORIZONTAL,
                        ),
                    ),
                ),
            )
        }

        assertEquals(FoldPosture.TABLE_TOP, shape.posture)
        // PlayerScreen splits at `hinge.top`, so a null here silently
        // disables flex mode even with the posture right.
        assertNotNull("tabletop must carry a hinge rect", shape.hinge)
    }

    @Test
    fun theHingeRectIsTheOneTheDeviceReported() {
        val shape = shapeUnderFold {
            publisher.overrideWindowLayoutInfo(
                TestWindowLayoutInfo(
                    listOf(
                        FoldingFeature(
                            activity = compose.activity,
                            center = 600,
                            size = 0,
                            state = State.HALF_OPENED,
                            orientation = Orientation.HORIZONTAL,
                        ),
                    ),
                ),
            )
        }

        // The split is the hinge's own position, not half the window: the two
        // halves of a fold are not equal, and the player relies on that.
        assertEquals(600f, shape.hinge?.top)
    }

    // ── The postures that leave it off ─────────────────────────────────

    @Test
    fun halfOpenHeldLikeABookIsNotFlex() {
        val shape = shapeUnderFold {
            publisher.overrideWindowLayoutInfo(
                TestWindowLayoutInfo(
                    listOf(
                        FoldingFeature(
                            activity = compose.activity,
                            state = State.HALF_OPENED,
                            orientation = Orientation.VERTICAL,
                        ),
                    ),
                ),
            )
        }

        // A vertical hinge means the fold runs down the middle of the picture.
        // Splitting there would cut the frame in half, so BOOK lays out flat.
        assertEquals(FoldPosture.BOOK, shape.posture)
    }

    @Test
    fun aFullyOpenFoldIsFlat() {
        val shape = shapeUnderFold {
            publisher.overrideWindowLayoutInfo(
                TestWindowLayoutInfo(
                    listOf(
                        FoldingFeature(
                            activity = compose.activity,
                            state = State.FLAT,
                            orientation = Orientation.HORIZONTAL,
                        ),
                    ),
                ),
            )
        }

        assertEquals(FoldPosture.FLAT, shape.posture)
        // A flat fold is not separating, so there is nothing to split at.
        assertNull(shape.hinge)
    }

    @Test
    fun aDeviceWithNoHingeIsFlat() {
        val shape = shapeUnderFold {
            publisher.overrideWindowLayoutInfo(TestWindowLayoutInfo(emptyList()))
        }

        assertEquals(FoldPosture.FLAT, shape.posture)
        assertNull(shape.hinge)
    }
}
