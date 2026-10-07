package com.regolith.ui.components

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.SharedTransitionScope.ResizeMode.Companion.RemeasureToBounds
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import androidx.navigation3.runtime.NavEntryDecorator
import androidx.navigation3.ui.LocalNavAnimatedContentScope
import com.regolith.domain.artwork.ArtworkOwner
import com.regolith.domain.artwork.ArtworkRequest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt

/*
 * Posters that fly: a tile's picture travels to where the page it opens
 * shows the same picture, instead of the page sliding in over it. A video's
 * 2:3 poster grows into the title page's wide picture, uncropping as it goes;
 * a collection's poster moves and grows into the poster on its profile. Back
 * flies it home again, and on a phone the Back swipe holds it under your
 * thumb (Navigation 3 seeks the same transition).
 *
 * Built on Compose's shared elements: both ends mark their picture with the
 * same key ([posterFlight]), and when a screen change takes one away
 * and brings the other, Compose animates one into the other. Web analogy: the
 * View Transitions API, with `view-transition-name` set to the picture's owner.
 * The key is the picture's [ArtworkOwner], because that is what makes two
 * pictures "the same": every picture of a file is cut from one source image
 * (`ArtworkStore.save`), so the wide one cropped to 2:3 is the poster.
 */

/**
 * The flights between the screens under [PosterFlightLayout]: the
 * [SharedTransitionScope] they fly in, and the one flight made by hand. Null
 * outside it (previews, tests), where nothing flies and every tile is an
 * ordinary picture.
 *
 * The flight by hand is the one into a page beside the wall on a wide window
 * (`WallScene`). There the tile never leaves the screen — the wall it is on
 * stays, at a new width — so nothing is taken away for Compose to fly from:
 * the tile reports where it was when tapped ([rememberFlightLaunchPad]), the
 * page reports where its picture goes ([rememberFlightLanding]), and the
 * layout draws the picture between the two over everything.
 */
@Stable
class PosterFlights internal constructor(internal val scope: SharedTransitionScope) {
    /** The tiles tapped, for a page beside the wall to claim. */
    internal val launches = FlightLaunches()

    /** The flight by hand that is in the air, drawn by [PosterFlightLayout]. */
    internal var flight: Flight? by mutableStateOf(null)

    /**
     * True for a moment after a tile was tapped: the page arriving beside the
     * wall then fades in under the poster instead of sliding in.
     */
    val launching: Boolean get() = launches.launching
}

/** A tapped tile: whose picture, where it was on screen ([from], in the window), the picture it showed, and when. */
internal class Launch(val owner: ArtworkOwner, val from: Rect, val poster: ArtworkRequest?, val atNanos: Long)

/**
 * The last tile tapped, until a page claims it or it goes stale: a page that
 * opens more than half a second later was not opened by that tap. Not state:
 * nothing redraws because a tile was tapped. [now] is the clock, for tests.
 */
internal class FlightLaunches(private val now: () -> Long = System::nanoTime) {
    private var last: Launch? = null

    fun launched(owner: ArtworkOwner, from: Rect, poster: ArtworkRequest?) {
        last = Launch(owner, from, poster, now())
    }

    /** A tap has just happened that a page may yet claim. */
    val launching: Boolean get() = last?.fresh() == true

    /** The fresh launch of [owner]'s picture, taken so that no other page can fly it. */
    fun claim(owner: ArtworkOwner): Launch? = last?.takeIf { it.owner == owner && it.fresh() }?.also { last = null }

    private fun Launch.fresh(): Boolean = now() - atNanos < LAUNCH_FRESH_NANOS
}

/**
 * A picture flying by hand from [from] to wherever its page reports ([to]):
 * [poster] (the tile's picture) cross-fading into [picture] as it goes.
 */
@Stable
internal class Flight(val owner: ArtworkOwner, val from: Rect, val poster: ArtworkRequest?, val picture: ArtworkRequest?, val placeholder: ArtworkRequest?) {
    var to: Rect? by mutableStateOf(null)

    /** True once it has arrived: the page draws its own picture from then on. */
    var landed by mutableStateOf(false)
}

val LocalPosterFlights = staticCompositionLocalOf<PosterFlights?> { null }

/**
 * The screen a picture is on, as the transition that brings it and takes it
 * away: what a flight is timed by. Null outside NavDisplay's screens, where
 * Navigation 3's own local would throw rather than answer.
 */
private val LocalFlightScreen = staticCompositionLocalOf<AnimatedVisibilityScope?> { null }

/**
 * Hands every screen's transition to the pictures on it ([posterFlight]).
 * One of NavDisplay's entry decorators: wrappers Navigation 3 puts around each
 * screen, the way the saved-state and ViewModel ones are.
 */
@Composable
fun <T : Any> rememberPosterFlightDecorator(): NavEntryDecorator<T> = remember {
    NavEntryDecorator { entry ->
        CompositionLocalProvider(LocalFlightScreen provides LocalNavAnimatedContentScope.current) { entry.Content() }
    }
}

/**
 * Where posters can fly: a Box around the app's NavDisplay (and the nav
 * chrome beside it, which a poster in the air passes over), so a picture can
 * leave one screen and land on the next.
 *
 * Deliberately NOT handed to NavDisplay as its `sharedTransitionScope`. Given
 * one, Navigation 3 makes every screen a shared element of its own, so a wall
 * that changes width as a page opens beside it would stretch from one layout
 * to the other instead of dissolving and reforming as `WallScene` makes it.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun PosterFlightLayout(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    SharedTransitionLayout(modifier) {
        val flights = remember(this) { PosterFlights(this) }
        CompositionLocalProvider(LocalPosterFlights provides flights) {
            Box(Modifier.fillMaxSize()) {
                content()
                flights.flight?.let { FlightByHand(it, onDone = { if (flights.flight === it) flights.flight = null }) }
            }
        }
    }
}

/**
 * The modifier that makes a picture one end of a flight: a tile's art, or the
 * spot on a page where the same picture lands. Pictures of the same [owner]
 * on the screen being left and the screen arriving fly into each other; with
 * no partner on the other screen it does nothing, and so does a null [owner]
 * (a tile that opens nothing a picture could land on).
 *
 * Apply it to the box the picture fills, outside any clip, so the flight's
 * bounds are the picture's own. Each end's content is measured again at every
 * size on the way ([RemeasureToBounds]), which is what lets a wide picture
 * uncrop as its box widens, and the two cross-fade.
 *
 * Only on a screen under [PosterFlightLayout], with [rememberPosterFlightDecorator]
 * among NavDisplay's decorators; anywhere else it is an empty modifier.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.posterFlight(owner: ArtworkOwner?): Modifier {
    val flights = LocalPosterFlights.current
    val screen = LocalFlightScreen.current
    if (owner == null || flights == null || screen == null) return this
    return with(flights.scope) {
        this@posterFlight.sharedBounds(
            sharedContentState = rememberSharedContentState(owner),
            animatedVisibilityScope = screen,
            enter = fadeIn(tween(FLIGHT_FADE_MS)),
            exit = fadeOut(tween(FLIGHT_FADE_MS)),
            boundsTransform = FlightBounds,
            resizeMode = RemeasureToBounds,
        )
    }
}

/**
 * How long a poster is in the air. Longer than a push (220ms): the eye has
 * to follow one picture across the screen, where a push only has to read as
 * "you moved". Shared with the page's own fade, so the two land together.
 */
const val FLIGHT_MS = 380

/** The two ends' pictures cross-fade over the first part of the flight. */
private const val FLIGHT_FADE_MS = 220

/**
 * Fast out of the tile, settling into its place: Material's emphasized
 * decelerate, the curve the canvas drew the flights with.
 */
val FlightEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

@OptIn(ExperimentalSharedTransitionApi::class)
private val FlightBounds = BoundsTransform { _, _ -> tween(FLIGHT_MS, easing = FlightEasing) }

/**
 * Where a tile was when it was tapped, for a flight made by hand
 * ([PosterFlights]). Put [modifier] on the tile's picture, call [launch] as
 * it is tapped, and read [hidden] in a draw-phase lambda (a graphicsLayer):
 * the tile's own picture steps aside while a copy of it is in the air.
 */
@Stable
class FlightLaunchPad internal constructor(
    private val flights: PosterFlights,
    private val owner: ArtworkOwner,
    private val poster: ArtworkRequest?,
) {
    private var coordinates: LayoutCoordinates? = null

    /** Keeps the picture's place on screen; reads nothing, so it costs a field write per layout. */
    val modifier: Modifier = Modifier.onPlaced { coordinates = it }

    fun launch() {
        val at = coordinates?.takeIf { it.isAttached } ?: return
        flights.launches.launched(owner, at.boundsInRoot(), poster)
    }

    /** True while this tile's picture is flying by hand. */
    val hidden: Boolean get() = flights.flight?.let { it.owner == owner && !it.landed } == true
}

/** A [FlightLaunchPad] for a tile showing [poster], the picture of [owner]; null where nothing can fly. */
@Composable
fun rememberFlightLaunchPad(owner: ArtworkOwner?, poster: ArtworkRequest?): FlightLaunchPad? {
    val flights = LocalPosterFlights.current ?: return null
    if (owner == null) return null
    return remember(flights, owner, poster) { FlightLaunchPad(flights, owner, poster) }
}

/**
 * The page's end of a flight made by hand: the spot its picture lands on.
 * Claims the tile tapped a moment ago if it showed [owner]'s picture, and
 * flies [picture] (with [placeholder] while it loads) there from the tile.
 * Put [modifier] on the box the picture fills: it reports where that is,
 * and keeps it empty until the flight has landed.
 *
 * Only for a page beside a wall ([enabled]); everywhere else Compose's
 * shared elements fly the poster ([posterFlight]).
 */
@Stable
class FlightLanding internal constructor(internal val flight: Flight?) {
    val modifier: Modifier = if (flight == null) {
        Modifier
    } else {
        Modifier
            .onGloballyPositioned { flight.to = it.boundsInRoot() }
            .graphicsLayer { alpha = if (flight.landed) 1f else 0f }
    }
}

/**
 * A [FlightLanding] for the picture of [owner] on a page, landing [picture]
 * (with [placeholder] while it loads); a plain one, with nothing flying in,
 * unless [enabled] and a tile showing that picture was tapped a moment ago.
 */
@Composable
fun rememberFlightLanding(
    owner: ArtworkOwner,
    picture: ArtworkRequest?,
    placeholder: ArtworkRequest?,
    enabled: Boolean,
): FlightLanding {
    val flights = LocalPosterFlights.current
    val landing = remember(flights, owner, enabled) {
        val launch = if (enabled) flights?.launches?.claim(owner) else null
        FlightLanding(launch?.let { Flight(owner, it.from, it.poster, picture, placeholder) })
    }
    // Into the air once this page is composed, not while it is: the layout
    // that draws the flight has already been composed this frame.
    LaunchedEffect(landing) { landing.flight?.let { flights?.flight = it } }
    return landing
}

/**
 * The flight made by hand, drawn over everything: a box from the tile's
 * place to the page's, its corners straightening from the tile's 12dp, the
 * tile's picture fading into the page's wide one, which uncrops as the box
 * widens (both are cropped to the box as it goes). Then the page's own
 * picture takes over and this fades away.
 */
@Composable
private fun FlightByHand(flight: Flight, onDone: () -> Unit) {
    val progress = remember(flight) { Animatable(0f) }
    val leave = remember(flight) { Animatable(1f) }
    var origin by remember { mutableStateOf(Offset.Zero) }
    LaunchedEffect(flight) {
        // The page's picture has to have been placed before it can be flown
        // to; a page that never places it gets its picture back regardless.
        val placed = withTimeoutOrNull(LANDING_WAIT_MS) { snapshotFlow { flight.to }.first { it != null } }
        if (placed != null) progress.animateTo(1f, tween(FLIGHT_MS, easing = FlightEasing))
        flight.landed = true
        leave.animateTo(0f, tween(FLIGHT_HANDOVER_MS))
        onDone()
    }
    // Cut short (another tile tapped mid-flight), it still lands: a page
    // waiting on it would otherwise keep its picture hidden for good.
    DisposableEffect(flight) { onDispose { flight.landed = true } }
    val to = flight.to ?: return
    val t = progress.value
    val box = lerp(flight.from, to, t).translate(-origin)
    val density = LocalDensity.current
    val corner = with(density) { lerp(TILE_CORNER.toPx(), 0f, t).toDp() }
    Box(
        Modifier
            .fillMaxSize()
            .onPlaced { origin = it.positionInRoot() },
    ) {
        Box(
            Modifier
                .offset { IntOffset(box.left.roundToInt(), box.top.roundToInt()) }
                .size(with(density) { box.width.toDp() }, with(density) { box.height.toDp() })
                .graphicsLayer { alpha = leave.value }
                .clip(RoundedCornerShape(corner))
                .testTag("poster_flight"),
        ) {
            ArtworkImage(flight.picture, Modifier.fillMaxSize(), placeholder = flight.placeholder)
            // The tile's own picture on top, gone by the time the box has
            // grown enough for the wide one to read as the same picture.
            if (flight.poster != null) {
                ArtworkImage(flight.poster, Modifier.fillMaxSize().graphicsLayer { alpha = 1f - (t / POSTER_FADE_END).coerceAtMost(1f) })
            }
        }
    }
}

/** How long a tap stays a launch: a page that opens later was opened by something else. */
private const val LAUNCH_FRESH_NANOS = 500_000_000L

/** The longest a flight waits for its page to place the picture it lands on. */
private const val LANDING_WAIT_MS = 300L

/** The page's picture and the flown one overlap this long as one hands over to the other. */
private const val FLIGHT_HANDOVER_MS = 120

/** The tile's picture has faded out by this much of the flight. */
private const val POSTER_FADE_END = 0.6f

/** A tile's corner radius ([com.regolith.ui.theme.TileShape]), which a flight straightens out. */
private val TILE_CORNER = 12.dp
