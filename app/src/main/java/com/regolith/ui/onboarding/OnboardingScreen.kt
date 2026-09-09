package com.regolith.ui.onboarding

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.regolith.R
import com.regolith.ui.components.DisplayText
import com.regolith.ui.components.Eyebrow
import com.regolith.ui.components.PrimaryButton
import com.regolith.ui.theme.PillShape
import com.regolith.ui.theme.RegolithTheme
import com.regolith.ui.theme.Spacing
import com.regolith.ui.theme.TextStyles
import kotlinx.coroutines.launch

private data class Page(val image: Int, val eyebrow: String, val title: String, val body: String)

private val pages = listOf(
    Page(R.drawable.rg_onboard_lunar, "Regolith", "Your own\nmedia", "Everything on your drive, read straight off the share — no uploads, no account, no catalogue deciding what you get to watch tonight."),
    Page(R.drawable.rg_onboard_one_address, "How it works", "One address,\nnothing leaves", "Point it at an SMB share on your network. Files are read where they sit and nothing is copied out — the phone talks to your machine and nobody else."),
    Page(R.drawable.rg_onboard_no_network, "Off the network", "Take files\nwith you", "Keep anything on the phone and it plays with the share unreachable — on a plane, on a train, anywhere the network is not."),
)

/**
 * Onboarding (design section 02): three screens, each a photograph in the
 * top 404dp under the design's four-stop gradient, an eyebrow, a two-line
 * Michroma 19 title and body copy at the bottom, three dots (the current
 * one an 18×6 red pill), and Skip beside Next. The last ends on "Find my
 * server", which lands on the finder.
 */
@Composable
fun OnboardingScreen(
    onFinish: () -> Unit,
    onFindServer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RegolithTheme.colors
    val pager = rememberPagerState { pages.size }
    val scope = rememberCoroutineScope()
    Box(modifier.fillMaxSize().background(colors.ground).testTag("onboarding_screen")) {
        HorizontalPager(state = pager, modifier = Modifier.fillMaxSize()) { index ->
            val page = pages[index]
            // The photograph fills the top 58% (404 of the frame's 692), as in the design, whatever the phone's height.
            androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
                val imageHeight = maxHeight * 0.585f
                Image(painterResource(page.image), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth().height(imageHeight))
                Box(
                    Modifier.fillMaxWidth().height(imageHeight).background(
                        Brush.verticalGradient(0f to Color(0x9E000000), 0.34f to Color(0x1F000000), 0.74f to Color(0xB3000000), 1f to Color.Black),
                    ),
                )
                Column(Modifier.fillMaxSize().padding(horizontal = Spacing.s18).navigationBarsPadding(), verticalArrangement = Arrangement.Bottom) {
                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.s12)) {
                        Eyebrow(page.eyebrow, muted = index > 0)
                        DisplayText(page.title, style = TextStyles.detailTitle.copy(lineHeight = 26.6.sp(), letterSpacing = 0.sp()))
                        Text(page.body, style = TextStyles.body, color = colors.body)
                    }
                    // Room for the dots and buttons, which sit outside the pager.
                    Spacer(Modifier.height(Spacing.s18 + 6.dp + Spacing.s18 + 48.dp + Spacing.s18))
                }
            }
        }
        // Dots and buttons sit outside the pager so they do not slide with the page.
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = Spacing.s18).navigationBarsPadding().padding(bottom = Spacing.s18),
            verticalArrangement = Arrangement.spacedBy(Spacing.s18),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.s8, Alignment.CenterHorizontally)) {
                pages.indices.forEach { i ->
                    val w by animateDpAsState(if (i == pager.currentPage) 18.dp else 6.dp, label = "dot")
                    Box(Modifier.size(w, 6.dp).background(if (i == pager.currentPage) colors.accent else colors.raised, PillShape))
                }
            }
            val last = pager.currentPage == pages.lastIndex
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.s12)) {
                // The last page has no Skip: "Find my server" is the only way on, as in the design.
                if (!last) {
                    Box(
                        Modifier.width(80.dp).height(48.dp).clickable(interactionSource = null, indication = null, onClick = onFinish).testTag("onboarding_skip_button"),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Text("Skip", style = TextStyles.buttonTertiary, color = colors.ink)
                    }
                }
                PrimaryButton(
                    text = if (last) "Find my server" else "Next",
                    onClick = { if (last) onFindServer() else scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } },
                    testTag = if (last) "onboarding_find_button" else "onboarding_next_button",
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

private fun Double.sp() = androidx.compose.ui.unit.TextUnit(this.toFloat(), androidx.compose.ui.unit.TextUnitType.Sp)
private fun Int.sp() = androidx.compose.ui.unit.TextUnit(this.toFloat(), androidx.compose.ui.unit.TextUnitType.Sp)

/**
 * Splash (design section 02): the moon plate under a radial darkening,
 * the wedge mark in monochrome white over it and the wordmark in Michroma
 * 21 tracked .04em. Wordmark, nothing else: no spinner, no status line.
 */
@Composable
fun SplashContent(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().background(Color.Black).testTag("splash_screen")) {
        Image(painterResource(R.drawable.rg_splash_moon), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Brush.radialGradient(listOf(Color(0x8C000000), Color(0xCC000000), Color.Black), radius = 900f)))
        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Spacing.s18)) {
            Image(painterResource(R.drawable.rg_wedge_white), contentDescription = null, modifier = Modifier.size(58.dp, 78.dp))
            DisplayText("Regolith", style = TextStyles.wordmark.copy(letterSpacing = 0.04.em()))
        }
    }
}

private fun Double.em() = androidx.compose.ui.unit.TextUnit(this.toFloat(), androidx.compose.ui.unit.TextUnitType.Em)
