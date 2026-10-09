package com.regolith.ui.navigation

import androidx.compose.material3.SnackbarResult
import com.regolith.ui.components.MessageKind
import com.regolith.ui.components.showMessage
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.background
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import android.util.Log
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.media3.common.util.UnstableApi
import androidx.compose.ui.Modifier
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import com.regolith.AppViewModel
import com.regolith.BuildConfig
import com.regolith.R
import androidx.compose.runtime.rememberCoroutineScope
import com.regolith.ui.adaptive.LocalWindowShape
import com.regolith.ui.adaptive.rememberWindowShape
import com.regolith.ui.addserver.AddServerViewModel
import com.regolith.ui.addserver.FolderPickerScreen
import com.regolith.ui.addserver.FolderPickerViewModel
import com.regolith.ui.addserver.ManualEntryScreen
import com.regolith.ui.addserver.NameServerScreen
import com.regolith.ui.addserver.NameServerViewModel
import com.regolith.ui.addserver.ScanningScreen
import com.regolith.ui.addserver.ScanningViewModel
import com.regolith.ui.addserver.SearchServersScreen
import com.regolith.ui.addserver.SharePickerScreen
import com.regolith.ui.serverdetail.ServerDetailScreen
import com.regolith.ui.serverdetail.ServerDetailViewModel
import com.regolith.ui.addserver.SharePickerViewModel
import com.regolith.ui.browse.BrowseScreen
import com.regolith.ui.browse.BrowseViewModel
import com.regolith.ui.onboarding.SPLASH_MS
import com.regolith.ui.player.PlayerScreen
import com.regolith.ui.player.PlayerViewModel
import com.regolith.ui.components.IconCircleButton
import com.regolith.ui.components.LocalNavChromeHold
import com.regolith.ui.components.CHROME_MESSAGE_MAX_WIDTH
import com.regolith.ui.components.ChromeMessageHost
import com.regolith.ui.components.LocalAppSnackbar
import com.regolith.ui.poster.PosterEditorScreen
import com.regolith.ui.poster.PosterEditorViewModel
import com.regolith.ui.poster.SetPosterScreen
import com.regolith.ui.poster.SetPosterViewModel
import com.regolith.ui.components.LocalSelectionChrome
import com.regolith.ui.components.LocalNavChromeVisible
import com.regolith.ui.components.LocalNavRailInset
import com.regolith.ui.components.PosterFlightLayout
import com.regolith.ui.components.LocalMiniPlayerClearance
import com.regolith.ui.components.NAV_PILL_HEIGHT
import androidx.compose.foundation.layout.Spacer
import com.regolith.ui.player.MiniPlayerViewModel
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import com.regolith.ui.player.LocalInPictureInPicture
import com.regolith.ui.player.PictureInPictureFilm
import com.regolith.ui.player.pictureInPictureParams
import com.regolith.ui.player.pipAspect
import com.regolith.ui.player.rememberInPictureInPicture
import com.regolith.ui.player.MiniPlayerHandoff
import com.regolith.ui.player.LocalMiniPlayerHandoff
import com.regolith.ui.player.miniBarPictureRect
import com.regolith.ui.player.miniCardPictureRect
import com.regolith.ui.player.LandedPicture
import com.regolith.ui.player.videoFrame
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.toSize
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.animation.core.tween
import com.regolith.ui.player.MiniPlayerBar
import com.regolith.ui.player.MiniPlayerCard
import com.regolith.ui.player.MINI_BAR_HEIGHT
import com.regolith.ui.player.MINI_CARD_HEIGHT
import com.regolith.ui.player.MINI_CARD_WIDTH
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import com.regolith.ui.components.rememberPosterFlightDecorator
import com.regolith.ui.components.NavChromeHold
import androidx.compose.material3.SnackbarHostState
import com.regolith.ui.components.SelectionChrome
import com.regolith.ui.components.SelectionSummaryTier
import com.regolith.ui.components.LocalNavPillInsets
import com.regolith.ui.components.NAV_PILL_CLEARANCE
import com.regolith.ui.components.NAV_RAIL_INSET
import com.regolith.ui.components.NAV_RAIL_SPINE_INSET
import com.regolith.ui.components.NavRailSpine
import com.regolith.ui.components.BackgroundWorkTier
import com.regolith.ui.components.NavPill
import com.regolith.ui.home.ContinueWatchingScreen
import com.regolith.ui.home.HomeScreen
import com.regolith.ui.library.LibraryScreen
import com.regolith.ui.library.LibraryViewModel
import com.regolith.ui.lightbox.LightboxScreen
import com.regolith.ui.lightbox.LightboxViewModel
import com.regolith.ui.story.StoryScreen
import com.regolith.ui.story.StoryViewModel
import com.regolith.ui.lightbox.LocalPictureFocus
import com.regolith.ui.lightbox.PictureFocus
import com.regolith.ui.search.SearchScreen
import com.regolith.ui.search.SearchViewModel
import com.regolith.ui.onboarding.OnboardingScreen
import com.regolith.ui.lock.LockScreen
import com.regolith.ui.onboarding.SplashContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeOut
import androidx.compose.animation.fadeIn
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import com.regolith.ui.settings.SettingsScreen
import com.regolith.ui.shorts.ShortsScreen
import com.regolith.ui.titledetail.TitleDetailScreen
import com.regolith.ui.titledetail.TitleDetailViewModel
import com.regolith.ui.theme.RegolithTheme
import com.regolith.ui.theme.Spacing
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import kotlin.random.Random

/**
 * The router. One back stack (a list of [RegolithKey]), one [NavDisplay]
 * that renders the top key, and one [NavPill] floated over it.
 *
 * Tab rule: switching tabs resets the stack to `[Home, tab]` so system back
 * from any tab returns to Home and back from Home leaves the app. Pushed
 * screens (Player, TitleDetail, Add Server) sit above the tab that opened
 * them and hide the pill.
 *
 * Web analogy: `<Router>` + `<Routes>` + a persistent bottom nav rendered
 * outside the route outlet.
 */
// Media3's UnstableApi is a Java opt-in marker; androidx's @OptIn (not Kotlin's) is what lint checks for.
@androidx.annotation.OptIn(UnstableApi::class)
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun RegolithNavGraph(appViewModel: AppViewModel) {
    val start by appViewModel.startDestination.collectAsStateWithLifecycle()
    val dimmedTabs by appViewModel.dimmedTabs.collectAsStateWithLifecycle()
    val tabDots by appViewModel.tabDots.collectAsStateWithLifecycle()
    val backgroundWork by appViewModel.backgroundWork.collectAsStateWithLifecycle()
    val locked by appViewModel.locked.collectAsStateWithLifecycle()
    // The film that carries on once the player is put away (MiniPlayer.kt).
    // Activity-scoped, like everything here: it outlives every screen.
    val miniPlayer: MiniPlayerViewModel = hiltViewModel()
    val playback by miniPlayer.state.collectAsStateWithLifecycle()
    val miniPlayerPlayer by miniPlayer.player.collectAsStateWithLifecycle()
    val pictureInPicture by miniPlayer.pictureInPicture.collectAsStateWithLifecycle()
    // What the player and the mini player tell each other as the film passes
    // between them: where it lands, and when to take it (MiniPlayerHandoff).
    val handoff = remember { MiniPlayerHandoff() }
    // A frame kept for a hand-over is a few MB; none is wanted once the film is gone.
    LaunchedEffect(playback.loaded) {
        if (!playback.loaded) {
            handoff.frame = null
            handoff.landed = null
        }
    }
    // The Compose root, which the video surfaces hang under (videoFrame).
    val rootView = LocalView.current
    // The picture the lightbox is showing, for the album it closes back onto.
    val pictureFocus = remember { PictureFocus() }
    // How high the phone's bar sits where the player is being put away to,
    // as the slot below was worked out: where the bar lands (barLift).
    var barLandingLift by remember { mutableStateOf(0.dp) }
    // The window, measured by the root: where the mini player's picture is
    // worked out from, in the same coordinates the player measures itself in.
    var rootSize by remember { mutableStateOf(IntSize.Zero) }
    // null = preferences still loading; the system splash is covering us.
    val startKey = start ?: return

    val backStack = rememberNavBackStack(startKey)
    val scope = rememberCoroutineScope()
    val hazeState = remember { HazeState() }
    // The design's splash: the moon plate for "two seconds at most" on a cold start, then it fades.
    var splash by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) { delay(SPLASH_MS); splash = false }
    val topKey = backStack.lastOrNull() as? RegolithKey

    // The window as a media query: wide (a foldable's inner display, a tablet)
    // plus fold posture. Provided once here; screens read LocalWindowShape.
    // Recomputed on fold/unfold, which the manifest turns into a recompose
    // rather than an Activity restart. The log line is how the QA loop reads
    // it on the emulator (`adb logcat -s Regolith`).
    val windowShape = rememberWindowShape()
    // On a wide window Title Detail is a PAGE beside the wall that opened it,
    // not a pushed screen (WallSceneStrategy, below). The key underneath is
    // then still the visible tab, so the rail keeps its selection and the wall
    // rings the open title. Both walls qualify: a file opened from Browse
    // behaves exactly like one opened from Library. This is the same test the
    // strategy makes, read off the same stack, so the two cannot disagree.
    val paneListKey = if (windowShape.wide && topKey is RegolithKey.TitleDetail) {
        (backStack.getOrNull(backStack.lastIndex - 1) as? RegolithKey)
            ?.takeIf { it is RegolithKey.Library || it is RegolithKey.Browse }
    } else {
        null
    }
    val currentTab = MainTab.forKey(topKey) ?: MainTab.forKey(paneListKey)
    val paneFileId = (topKey as? RegolithKey.TitleDetail)?.fileId?.takeIf { paneListKey != null }
    LaunchedEffect(windowShape) { if (BuildConfig.DEBUG) Log.i("Regolith", "window shape: $windowShape") }
    // What a tab screen must keep clear for the pill. On a phone the pill
    // floats over the bottom of the list; on a wide window it is a rail on
    // the start edge, reserved by [TabContent] below, so the list only needs
    // to clear the system navigation bar.
    val navBarBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    // The rail can be away in two deliberately different ways (F6):
    //  - PINNED AWAY ([railHidden], remembered in preferences): the layout
    //    hands the rail's 102dp back to the screen, so the wall gets its
    //    third tile at full width. Content reflows, so it only ever happens
    //    because you asked for it.
    //  - IDLE ([railIdle], this session only): the rail slides off the start
    //    edge a few seconds after the last touch ([navHideAfter]). The reserved inset does not
    //    change, so nothing reflows and the wall never jumps as you read it.
    // Either way the spine stays on the edge, and tapping it brings the rail
    // back. Web analogy: one is a collapsed sidebar, the other is a toolbar
    // that fades while you scroll.
    val railHidden by appViewModel.railHidden.collectAsStateWithLifecycle()
    val autoHideRail by appViewModel.autoHideRail.collectAsStateWithLifecycle()
    // How long "a few seconds" is: Settings › Display › Hide after.
    val navHideAfter by appViewModel.navHideAfter.collectAsStateWithLifecycle()
    var railIdle by remember { mutableStateOf(false) }
    // The selection toolbar lives in the nav chrome, so the chrome must be
    // there whenever a selection is. That outranks BOTH ways the rail goes
    // away — idling (a timer) and being pinned away (a preference). A pinned
    // rail would otherwise leave a wide window with no way to act on a
    // selection at all, which is exactly what it did.
    val selectionChrome = remember { SelectionChrome() }
    val selecting = selectionChrome.state != null
    val railVisible = windowShape.wide && (selecting || (!railHidden && !railIdle))
    // The mini player shows once the player itself has been put away, on the
    // tabs and on the pages pushed over them (the title page is where back
    // from the player lands). Not over Shorts, whose clips are a player of
    // their own (it pauses the film as it opens), not in the full-screen
    // flows — onboarding, adding a server, the poster editor — and not while
    // a selection has the chrome. Screens clear it as they clear the pill.
    val playerOnStack = backStack.any { it is RegolithKey.Player }
    val miniPlayerShown = playback.loaded && !playerOnStack && topKey.hostsMiniPlayer() && !selecting
    // Not while the player is still on screen shrinking into it: the picture
    // is the player's until it hands it over (MiniPlayerHandoff).
    val miniPlayerDrawn = miniPlayerShown && !handoff.playerShowing
    val miniPlayerClearance = when {
        !miniPlayerShown -> 0.dp
        windowShape.wide -> MINI_CARD_HEIGHT + Spacing.s18
        else -> MINI_BAR_HEIGHT + Spacing.s8
    }
    val pillInsets = if (windowShape.wide) {
        PaddingValues(bottom = navBarBottom + Spacing.s18 + miniPlayerClearance)
    } else {
        PaddingValues(bottom = NAV_PILL_CLEARANCE + miniPlayerClearance)
    }
    // Bottom-centred chrome on a wide window keeps to the left of the card.
    val miniCardBeside = if (windowShape.wide && miniPlayerShown) MINI_CARD_WIDTH + Spacing.s18 else 0.dp
    // A phone's pill hides on the same timer, minus the pinning: there is no
    // spine to pin it to, so going idle is the only way it leaves, and any
    // touch brings it straight back.
    // Phones get a second, independent reason for the pill to be away:
    // scrolling DOWN puts it out of the way and keeps it there, which is
    // 112dp of a small screen handed back to the thing being read. The two
    // reasons never argue, because absent always beats present.
    val pillScroll = rememberNavPillScroll()
    // A new tab is a new thing to look at, and it starts at the top of its
    // own scroll. Arriving on it with the nav still scrolled away would
    // punish you for where you had got to on the LAST screen.
    LaunchedEffect(currentTab) { pillScroll.reveal() }
    // Something is saying "not yet" — today, a snackbar that is still on
    // screen. Phones only: on a wide window the rail is on the side edge and
    // a message at the bottom never sat on top of it, so there is nothing to
    // put back in step.
    val navChromeHold = remember { NavChromeHold() }
    // Every tab screen's messages land here, so the chrome can dock them
    // above the pill instead of each screen floating its own.
    val appSnackbar = remember { SnackbarHostState() }
    val messageUp = appSnackbar.currentSnackbarData != null
    val navVisible = if (windowShape.wide) {
        railVisible
    } else {
        selecting || messageUp || navChromeHold.held || (!railIdle && !pillScroll.hidden)
    }
    // Search is a pushed screen with no nav of its own, but a pick made there
    // needs the selection's toolbar, and the toolbar IS the nav chrome. So
    // the chrome comes up on Search while something is picked, and stays
    // while the message that reports on it is showing: a message needs a
    // host to be shown in, or it holds the queue (see the wide window's host
    // below). The pill itself appears there only as the toolbar, never as a nav.
    // The lightbox is the same case without the picking: its Rename, Move and
    // Delete, and a poster set from it, report in that same message line.
    val pushedChrome = (topKey is RegolithKey.Search || topKey is RegolithKey.Lightbox) && (selecting || messageUp)
    val pillHere = currentTab != null || selecting
    // Search has no rail beside it, so while the rail is its toolbar it moves
    // over to make room, as the tab screens always have (tabContent).
    val searchInset by animateDpAsState(
        if (windowShape.wide && selecting && topKey is RegolithKey.Search) NAV_RAIL_INSET else 0.dp,
        label = "searchInset",
    )
    // F6 reserved the rail's 102dp even while it was slid away, so nothing
    // would reflow. What that actually produced was a screen with an obvious
    // empty stripe down the side and no rail in it — and the two ways of
    // hiding the rail reserving different amounts of space, which read as a
    // bug rather than as a decision. The inset now follows what is on screen
    // either way, and ANIMATES there, which is what "nothing should jump"
    // really wanted.
    val railInsetTarget = when {
        !windowShape.wide -> 0.dp
        railVisible -> NAV_RAIL_INSET
        else -> NAV_RAIL_SPINE_INSET
    }
    val railInset by animateDpAsState(railInsetTarget, label = "railInset")
    // Where the mini player's picture will be if the player on top is put away
    // now: on the screen under it, which is where back lands. A phone's bar
    // rides above the pill on a tab and sits low on a pushed page; a wide
    // window's card keeps to the wall's half beside an open page. Kept, not
    // cleared, once the player is gone: it is read all the way down.
    // Where a phone's bar will sit on [page] once the player has gone from
    // over it: above the pill if the page shows one. Arriving on a tab
    // brings back a pill that was scrolled away, so only going idle keeps it
    // off; a swipe down is a touch and wakes it, the system's back swipe is
    // not one and does not.
    fun barLiftThere(page: RegolithKey?): Dp {
        val pillThere = MainTab.forKey(page) != null && !splash && locked != true &&
            (selecting || messageUp || navChromeHold.held || !railIdle)
        return if (pillThere) MINI_BAR_LIFT else 0.dp
    }
    if (topKey is RegolithKey.Player && playback.loaded && rootSize != IntSize.Zero) {
        val below = backStack.getOrNull(backStack.lastIndex - 1) as? RegolithKey
        val belowBelow = backStack.getOrNull(backStack.lastIndex - 2) as? RegolithKey
        val window = rootSize.toSize()
        val density = LocalDensity.current
        val slot = when {
            !below.hostsMiniPlayer() -> null
            windowShape.wide -> {
                val besideWall = below is RegolithKey.TitleDetail && (belowBelow is RegolithKey.Library || belowBelow is RegolithKey.Browse)
                val end = if (besideWall) (windowShape.width - railInset - Spacing.s18) / 2 + Spacing.s18 + Spacing.s18 else Spacing.s18
                miniCardPictureRect(window, navBarBottom, end, density)
            }
            else -> miniBarPictureRect(window, navBarBottom, barLiftThere(below), density)
        }
        SideEffect {
            handoff.slot = slot
            barLandingLift = barLiftThere(below)
        }
    }
    // With a page open beside a wall the card keeps to the wall's half, at its
    // end, rather than sitting on the page: the page takes the end half of
    // what the rail leaves, after the s18 gap between them (WallScene).
    val miniCardEnd = if (windowShape.wide && paneListKey != null) {
        (windowShape.width - railInset - Spacing.s18) / 2 + Spacing.s18 + Spacing.s18
    } else {
        Spacing.s18
    }
    // A pushed page has no pill: a phone's bar docks to the bottom on its own,
    // and the page stops short of it — as it does short of a wide window's
    // card, which would otherwise sit on the page's last rows.
    val miniOnPushedPage = miniPlayerShown && currentTab == null && !pushedChrome
    val pushedClearance by animateDpAsState(
        when {
            !miniOnPushedPage -> 0.dp
            windowShape.wide -> navBarBottom + Spacing.s18 + MINI_CARD_HEIGHT + Spacing.s18
            else -> navBarBottom + Spacing.s8 + MINI_BAR_HEIGHT + Spacing.s8
        },
        label = "miniPlayerClearance",
    )
    // Every touch in the app, observed without consuming (Initial pass) and
    // reported down a flow rather than into state, so a scroll does not
    // recompose the tree on every frame. collectLatest restarts the delay on
    // each touch: the rail retracts only once you have actually stopped.
    val touches = remember { MutableSharedFlow<Unit>(extraBufferCapacity = 1) }
    LaunchedEffect(autoHideRail, navHideAfter, windowShape.wide, railHidden) {
        railIdle = false
        // The scroll watcher is detached when the setting is off, so it
        // cannot un-hide itself; say so here or the pill stays gone.
        pillScroll.reveal()
        // Phones hide their pill on the same timer. The wide-window gate that
        // used to be here meant the setting only ever did anything on a
        // tablet, which is also why it was hidden from a phone's Settings.
        //
        // [railHidden] only counts on a wide window. It is the PIN -- the
        // chevron under the rail, which a phone never draws -- and it is
        // remembered, so a Fold pinned away on its inner display came back to
        // its outer one with the pill's auto-hide silently dead and no
        // control anywhere to revive it.
        if (!autoHideRail || (windowShape.wide && railHidden)) return@LaunchedEffect
        touches.onStart { emit(Unit) }.collectLatest {
            // A phone has no spine to tap, so touching anything is what brings
            // the pill back. A wide window keeps its old manners: the rail
            // returns when you ask the spine for it, because a touch in the
            // content reflowing the layout under your finger is worse than
            // reaching for the edge.
            if (!windowShape.wide) railIdle = false
            delay(navHideAfter.idleMs)
            railIdle = true
        }
    }

    // How the walls use a wide window (F15): the whole window while nothing
    // is open, and an even split once a title's page slides in beside them.
    // Two panes from 600dp, the same line as WindowShape.wide, so the rail and
    // the split arrive together. The split itself never moves (F13): what a
    // divider was for — seeing the whole wall — is what closing the page does.
    val wallScenes = remember(windowShape.wide) { WallSceneStrategy<NavKey>(windowShape.wide) }

    // A film handed over by another app opens the player on top of whatever
    // was there, and is consumed so a rotation does not reopen it.
    val external by appViewModel.external.collectAsStateWithLifecycle()
    LaunchedEffect(external) {
        val video = external ?: return@LaunchedEffect
        backStack.add(RegolithKey.Player.external(video.uri, video.title))
        appViewModel.openedExternal()
    }

    // A selection survives walking the tree — that is the whole point of the
    // shared store, and a pick three folders deep needs it. What ends it is
    // arriving somewhere that cannot act on it: only Browse, Library and
    // Search draw the bar, so anywhere else the picks would be invisible and
    // unreachable. One rule covers back, the nav pill and a push into the
    // player, where three separate ones drifted apart.
    val canSelectHere = currentTab == MainTab.BROWSE ||
        currentTab == MainTab.LIBRARY ||
        topKey is RegolithKey.Search

    // Picture-in-picture (PictureInPicture.kt). Android floats the app by
    // itself when it is left while a film plays and the setting is on; the
    // window is shaped like the film. Floating, the session holds a finished
    // film rather than closing it, and the lock times the trip.
    val activity = LocalActivity.current as? ComponentActivity
    val inPip = rememberInPictureInPicture(activity)
    LaunchedEffect(inPip) {
        miniPlayer.setFloating(inPip)
        if (inPip) appViewModel.enteredPictureInPicture() else appViewModel.leftPictureInPicture()
        // A film that finished in the window, opening out onto the mini player
        // rather than the player: over, so it closes as it would have there.
        if (!inPip && playback.ended && !playerOnStack) miniPlayer.close()
    }
    val floats = pictureInPicture && playback.loaded && playback.playWhenReady && !playback.ended && locked != true
    val pipShape = pipAspect(playback.video)
    // The window grows out of the film where it is: the player's picture, or
    // the mini player's when the player is put away. Not while floating: laid
    // out in the small window, they would aim the window's way back out at
    // its own corner, so it opens out the plain way instead.
    val pipSource = (if (handoff.playerShowing) handoff.playerPicture else handoff.miniPicture).takeUnless { inPip }
    LaunchedEffect(activity, floats, pipShape, pipSource) {
        activity?.setPictureInPictureParams(pictureInPictureParams(floats, playback.video, pipSource))
    }
    // For `adb logcat -s Regolith/PiP` on a phone: whether leaving the app now
    // would float the film, and if not, which condition said no.
    LaunchedEffect(floats) {
        Log.d(
            "Regolith/PiP",
            "floats=$floats setting=$pictureInPicture loaded=${playback.loaded} " +
                "playing=${playback.playWhenReady} ended=${playback.ended} locked=$locked",
        )
    }

    LaunchedEffect(canSelectHere) {
        if (!canSelectHere) appViewModel.clearSelection()
    }

    // The download notification was tapped. Downloads have no destination of
    // their own by design, so this is Library's own device tab.
    val openDownloads by appViewModel.openDownloads.collectAsStateWithLifecycle()
    LaunchedEffect(openDownloads) {
        if (!openDownloads) return@LaunchedEffect
        backStack.clear()
        backStack.add(RegolithKey.Home)
        backStack.add(RegolithKey.Library(onDevice = true))
        appViewModel.openedDownloads()
    }

    // Uploads (P16) are always going SOMEWHERE, so everything that speaks
    // about them — the tier, the capsule's "Show", the notifications — leads
    // to that folder. The stack is rebuilt the way the share tree's jump
    // rebuilds it, so back leaves Browse instead of walking up through
    // folders the jump skipped. Null is Browse itself (more than one folder).
    fun openUploadFolder(folderId: Long?) {
        val target = RegolithKey.Browse(folderId)
        if (backStack.lastOrNull() == target) return
        backStack.clear()
        backStack.add(RegolithKey.Home)
        backStack.add(target)
    }
    val openUploads by appViewModel.openUploads.collectAsStateWithLifecycle()
    LaunchedEffect(openUploads) {
        val folderId = openUploads ?: return@LaunchedEffect
        openUploadFolder(folderId.takeIf { it >= 0 })
        appViewModel.openedUploads()
    }
    // A poster made from a picture (Set as poster closes as it finishes).
    LaunchedEffect(Unit) {
        appViewModel.posterNotices.collect { text -> launch { appSnackbar.showMessage(text, kind = MessageKind.DONE) } }
    }
    // Save to phone, which carries on after the album is left.
    LaunchedEffect(Unit) {
        appViewModel.pictureSaves.collect { saved ->
            launch { appSnackbar.showMessage(saved.text, kind = if (saved.failed) MessageKind.FAILED else MessageKind.DONE) }
        }
    }
    // One message per finished batch, wherever the user is by then. "Show"
    // only when they are somewhere else: in the folder, the rows say it all.
    LaunchedEffect(Unit) {
        appViewModel.uploadNotices.collect { notice ->
            val here = (backStack.lastOrNull() as? RegolithKey.Browse)?.folderId == notice.folderId
            // Its own coroutine, so a message on screen does not hold up the next batch's.
            launch {
                val result = appSnackbar.showMessage(
                    notice.text,
                    kind = if (notice.failed) MessageKind.FAILED else MessageKind.DONE,
                    actionLabel = if (here) null else "Show",
                )
                if (result == SnackbarResult.ActionPerformed) openUploadFolder(notice.folderId)
            }
        }
    }

    // The four tab screens sit beside the rail on a wide window. Pushed
    // screens (Player, Title Detail, Add Server) have no rail and take the
    // whole width, so the inset is applied per entry, not on the NavDisplay.
    val tabContent: @Composable (@Composable () -> Unit) -> Unit = { content ->
        // The rail is allowed to resize what is beside it: that is the point
        // of retracting it. The column lays out to whatever is left and lays
        // out again when the rail goes away — no clipping, because nothing is
        // being dragged any more and the width only changes when the rail does.
        Box(Modifier.fillMaxSize().padding(start = railInset)) { content() }
    }

    // The mini player opens the player again on the film it is playing,
    // which picks it up as it was rather than loading it afresh (`expand`).
    fun expandPlayer() {
        // The film's frame off the mini player's surface, for the player's
        // picture to show as it grows out of it until its own surface has
        // drawn one (MiniPlayerHandoff.frame). A mini player that has drawn
        // nothing yet, a film put away paused, still has the frame it landed with.
        handoff.frame = rootView.videoFrame() ?: handoff.frame
        val s = playback
        backStack.add(
            RegolithKey.Player(
                fileId = s.fileId ?: RegolithKey.Player.EXTERNAL,
                externalTitle = s.title.takeIf { s.fileId == null },
                expand = true,
            ),
        )
    }

    // Play all / Shuffle from a collection. The order is the one the wall was
    // showing, shuffled here rather than in the session so the session never
    // has to know what a wall is; the queue rides on the Player key so it
    // survives the process being killed.
    fun playAll(fileIds: List<Long>, shuffle: Boolean) {
        val queue = if (shuffle) fileIds.shuffled() else fileIds
        val first = queue.firstOrNull() ?: return
        backStack.add(RegolithKey.Player(first, queue = queue))
    }

    // Opening a title. Beside a wall the wall stays live, so picking another
    // title REPLACES the open page — stacking two would leave the wall ringing
    // the wrong tile and turn the page's close control back into a back arrow
    // — and picking the SAME one again puts the page away: the ringed tile is
    // the toggle. On a phone the wall is covered, so the top key is never a
    // page and this is the plain push it always was.
    fun openTitle(fileId: Long) {
        val open = backStack.lastOrNull() as? RegolithKey.TitleDetail
        if (windowShape.wide && open != null) {
            backStack.removeLastOrNull()
            if (open.fileId == fileId) return
        }
        backStack.add(RegolithKey.TitleDetail(fileId))
    }

    // Walking somewhere else from a wall whose page is open — into a
    // collection, a folder — closes the page first. The page belongs to the
    // wall it was opened from; carried into the next one it would sit beside
    // tiles it is not among, and back would bring it round again. A phone
    // never has a page beside a wall, so there it is the plain push.
    fun openFromWall(key: RegolithKey) {
        if (windowShape.wide && backStack.lastOrNull() is RegolithKey.TitleDetail) backStack.removeLastOrNull()
        backStack.add(key)
    }

    // A collection wall's own back arrow leaves the WALL, page and all. With a
    // page open, the top key is the page, so a plain pop would only close it
    // and the arrow would take two taps to do what it says.
    fun leaveWall(wall: RegolithKey) {
        val at = backStack.lastIndexOf(wall)
        if (at < 0) return
        while (backStack.size > at) backStack.removeAt(backStack.lastIndex)
    }

    /**
     * A tab cell switches tabs; the CURRENT tab's cell brings its stack back
     * to the top — Library three folders deep becomes Library. Already at
     * the top, it does nothing, so a stray tap does not rebuild the screen.
     */
    // A tap on the tab you are already on. Nothing navigates, so a screen
    // that wants to react (Shorts deals a new deck) listens here. Web
    // analogy: an event bus the router fires when you click the active link.
    val tabReselects = remember { MutableSharedFlow<MainTab>(extraBufferCapacity = 1) }
    val shortsReselects = remember { tabReselects.filter { it == MainTab.SHORTS }.map { } }

    fun navigateToTab(tab: MainTab, force: Boolean = false) {
        if (backStack.lastOrNull() == tab.key && !force) return
        backStack.clear()
        if (tab != MainTab.HOME) backStack.add(RegolithKey.Home)
        backStack.add(tab.key)
    }

    // Root container. testTagsAsResourceId: without this, uiautomator (and
    // therefore argent's `describe`) cannot see any Compose testTag at all.
    CompositionLocalProvider(
        LocalWindowShape provides windowShape,
        LocalNavPillInsets provides pillInsets,
        LocalMiniPlayerClearance provides pushedClearance,
        LocalMiniPlayerHandoff provides handoff,
        LocalPictureFocus provides pictureFocus,
        LocalInPictureInPicture provides inPip,
        LocalNavRailInset provides railInset,
        // The same answer the pill acts on, published for screens that float
        // their own chrome. One timer, so nothing can drift out of step.
        LocalNavChromeVisible provides navVisible,
        LocalNavChromeHold provides navChromeHold,
        LocalSelectionChrome provides selectionChrome,
        LocalAppSnackbar provides appSnackbar,
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(RegolithTheme.colors.ground)
                // Watch every touch without taking it: the Initial pass sees
                // the event before any child, and nothing here consumes it.
                // This is what tells the rail you are still using the app.
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        // Tracked across events rather than read per event:
                        // a drag reports "pressed" on every move, and
                        // treating each of those as a fresh press would
                        // forget that the gesture had already scrolled.
                        var wasPressed = false
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            touches.tryEmit(Unit)
                            val pressed = event.changes.any { it.pressed }
                            if (pressed && !wasPressed) pillScroll.onPress()
                            if (!pressed && wasPressed) pillScroll.onRelease()
                            wasPressed = pressed
                        }
                    }
                }
                // Above every scrolling container in the app, so Home's
                // Column, Library's grid and Search's list all report through
                // one place. Phones only: a wide window has the room, and its
                // rail answers to the spine instead.
                .then(if (!windowShape.wide && autoHideRail) Modifier.nestedScroll(pillScroll.connection) else Modifier)
                .semantics { testTagsAsResourceId = true },
        ) {
            // A Box in which a tile's poster can fly into the page it opens (PosterFlight.kt).
            PosterFlightLayout(Modifier.fillMaxSize().onSizeChanged { rootSize = it }) {
                NavDisplay(
                    backStack = backStack,
                    onBack = { backStack.removeLastOrNull() },
                    sceneStrategies = listOf(wallScenes),
                entryDecorators = listOf(
                        rememberSaveableStateHolderNavEntryDecorator(),
                        rememberViewModelStoreNavEntryDecorator(),
                        // Lets a screen's posters fly with its transition (PosterFlight.kt).
                        rememberPosterFlightDecorator(),
                    ),
                    // Screens register as the blur source so the pill frosts
                    // whatever scrolls beneath it.
                    modifier = Modifier.fillMaxSize().hazeSource(hazeState),
                    // Slide in / slide back instead of Navigation 3's scale-and-fade; see Transitions.kt.
                    transitionSpec = { pushSpec(this) },
                    popTransitionSpec = { popSpec(this) },
                    predictivePopTransitionSpec = { _ -> popSpec(this) },
                    entryProvider = entryProvider {
                        entry<RegolithKey.Onboarding> {
                            OnboardingScreen(
                                onFinish = {
                                    appViewModel.completeOnboarding()
                                    backStack.clear()
                                    backStack.add(RegolithKey.Home)
                                },
                                onFindServer = {
                                    appViewModel.completeOnboarding()
                                    backStack.clear()
                                    backStack.add(RegolithKey.Home)
                                    backStack.add(RegolithKey.AddServer.Search)
                                },
                            )
                        }
                        entry<RegolithKey.AddServer.Search> {
                            SearchServersScreen(
                                viewModel = hiltViewModel(),
                                onBack = { backStack.removeLastOrNull() },
                                onPick = { host -> backStack.add(RegolithKey.AddServer.Manual(prefill = "smb://${host.host.host}" + (if (host.host.port != 445) ":${host.host.port}" else ""))) },
                                onManual = { backStack.add(RegolithKey.AddServer.Manual()) },
                            )
                        }
                        entry<RegolithKey.Home>(metadata = tabScreen) {
                            tabContent {
                                HomeScreen(
                                    viewModel = hiltViewModel(),
                                    onAddServer = { backStack.add(RegolithKey.AddServer.Search) },
                                    onEnterAddress = { backStack.add(RegolithKey.AddServer.Manual()) },
                                    onSearch = { backStack.add(RegolithKey.Search()) },
                                    onOpenTitle = { openTitle(it) },
                                    // Only the Continue watching cards play from Home, and their
                                    // pictures fly into the player.
                                    onPlay = { fileId, startMs -> backStack.add(RegolithKey.Player(fileId, startMs, flies = true)) },
                                    onOpenDevice = { backStack.clear(); backStack.add(RegolithKey.Home); backStack.add(RegolithKey.Library(onDevice = true)) },
                                    onOpenContinueWatching = { backStack.add(RegolithKey.ContinueWatching) },
                                    // A name in the Moments section: Search, with it already picked.
                                    onOpenMoment = { backStack.add(RegolithKey.Search(poi = it)) },
                                )
                            }
                        }
                        entry<RegolithKey.Library>(metadata = tabScreen + WallSceneStrategy.wall()) { key ->
                            tabContent {
                                LibraryScreen(
                                    viewModel = hiltViewModel<LibraryViewModel, LibraryViewModel.Factory>(
                                        creationCallback = { it.create(key.folderId, key.filter) },
                                    ),
                                    onBack = if (key.folderId == null) null else ({ leaveWall(key) }),
                                    // Opens with the chip lit here: a profile on that tab.
                                    onOpenCollection = { folderId, filter -> openFromWall(RegolithKey.Library(folderId, filter = filter)) },
                                    onOpenTitle = { openTitle(it) },
                                    onSearch = { backStack.add(RegolithKey.Search()) },
                                    onAddServer = { backStack.add(RegolithKey.AddServer.Search) },
                                    startOnDevice = key.onDevice,
                                    selectedFileId = paneFileId,
                                    // Only inside a collection: on the root, "all" would
                                    // mean every file on every share.
                                    onPlayAll = if (key.folderId != null) ::playAll else null,
                                    onSendingAway = appViewModel::sendingAway,
                                    // A moment on a collection's profile plays from its time, as in Search.
                                    onPlayAt = { fileId, ms -> backStack.add(RegolithKey.Player(fileId, startMs = ms)) },
                                    // Its moments as a reel, from the profile's Moments tab.
                                    onPlayMoments = { title, clips -> backStack.add(RegolithKey.Player.reel(title, clips)) },
                                    // A picture opens the lightbox, its picture flying out of the tile.
                                    onOpenPicture = { folderId, pictureId, onWall ->
                                        pictureFocus.pictureId = pictureId
                                        openFromWall(RegolithKey.Lightbox(folderId, pictureId, onWall))
                                    },
                                    onSetPoster = { folderId, pictureId, uri -> backStack.add(RegolithKey.SetPoster(folderId, pictureId, uri)) },
                                    // Play pictures and its Shuffle, on a profile's Images tab.
                                    onPlayPictures = { folderId, shuffle ->
                                        backStack.add(RegolithKey.Story(folderId, shuffleSeed = if (shuffle) Random.nextLong() else null))
                                    },
                                )
                            }
                        }
                        entry<RegolithKey.ContinueWatching> {
                            ContinueWatchingScreen(
                                viewModel = hiltViewModel(),
                                onBack = { backStack.removeLastOrNull() },
                                onPlay = { fileId, startMs -> backStack.add(RegolithKey.Player(fileId, startMs, flies = true)) },
                            )
                        }
                        entry<RegolithKey.Search> { key ->
                            SearchScreen(
                                viewModel = hiltViewModel<SearchViewModel, SearchViewModel.Factory>(
                                    creationCallback = { it.create(key.poi) },
                                ),
                                onCancel = { backStack.removeLastOrNull() },
                                onOpenTitle = { openTitle(it) },
                                onOpenFolder = { backStack.add(RegolithKey.Browse(it)) },
                                // A point of interest opens the player at that time (P9).
                                onPlayAt = { fileId, ms -> backStack.add(RegolithKey.Player(fileId, startMs = ms)) },
                                modifier = Modifier.padding(start = searchInset),
                                // Opened on a moment, the results are the point: keyboard down.
                                focusField = key.poi == null,
                            )
                        }
                        entry<RegolithKey.Browse>(metadata = tabScreen + WallSceneStrategy.wall()) { key ->
                            tabContent {
                                BrowseScreen(
                                    selectedFileId = paneFileId,
                                    highlightFileId = key.highlightFileId,
                                    viewModel = hiltViewModel<BrowseViewModel, BrowseViewModel.Factory>(
                                        creationCallback = { it.create(key.folderId) },
                                    ),
                                    onBack = if (key.folderId == null) null else ({ leaveWall(key) }),
                                    onOpenFolder = { openFromWall(RegolithKey.Browse(it)) },
                                    onOpenFile = { openTitle(it) },
                                    onAddServer = { backStack.add(RegolithKey.AddServer.Search) },
                                    onPlayAll = ::playAll,
                                    onSendingAway = appViewModel::sendingAway,
                                    onSetPoster = { folderId, pictureId, uri -> backStack.add(RegolithKey.SetPoster(folderId, pictureId, uri)) },
                                    // The tree restarts the Browse chain instead of pushing onto
                                    // it, so back from a jump leaves Browse rather than walking
                                    // through every folder the tree was used to skip.
                                    onOpenTree = if (windowShape.wide) {
                                        { folderId ->
                                            backStack.clear()
                                            backStack.add(RegolithKey.Home)
                                            backStack.add(RegolithKey.Browse(folderId))
                                        }
                                    } else {
                                        null
                                    },
                                )
                            }
                        }
                        // A page posters fly into: it fades in under the poster (pageScreen).
                        // Beside a wall the scene's own metadata applies instead (WallScene).
                        entry<RegolithKey.TitleDetail>(metadata = WallSceneStrategy.page() + pageScreen) { key ->
                            TitleDetailScreen(
                                viewModel = hiltViewModel<TitleDetailViewModel, TitleDetailViewModel.Factory>(
                                    creationCallback = { it.create(key.fileId) },
                                ),
                                onBack = { backStack.removeLastOrNull() },
                                // The hero's picture flies into the player.
                                onPlay = { backStack.add(RegolithKey.Player(it, flies = true)) },
                                inPane = LocalBesideWall.current,
                            )
                        }
                        entry<RegolithKey.Shorts>(metadata = tabScreen) {
                            tabContent {
                                ShortsScreen(
                                    viewModel = hiltViewModel(),
                                    // Locate lands in Browse at the clip's folder, which is
                                    // the existing screen: the feed designs no destination.
                                    // The file id rides along so Browse can scroll to it and
                                    // ring it, rather than dropping you at the top of a folder
                                    // to hunt for the clip you were just watching.
                                    onLocate = { folderId, fileId ->
                                        backStack.add(RegolithKey.Browse(folderId, highlightFileId = fileId))
                                    },
                                    // The same editor the player's Playback sheet opens; it
                                    // comes back here, and Shorts shows its "Poster saved".
                                    onMakePoster = { fileId, ms -> backStack.add(RegolithKey.PosterEditor(fileId, ms)) },
                                    reselects = shortsReselects,
                                )
                            }
                        }
                        entry<RegolithKey.Settings>(metadata = tabScreen) {
                            tabContent {
                                SettingsScreen(
                                    viewModel = hiltViewModel(),
                                    onAddServer = { backStack.add(RegolithKey.AddServer.Search) },
                                    onOpenServer = { serverId -> backStack.add(RegolithKey.ServerDetail(serverId)) },
                                    // Inline rather than navigateToTab, which cannot carry the
                                    // onDevice argument. Downloads have no destination of their
                                    // own by design; Library › On this device is where they live.
                                    onOpenDownloads = {
                                        backStack.clear()
                                        backStack.add(RegolithKey.Home)
                                        backStack.add(RegolithKey.Library(onDevice = true))
                                    },
                                )
                            }
                        }

                        entry<RegolithKey.AddServer.Manual> { key ->
                            ManualEntryScreen(
                                viewModel = hiltViewModel<AddServerViewModel, AddServerViewModel.Factory>(creationCallback = { it.create(key.prefill) }),
                                onBack = { backStack.removeLastOrNull() },
                                onConnected = { serverId -> backStack.add(RegolithKey.AddServer.Name(serverId)) },
                            )
                        }
                        entry<RegolithKey.AddServer.Name> { key ->
                            NameServerScreen(
                                viewModel = hiltViewModel<NameServerViewModel, NameServerViewModel.Factory>(
                                    creationCallback = { it.create(key.serverId) },
                                ),
                                onBack = { backStack.removeLastOrNull() },
                                onContinue = { backStack.add(RegolithKey.AddServer.Shares(key.serverId)) },
                            )
                        }
                        entry<RegolithKey.ServerDetail> { key ->
                            ServerDetailScreen(
                                viewModel = hiltViewModel<ServerDetailViewModel, ServerDetailViewModel.Factory>(
                                    creationCallback = { it.create(key.serverId) },
                                ),
                                onBack = { backStack.removeLastOrNull() },
                                // The share picker IS the folder chooser: it lists a
                                // server's shares with their on/off switches and the
                                // "Choose folders" step behind each. Reached from the
                                // Add Server flow until now, which is the only reason
                                // the choice could be made once and never revised.
                                onChooseShares = { backStack.add(RegolithKey.AddServer.Shares(key.serverId)) },
                            )
                        }
                        entry<RegolithKey.AddServer.Shares> { key ->
                            SharePickerScreen(
                                viewModel = hiltViewModel<SharePickerViewModel, SharePickerViewModel.Factory>(
                                    creationCallback = { it.create(key.serverId) },
                                ),
                                onBack = { backStack.removeLastOrNull() },
                                onContinue = { backStack.add(RegolithKey.AddServer.Scanning(key.serverId)) },
                                onChooseFolders = { shareId -> backStack.add(RegolithKey.AddServer.Folders(shareId)) },
                            )
                        }
                        entry<RegolithKey.AddServer.Folders> { key ->
                            FolderPickerScreen(
                                viewModel = hiltViewModel<FolderPickerViewModel, FolderPickerViewModel.Factory>(
                                    creationCallback = { it.create(key.shareId, key.relPath) },
                                ),
                                onBack = { backStack.removeLastOrNull() },
                                onOpen = { relPath -> backStack.add(RegolithKey.AddServer.Folders(key.shareId, relPath)) },
                                // Done climbs straight back out to the share list, however deep you went.
                                onDone = { backStack.removeAll { it is RegolithKey.AddServer.Folders } },
                            )
                        }
                        entry<RegolithKey.AddServer.Scanning> { key ->
                            ScanningScreen(
                                viewModel = hiltViewModel<ScanningViewModel, ScanningViewModel.Factory>(
                                    creationCallback = { it.create(key.serverId) },
                                ),
                                // Either way the Add Server flow is over: drop it from the stack.
                                onBackground = { navigateToTab(MainTab.HOME, force = true) },
                                onDone = { navigateToTab(MainTab.LIBRARY, force = true) },
                            )
                        }
                        // The player moves itself in and out (playerScreen): back puts
                        // it away into the mini player, which brings it back.
                        entry<RegolithKey.Player>(metadata = playerScreen) { key ->
                            PlayerScreen(
                                viewModel = hiltViewModel<PlayerViewModel, PlayerViewModel.Factory>(
                                    creationCallback = { it.create(key) },
                                ),
                                onBack = { backStack.removeLastOrNull() },
                                onMakePoster = { fileId, ms -> backStack.add(RegolithKey.PosterEditor(fileId, ms)) },
                                expandFromMini = key.expand,
                                fliesIn = key.flies,
                            )
                        }
                        // The lightbox: a picture flies into it, and back (lightboxScreen).
                        entry<RegolithKey.Lightbox>(metadata = lightboxScreen) { key ->
                            LightboxScreen(
                                viewModel = hiltViewModel<LightboxViewModel, LightboxViewModel.Factory>(
                                    creationCallback = { it.create(key.folderId, key.pictureId, key.onWall) },
                                ),
                                onClose = { backStack.removeLastOrNull() },
                                onSetPoster = { folderId, pictureId -> backStack.add(RegolithKey.SetPoster(folderId, pictureId)) },
                                onPlayFrom = { folderId, pictureId -> backStack.add(RegolithKey.Story(folderId, startPictureId = pictureId, onWall = key.onWall)) },
                            )
                        }
                        // A collection's pictures as a story: on black over where it was played from, as the lightbox is.
                        entry<RegolithKey.Story>(metadata = lightboxScreen) { key ->
                            StoryScreen(
                                viewModel = hiltViewModel<StoryViewModel, StoryViewModel.Factory>(
                                    creationCallback = { it.create(key) },
                                ),
                                onClose = { backStack.removeLastOrNull() },
                            )
                        }
                        entry<RegolithKey.SetPoster> { key ->
                            SetPosterScreen(
                                viewModel = hiltViewModel<SetPosterViewModel, SetPosterViewModel.Factory>(
                                    creationCallback = { it.create(key) },
                                ),
                                onClose = { backStack.removeLastOrNull() },
                            )
                        }
                        entry<RegolithKey.PosterEditor> { key ->
                            PosterEditorScreen(
                                viewModel = hiltViewModel<PosterEditorViewModel, PosterEditorViewModel.Factory>(
                                    creationCallback = { it.create(key) },
                                ),
                                onClose = { backStack.removeLastOrNull() },
                            )
                        }
                        // Phase 6: AddServer.Search.
                    },
                )

                // The mini player off the nav's own group: a wide window's card,
                // in the bottom corner away from the rail, and a phone's bar on a
                // pushed page, which has no pill to ride above (MiniPlayer.kt).
                // It fades in and out, but goes at once when the player opens:
                // the player's picture grows out of the mini player's, and a
                // copy of it fading where it was would trail the one growing.
                val miniExit = if (playerOnStack) ExitTransition.None else fadeOut(tween(MINI_ARRIVE_MS))
                if (windowShape.wide) {
                    AnimatedVisibility(
                        visible = miniPlayerDrawn,
                        enter = fadeIn(tween(MINI_ARRIVE_MS)),
                        exit = miniExit,
                        modifier = Modifier.align(Alignment.BottomEnd).navigationBarsPadding()
                            .padding(end = miniCardEnd, bottom = Spacing.s18),
                    ) {
                        MiniPlayerCard(
                            state = playback,
                            player = miniPlayerPlayer,
                            hazeState = hazeState,
                            onExpand = ::expandPlayer,
                            onTogglePlay = miniPlayer::togglePlayPause,
                            // A reel steps between its moments, as the player's own buttons do.
                            onPrevious = playback.reel?.let { { miniPlayer.reelPrevious() } }
                                ?: playback.upPrevious?.let { p -> { miniPlayer.play(p.fileId) } },
                            onNext = playback.reel?.let { r -> r.takeIf { it.hasNext }?.let { { miniPlayer.reelNext() } } }
                                ?: playback.upNext?.takeIf { playback.reel == null }?.let { n -> { miniPlayer.play(n.fileId) } },
                            onClose = miniPlayer::close,
                        )
                    }
                } else {
                    // A phone's bar stays when the pill slides away (scrolled,
                    // or idle) and settles into its place, rather than taking
                    // the playing film with it; above the pill while it shows.
                    //
                    // Landing from the player (MiniPlayerHandoff.landed) it is
                    // where the picture was flown to, at once. Following the
                    // pill, it rose from the bottom as the pill came back
                    // with the page, so it arrived below the picture: two
                    // pictures and a dark bar where one should be. Out of
                    // sight it takes its place at once too, with nothing to
                    // slide; after a landing it goes on to where the pill
                    // says, if that is somewhere else.
                    val pillShowing = navVisible && pillHere && (currentTab != null || pushedChrome) && !splash && locked != true
                    val barLanding = handoff.landed != null
                    val barTarget = when {
                        barLanding -> barLandingLift
                        pillShowing -> MINI_BAR_LIFT
                        else -> 0.dp
                    }
                    // Even a snap reaches an animated value a frame late, and
                    // that frame is the bar's first: so the target is used as
                    // it is, while the animation catches up out of sight.
                    val barSnaps = barLanding || !miniPlayerDrawn
                    val barLiftAnimated by animateDpAsState(
                        barTarget,
                        animationSpec = if (barSnaps) snap() else spring(visibilityThreshold = Dp.VisibilityThreshold),
                        label = "miniBarLift",
                    )
                    val barLift = if (barSnaps) barTarget else barLiftAnimated
                    AnimatedVisibility(
                        visible = miniPlayerDrawn,
                        enter = fadeIn(tween(MINI_ARRIVE_MS)),
                        exit = miniExit,
                        modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding()
                            .padding(start = Spacing.s18, end = Spacing.s18, bottom = Spacing.s8 + barLift),
                    ) {
                        MiniPlayerBar(
                            state = playback,
                            player = miniPlayerPlayer,
                            hazeState = hazeState,
                            onExpand = ::expandPlayer,
                            onTogglePlay = miniPlayer::togglePlayPause,
                            onClose = miniPlayer::close,
                        )
                    }
                }
                // The picture that has just landed, held over the mini player
                // while the rest of it fades in around the picture (LandedPicture).
                val landed = handoff.landed
                val landedAt = handoff.slot
                if (landed != null && landedAt != null && miniPlayerDrawn) {
                    LandedPicture(landed, landedAt, windowShape.wide, MINI_ARRIVE_MS) {
                        if (handoff.landed === landed) handoff.landed = null
                    }
                }

                AnimatedVisibility(visible = splash, exit = fadeOut(), modifier = Modifier.fillMaxSize()) { SplashContent() }
                // The app lock, over everything including the splash: what it
                // covers is the whole point of it.
                AnimatedVisibility(visible = locked == true, exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
                    LockScreen(authenticate = appViewModel::authenticate, onUnlocked = appViewModel::unlocked)
                }

                if ((currentTab != null || pushedChrome) && !splash && locked != true) {
                    // A wide window's message has no pill to ride above, so it
                    // docks to the bottom of the window instead, clear of the
                    // rail on the start edge. Without this the message was
                    // shown into a host that was never composed: it never
                    // appeared, and since nothing could dismiss it, it held the
                    // queue's lock and swallowed every message after it.
                    if (windowShape.wide) {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(Spacing.s8),
                            modifier = Modifier.align(Alignment.BottomCenter)
                                .padding(start = railInset, end = Spacing.s18 + miniCardBeside, bottom = Spacing.s18)
                                .widthIn(max = CHROME_MESSAGE_MAX_WIDTH),
                        ) {
                            selectionChrome.state?.let { chrome ->
                                SelectionSummaryTier(chrome, hazeState)
                            }
                            ChromeMessageHost(appSnackbar)
                        }
                    }
                    // On a wide window the rail and its spine occupy the same
                    // edge and swap: whichever is not showing has slid out.
                    //
                    // No start padding: the spine now carries its own s8
                    // gutter inside its touch target, so the target itself
                    // reaches the screen edge and a thumb coming in from
                    // off-screen lands on it. See NAV_RAIL_SPINE_TOUCH_WIDTH.
                    // A wide window's nav is a vertical rail on the start
                    // edge, which has no tier above it to dock to — so on the
                    // inner display this reports from the window's own bottom
                    // edge instead, capped at a readable width rather than
                    // stretched across 739dp. It is deliberately NOT inside the
                    // rail's AnimatedVisibility: the rail retracts after a few
                    // idle seconds, and a scan that is still running is exactly
                    // what someone who has stopped touching the screen wants to
                    // be able to see.
                    if (windowShape.wide && currentTab != null) {
                        BackgroundWorkTier(
                            backgroundWork,
                            hazeState,
                            Modifier.align(Alignment.BottomCenter)
                                .navigationBarsPadding()
                                .padding(start = Spacing.s18, end = Spacing.s18 + miniCardBeside, top = Spacing.s18, bottom = Spacing.s18)
                                .widthIn(max = BACKGROUND_WORK_MAX_WIDTH),
                            onOpen = { openUploadFolder(it.folderId) },
                        )
                    }
                    AnimatedVisibility(
                        visible = !railVisible && windowShape.wide && currentTab != null,
                        enter = fadeIn(),
                        exit = fadeOut(),
                        modifier = Modifier.align(Alignment.CenterStart),
                    ) {
                        NavRailSpine(
                            selected = currentTab,
                            dimmed = dimmedTabs,
                            dots = tabDots,
                            hazeState = hazeState,
                            onExpand = {
                                if (railHidden) appViewModel.setRailHidden(false) else railIdle = false
                                touches.tryEmit(Unit)
                            },
                        )
                    }
                    AnimatedVisibility(
                        // A phone's group also carries the message host, which
                        // Search needs after its selection has ended; the
                        // rail is the nav and nothing else.
                        visible = if (windowShape.wide) navVisible && pillHere else navVisible,
                        // The rail leaves by the start edge it lives on; the
                        // pill leaves by the bottom, which is where it already
                        // sits and the shortest way out of the way.
                        enter = if (windowShape.wide) slideInHorizontally { -it } + fadeIn() else slideInVertically { it } + fadeIn(),
                        exit = if (windowShape.wide) slideOutHorizontally { -it } + fadeOut() else slideOutVertically { it } + fadeOut(),
                        modifier = if (windowShape.wide) {
                            // The rail: s18 in from the start edge, centred on the height.
                            Modifier.align(Alignment.CenterStart).padding(start = Spacing.s18)
                        } else {
                            Modifier
                                .align(Alignment.BottomCenter)
                                .navigationBarsPadding()
                                .padding(bottom = Spacing.s8)
                        },
                    ) {
                        // On a wide window the rail and the control that pins
                        // it away are two objects, not one: the pill is four
                        // tabs and nothing else, and the circle beneath it is
                        // plainly a control — the same 44dp frosted circle the
                        // detail pane closes with. They slide as one group, so
                        // the edge never shows half a nav.
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Spacing.s8)) {
                            // The message capsule rides with the pill: one
                            // group, one clock. Phones only — the rail has
                            // no "above" to dock to.
                            if (!windowShape.wide) {
                                // What the app is doing on its own: the scan
                                // that is still filling the library, or the
                                // artwork pass behind it. Above the selection
                                // and the message because it is the longest
                                // lived of the three and should not shuffle
                                // when one of them appears. A wide window has
                                // no "above" — see the bottom-docked copy below.
                                if (currentTab != null) {
                                    BackgroundWorkTier(
                                        backgroundWork, hazeState, Modifier.fillMaxWidth().padding(horizontal = Spacing.s18),
                                        onOpen = { openUploadFolder(it.folderId) },
                                    )
                                }
                                // What is picked, and why a verb might be grey.
                                // The pill has no room for a sentence, so it
                                // rides directly above it in the same glass.
                                selectionChrome.state?.let { chrome ->
                                    SelectionSummaryTier(chrome, hazeState, Modifier.fillMaxWidth().padding(horizontal = Spacing.s18))
                                }
                                ChromeMessageHost(appSnackbar, Modifier.fillMaxWidth().padding(horizontal = Spacing.s18))
                                // Room for the mini player's bar, directly above
                                // the pill: the bar itself is drawn outside this
                                // group, because it stays when the pill slides
                                // away (see below), and this keeps the messages
                                // above it from landing on it.
                                AnimatedVisibility(
                                    visible = miniPlayerShown,
                                    enter = expandVertically(),
                                    exit = shrinkVertically(),
                                ) {
                                    Spacer(Modifier.height(MINI_BAR_HEIGHT))
                                }
                            }
                            if (pillHere) NavPill(
                                selected = currentTab,
                                onSelect = { tab ->
                                    if (backStack.lastOrNull() == tab.key) tabReselects.tryEmit(tab) else navigateToTab(tab)
                                },
                                hazeState = hazeState,
                                dimmed = dimmedTabs,
                                dots = tabDots,
                                vertical = windowShape.wide,
                                // BOTH shapes morph. The rail was left out of
                                // this at first, on the theory that a nav on the
                                // side edge never had to be taken over — but the
                                // screens had already stopped drawing their own
                                // bars, so that left a wide window with no way
                                // to act on a selection at all.
                                selection = selectionChrome.state,
                            )
                            if (windowShape.wide) {
                                // The circle under the rail is whichever control
                                // the moment calls for: normally "put the rail
                                // away", and while selecting the way OUT of the
                                // selection — which is also the phone's shape,
                                // where cancel is a circle beside the pill.
                                // Hiding the rail mid-selection would take the
                                // toolbar with it, so that control stands down.
                                val chrome = selectionChrome.state
                                if (chrome == null) {
                                    IconCircleButton(
                                        icon = painterResource(R.drawable.rg_ic_chevron_left),
                                        contentDescription = "Hide the rail",
                                        onClick = { appViewModel.setRailHidden(true) },
                                        size = 44.dp,
                                        iconSize = 20.dp,
                                        testTag = "nav_rail_hide_button",
                                    )
                                } else {
                                    IconCircleButton(
                                        icon = painterResource(R.drawable.rg_ic_close),
                                        contentDescription = "Cancel selection",
                                        onClick = chrome.onCancel,
                                        size = 44.dp,
                                        iconSize = 20.dp,
                                        testTag = "nav_selection_cancel",
                                    )
                                }
                            }
                        }
                    }
                }
                // The picture-in-picture window: the film and nothing else, over
                // everything else, which stays composed underneath for when the
                // window opens out again (PictureInPicture.kt).
                if (inPip) PictureInPictureFilm(miniPlayerPlayer, Modifier.fillMaxSize())
            }
        }
    }
}


/**
 * How wide the background-work tier may get on a wide window. The chrome is
 * a sentence and a bar; stretched across a 739dp inner display it would read
 * as a banner, and the eye would have to travel the whole width to learn one
 * number.
 */
private val BACKGROUND_WORK_MAX_WIDTH = 420.dp

/**
 * Whether the mini player shows over this screen once the player is put
 * away: not over Shorts, whose clips are a player of their own (it pauses the
 * film as it opens), and not in the full-screen flows — onboarding, adding a
 * server, the poster editor and Set as poster.
 */
private fun RegolithKey?.hostsMiniPlayer(): Boolean =
    this !is RegolithKey.Shorts && this !is RegolithKey.Onboarding &&
        this !is RegolithKey.AddServer && this !is RegolithKey.PosterEditor && this !is RegolithKey.SetPoster &&
        // The lightbox and a story are pictures on black, edge to edge: the film carries on underneath.
        this !is RegolithKey.Lightbox && this !is RegolithKey.Story

/**
 * How long the mini player takes to fade in, around a picture that has just
 * landed in it or on its own, and to fade out; also how long the landed
 * picture is held over it, and then takes to fade off it.
 */
private const val MINI_ARRIVE_MS = 120

/** How high a phone's bar sits while the pill shows: over the pill, a gap between. */
private val MINI_BAR_LIFT = NAV_PILL_HEIGHT + Spacing.s8
