package com.regolith

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.regolith.ui.navigation.RegolithNavGraph
import com.regolith.ui.theme.RegolithTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * The single Activity. Android needs at least one "window" to draw into; in
 * a Compose app that is this class and nothing else. Every screen is a
 * composable inside [RegolithNavGraph].
 *
 * Lifecycle note for web devs: Android destroys and recreates this object on
 * rotation ("configuration change"). Anything that must survive that lives in
 * a ViewModel, never in the Activity.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val appViewModel: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        // Keep the system splash (black + wedge) up until we know whether to
        // start on onboarding or Home. Design: two seconds at most.
        val splash = installSplashScreen()
        splash.setKeepOnScreenCondition { appViewModel.startDestination.value == null }

        super.onCreate(savedInstanceState)
        // Draw under the status and navigation bars; the design runs content
        // beneath the floating nav pill with no hard edges.
        enableEdgeToEdge()

        setContent {
            RegolithTheme {
                RegolithNavGraph(appViewModel = appViewModel)
            }
        }
    }
}
