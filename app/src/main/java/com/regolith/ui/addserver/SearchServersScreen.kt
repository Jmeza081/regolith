package com.regolith.ui.addserver

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.regolith.R
import com.regolith.data.discovery.DiscoveredHost
import com.regolith.data.discovery.DiscoveryEvent
import com.regolith.data.discovery.HostDiscovery
import com.regolith.ui.components.CardStyle
import com.regolith.ui.components.DisplayText
import com.regolith.ui.components.MichromaLabel
import com.regolith.ui.components.PrimaryButton
import com.regolith.ui.components.SecondaryButton
import com.regolith.ui.components.Skeleton
import com.regolith.ui.components.SurfaceCard
import com.regolith.ui.components.TertiaryButton
import com.regolith.ui.components.TopBar
import com.regolith.ui.theme.RegolithTheme
import com.regolith.ui.theme.Spacing
import com.regolith.ui.theme.TextStyles
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SearchServersUiState(
    val searching: Boolean = true,
    val subnet: String = "",
    val checked: Int = 0,
    val total: Int = 0,
    val hosts: List<DiscoveredHost> = emptyList(),
    /** The sweep finished with nothing. */
    val nothingFound: Boolean = false,
)

/** Runs the sweep; "Stop searching" cancels it and keeps what was found. */
@HiltViewModel
class SearchServersViewModel @Inject constructor(private val discovery: HostDiscovery) : ViewModel() {
    private val _uiState = MutableStateFlow(SearchServersUiState())
    val uiState: StateFlow<SearchServersUiState> = _uiState
    private var job: Job? = null

    init {
        start()
    }

    fun start() {
        job?.cancel()
        _uiState.value = SearchServersUiState()
        job = viewModelScope.launch {
            discovery.discover().collect { event ->
                when (event) {
                    is DiscoveryEvent.Subnet -> _uiState.update { it.copy(subnet = event.cidr, total = event.total) }
                    is DiscoveryEvent.Checked -> _uiState.update { it.copy(checked = event.done) }
                    is DiscoveryEvent.Found -> _uiState.update { s -> if (s.hosts.any { it.host == event.host.host }) s else s.copy(hosts = s.hosts + event.host) }
                    DiscoveryEvent.Finished -> _uiState.update { it.copy(searching = false, nothingFound = it.hosts.isEmpty()) }
                }
            }
        }
    }

    fun stop() {
        job?.cancel()
        _uiState.update { it.copy(searching = false, nothingFound = it.hosts.isEmpty()) }
    }
}

/**
 * "Searching" and "Nothing found" (design section 03). Three rings leave
 * the centre on a staggered 2.2 s sweep while the server mark breathes a
 * red glow; results fill in beneath with a skeleton row holding the space
 * so the list never jumps. Nothing found is the one failure with no error
 * colour: retry is primary, manual entry stays underneath.
 */
@Composable
fun SearchServersScreen(
    viewModel: SearchServersViewModel,
    onBack: () -> Unit,
    onPick: (DiscoveredHost) -> Unit,
    onManual: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = RegolithTheme.colors
    Column(modifier.fillMaxSize().navigationBarsPadding().testTag("addserver_search_screen")) {
        TopBar(title = "Add source server", onBack = onBack)
        if (state.nothingFound) {
            Box(Modifier.fillMaxSize().padding(horizontal = Spacing.s18), contentAlignment = Alignment.Center) {
                SurfaceCard(style = CardStyle.Empty, contentPadding = PaddingValues(horizontal = Spacing.s18, vertical = Spacing.s30), modifier = Modifier.fillMaxWidth().testTag("addserver_nothing_found")) {
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Spacing.s12)) {
                        Box(Modifier.size(52.dp).background(colors.surface, RoundedCornerShape(16.dp)).border(1.dp, colors.hairline, RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) {
                            Icon(painterResource(R.drawable.rg_ic_server), contentDescription = null, tint = colors.ink, modifier = Modifier.size(18.dp))
                        }
                        DisplayText("Nothing found", style = TextStyles.dialogTitle, textAlign = TextAlign.Center)
                        Text(
                            "No SMB shares answered on this network. Check the server is awake and on the same Wi-Fi, or type its address.",
                            style = TextStyles.body, color = colors.metadata, textAlign = TextAlign.Center,
                        )
                        PrimaryButton(text = "Search again", onClick = viewModel::start, testTag = "addserver_search_again_button", modifier = Modifier.fillMaxWidth())
                        TertiaryButton(text = "Enter an address", onClick = onManual, testTag = "addserver_manual_button", modifier = Modifier.fillMaxWidth())
                    }
                }
            }
            return
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Spacing.s18), verticalArrangement = Arrangement.spacedBy(Spacing.s18)) {
            Rings(active = state.searching)
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Spacing.s8)) {
                DisplayText(if (state.searching) "Searching" else "Search stopped", style = TextStyles.dialogTitle, textAlign = TextAlign.Center)
                Text(
                    if (state.subnet.isEmpty()) "Looking for SMB shares on Wi-Fi" else "Looking for SMB shares on Wi-Fi · ${state.subnet}",
                    style = TextStyles.body.copy(fontSize = 13.sp, lineHeight = 19.sp), color = colors.metadata, textAlign = TextAlign.Center,
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.s8)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MichromaLabel("Found so far", Modifier.weight(1f))
                    Text(
                        if (state.total > 0) "${state.checked} of ${state.total} checked" else "",
                        style = TextStyles.meta12.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium), color = colors.metadata,
                    )
                }
                state.hosts.forEach { host ->
                    SurfaceCard(
                        contentPadding = PaddingValues(Spacing.s12),
                        modifier = Modifier.fillMaxWidth().clickable(interactionSource = null, indication = null) { onPick(host) }.testTag("addserver_host_${host.host.host}"),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(38.dp).background(colors.disabledBg, RoundedCornerShape(11.dp)), contentAlignment = Alignment.Center) {
                                Icon(painterResource(R.drawable.rg_ic_server), contentDescription = null, tint = colors.ink, modifier = Modifier.size(18.dp))
                            }
                            Spacer(Modifier.width(Spacing.s12))
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.s2)) {
                                Text(host.name, style = TextStyles.rowLabel, color = colors.ink)
                                Text(host.label, style = TextStyles.meta12, color = colors.metadata)
                            }
                            Icon(painterResource(R.drawable.rg_ic_chevron_right), contentDescription = null, tint = colors.metadata, modifier = Modifier.size(17.dp))
                        }
                    }
                }
                if (state.searching) {
                    Row(
                        Modifier.fillMaxWidth().background(colors.ground, RoundedCornerShape(14.dp)).border(1.dp, colors.skeleton, RoundedCornerShape(14.dp)).padding(Spacing.s12),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Skeleton(Modifier.size(38.dp), shape = RoundedCornerShape(11.dp))
                        Spacer(Modifier.width(Spacing.s12))
                        Column(verticalArrangement = Arrangement.spacedBy(Spacing.s4)) {
                            Skeleton(Modifier.size(96.dp, 9.dp), shape = RoundedCornerShape(4.dp))
                            Skeleton(Modifier.size(132.dp, 8.dp), shape = RoundedCornerShape(4.dp))
                        }
                    }
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.s12)) {
                if (state.searching) {
                    SecondaryButton(text = "Stop searching", onClick = viewModel::stop, testTag = "addserver_stop_button", modifier = Modifier.fillMaxWidth())
                } else {
                    SecondaryButton(text = "Search again", onClick = viewModel::start, testTag = "addserver_search_again_button", modifier = Modifier.fillMaxWidth())
                }
                TertiaryButton(text = "Enter an address", onClick = onManual, testTag = "addserver_manual_button", modifier = Modifier.fillMaxWidth())
            }
            Spacer(Modifier.height(Spacing.s18))
        }
    }
}

/**
 * The three rings (188, 140, 92) with a 1.5dp red hairline at 50% and a
 * soft red glow, leaving the centre on a staggered 2.2 s sweep; the mark
 * in the middle only breathes.
 */
@Composable
private fun Rings(active: Boolean) {
    val colors = RegolithTheme.colors
    val transition = rememberInfiniteTransition(label = "rings")
    val t by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(2200, easing = LinearEasing)), label = "t")
    val breathe by transition.animateFloat(0.5f, 1f, infiniteRepeatable(tween(1100, easing = LinearEasing), androidx.compose.animation.core.RepeatMode.Reverse), label = "breathe")
    Box(Modifier.fillMaxWidth().height(150.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(188.dp)) {
            val ringColor = Color(0x80E11B17)
            val stroke = Stroke(width = 1.5.dp.toPx())
            listOf(92f, 140f, 188f).forEachIndexed { i, d ->
                val phase = if (active) ((t + i / 3f) % 1f) else 0.5f
                val scale = if (active) 0.34f + phase * 0.72f else 1f
                val alpha = if (active) (if (phase < 0.12f) phase / 0.12f else 1f - (phase - 0.12f) / 0.88f).coerceIn(0f, 1f) else 0.5f
                val r = d.dp.toPx() / 2 * scale
                drawCircle(ringColor.copy(alpha = ringColor.alpha * alpha), radius = r, style = stroke)
                drawCircle(Color(0x4DE11B17).copy(alpha = 0.3f * alpha), radius = r + 4.dp.toPx(), style = Stroke(width = 8.dp.toPx()))
            }
        }
        Box(Modifier.size(52.dp).alpha(if (active) breathe else 1f).background(Color(0x1FE11B17), androidx.compose.foundation.shape.CircleShape), contentAlignment = Alignment.Center) {
            Icon(painterResource(R.drawable.rg_ic_server), contentDescription = null, tint = colors.ink, modifier = Modifier.size(18.dp))
        }
    }
}
