package com.regolith.ui.onboarding

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import com.regolith.R
import com.regolith.ui.components.DisplayText
import com.regolith.ui.components.Eyebrow
import com.regolith.ui.components.StrataWedge
import com.regolith.ui.components.PrimaryButton
import com.regolith.ui.theme.PillShape
import com.regolith.ui.theme.RegolithTheme
import com.regolith.ui.theme.Spacing
import com.regolith.ui.theme.TextStyles
import com.regolith.ui.theme.designSp
import kotlinx.coroutines.launch
import com.regolith.ui.theme.scaledDp

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
                        DisplayText(page.title, style = TextStyles.detailTitle.copy(lineHeight = 26.6.designSp(), letterSpacing = 0.em))
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
                        Modifier.width(80.dp).height(48.scaledDp()).clickable(interactionSource = null, indication = null, onClick = onFinish).testTag("onboarding_skip_button"),
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


/**
 * Splash: the Strata Wedge assembling itself on a dark gray ground, the
 * wordmark rising under it. Nothing else -- no spinner, no status line.
 *
 * It used to be a moon photograph under a radial darkening, but the plate
 * was 427x640 and every phone stretched it to fill the screen, which is
 * what made it look soft. Nothing here is a bitmap: [StrataWedge] draws the
 * mark, so it is sharp at any density and its five bands can arrive one at
 * a time.
 *
 * The timing is [SplashEntrance]'s, and it is built to land with a beat of
 * stillness before [SPLASH_MS] fades the whole screen out.
 */
@Composable
fun SplashContent(modifier: Modifier = Modifier) {
    val colors = RegolithTheme.colors
    // One linear driver; the shape of the motion is in the pure functions,
    // where it can be tested. Web analogy: one clock, and the easing is
    // arithmetic rather than a CSS keyframe per element.
    val elapsed = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        elapsed.animateTo(
            targetValue = ENTRANCE_TOTAL_MS.toFloat(),
            animationSpec = tween(durationMillis = ENTRANCE_TOTAL_MS, easing = LinearEasing),
        )
    }
    val now = elapsed.value.toLong()
    val word = wordmarkProgress(now)
    Box(modifier.fillMaxSize().background(colors.splashGround).testTag("splash_screen")) {
        Column(
            Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.s18),
        ) {
            StrataWedge(
                bandOffsetX = { index -> bandOffsetX(index, now) },
                bandAlpha = { index -> bandAlpha(index, now) },
            )
            Text(
                text = "REGOLITH",
                style = TextStyles.splashWordmark,
                color = colors.ink,
                modifier = Modifier
                    .graphicsLayer {
                        alpha = word
                        translationY = (1f - word) * WORDMARK_RISE_DP.dp.toPx()
                    }
                    .testTag("splash_wordmark"),
            )
        }
    }
}
