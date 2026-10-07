package com.regolith.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.regolith.domain.artwork.ArtworkKind
import com.regolith.domain.artwork.ArtworkOwner
import com.regolith.domain.artwork.ArtworkRequest
import com.regolith.ui.components.MediaTile
import com.regolith.ui.components.PosterFlightLayout
import com.regolith.ui.components.rememberFlightLanding
import com.regolith.ui.theme.RegolithTheme
import org.junit.Rule
import org.junit.Test

/**
 * The flight made by hand (PosterFlight.kt), the one into a page beside the
 * wall on a wide window: a tile tapped, a page opening beside it with the same
 * picture, and the picture flying from one to the other over everything, then
 * gone once it has landed and the page shows its own. A page that opens with
 * no tap behind it has nothing flying in, and shows its picture at once.
 *
 * The clock is held still so the flight can be caught in the air.
 */
class PosterFlightTest {

    @get:Rule
    val rule = createComposeRule()

    private val heat = ArtworkOwner.File(30)
    private var open by mutableStateOf(false)

    private fun wallWithPage() {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            RegolithTheme {
                PosterFlightLayout {
                    Row {
                        Box(Modifier.width(120.dp)) {
                            MediaTile(
                                artwork = ArtworkRequest(heat, ArtworkKind.POSTER),
                                title = "Heat (1995)",
                                onClick = { open = true },
                                testTag = "tile",
                                flight = heat,
                            )
                        }
                        if (open) {
                            val landing = rememberFlightLanding(
                                heat,
                                picture = ArtworkRequest(heat, ArtworkKind.BACKDROP),
                                placeholder = ArtworkRequest(heat, ArtworkKind.THUMB),
                                enabled = true,
                            )
                            Box(Modifier.size(300.dp, 200.dp).then(landing.modifier).testTag("hero"))
                        }
                    }
                }
            }
        }
    }

    @Test
    fun aTappedTileFliesIntoThePageBesideIt() {
        wallWithPage()
        rule.onNodeWithTag("tile").performClick()
        rule.mainClock.advanceTimeBy(150)
        rule.onNodeWithTag("poster_flight").assertExists()
        // 380ms in the air and 120ms handing over to the page's own picture.
        rule.mainClock.advanceTimeBy(1_000)
        rule.onNodeWithTag("poster_flight").assertDoesNotExist()
        rule.onNodeWithTag("hero").assertExists()
    }

    @Test
    fun aPageOpenedWithoutATapHasNothingFlyingIn() {
        wallWithPage()
        open = true
        rule.mainClock.advanceTimeBy(150)
        rule.onNodeWithTag("hero").assertExists()
        rule.onNodeWithTag("poster_flight").assertDoesNotExist()
    }
}
