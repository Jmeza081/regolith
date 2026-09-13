package com.regolith.ui.lock

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import com.regolith.R
import com.regolith.domain.security.AuthResult
import com.regolith.ui.components.DisplayText
import com.regolith.ui.components.PrimaryButton
import com.regolith.ui.theme.RegolithTheme
import com.regolith.ui.theme.Spacing
import com.regolith.ui.theme.TextStyles
import kotlinx.coroutines.launch

/**
 * The app lock's front door: the splash, held shut.
 *
 * It deliberately looks like the screen Regolith opens on rather than like
 * an error — nothing has gone wrong, the app is simply closed — and it
 * carries no content at all, because anything it showed would be the thing
 * the lock exists to hide.
 *
 * The prompt is asked for once on arrival. Dismissing it says nothing (the
 * user chose to close it) and leaves the Unlock button to ask again; a real
 * failure says what the system said. Back is swallowed: the way out is the
 * prompt, or leaving.
 */
@Composable
fun LockScreen(
    authenticate: suspend (Activity) -> AuthResult,
    onUnlocked: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RegolithTheme.colors
    val activity = LocalActivity.current
    val scope = rememberCoroutineScope()
    var asking by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val ask = {
        val host = activity
        if (host != null && !asking) {
            asking = true
            scope.launch {
                when (val result = authenticate(host)) {
                    is AuthResult.Success -> onUnlocked()
                    // Dismissed on purpose: no scolding, just the button.
                    is AuthResult.Cancelled -> error = null
                    is AuthResult.Error -> error = result.message.ifBlank { "Could not check that." }
                }
                asking = false
            }
        }
    }

    // Asked for as the screen arrives, so an unlock is one touch and no taps.
    LaunchedEffect(Unit) { ask() }
    BackHandler(enabled = true) { /* the lock is not a screen you can go back from */ }

    Box(modifier.fillMaxSize().background(Color.Black).testTag("lock_screen")) {
        Image(
            painterResource(R.drawable.rg_splash_moon), contentDescription = null,
            contentScale = androidx.compose.ui.layout.ContentScale.Crop, modifier = Modifier.fillMaxSize(),
        )
        Box(Modifier.fillMaxSize().background(Brush.radialGradient(listOf(Color(0x8C000000), Color(0xCC000000), Color.Black), radius = 900f)))
        Column(
            Modifier.align(Alignment.Center).padding(horizontal = Spacing.s30),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.s18),
        ) {
            Image(painterResource(R.drawable.rg_wedge_white), contentDescription = null, modifier = Modifier.size(58.dp, 78.dp))
            DisplayText("Regolith", style = TextStyles.wordmark.copy(letterSpacing = 0.04.em))
            Text(
                error ?: "Locked",
                style = TextStyles.body,
                color = if (error != null) colors.accent else colors.body,
                textAlign = TextAlign.Center,
                modifier = Modifier.testTag("lock_message"),
            )
            PrimaryButton(
                text = "Unlock",
                onClick = ask,
                testTag = "lock_unlock_button",
                modifier = Modifier.width(200.dp),
                loading = asking,
            )
        }
    }
}
