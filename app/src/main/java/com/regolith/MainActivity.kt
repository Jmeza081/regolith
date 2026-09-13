package com.regolith

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.WindowManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.regolith.ui.navigation.RegolithNavGraph
import com.regolith.ui.theme.RegolithTheme
import dagger.hilt.android.AndroidEntryPoint
import com.regolith.data.transfer.TransferQueueWorker

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
        // Both answers are read from preferences; neither may be guessed at,
        // or the library flashes up for a frame before the lock lands on it.
        splash.setKeepOnScreenCondition { appViewModel.startDestination.value == null || appViewModel.locked.value == null }

        super.onCreate(savedInstanceState)
        handleViewIntent(intent)
        handleDownloadsIntent(intent)
        // Draw under the status and navigation bars; the design runs content
        // beneath the floating nav pill with no hard edges.
        enableEdgeToEdge()
        watchForBackgrounding()

        setContent {
            RegolithTheme {
                RegolithNavGraph(appViewModel = appViewModel)
            }
        }
    }

    /**
     * The app lock's clock and its blindfold.
     *
     * ON_STOP/ON_START is a true "the app went away and came back" here
     * because there is exactly one Activity, and the manifest turns
     * rotation and folding into a recompose rather than a restart — so this
     * never fires for a turn of the phone.
     *
     * FLAG_SECURE goes on at ON_PAUSE, which is before the system takes the
     * thumbnail it shows in the app switcher: without it the lock hides the
     * library from whoever is holding the phone while the recents card
     * shows them what was on screen anyway. It comes off at ON_RESUME, so
     * an ordinary screenshot of a film still works.
     */
    private fun watchForBackgrounding() {
        lifecycle.addObserver(
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_PAUSE -> if (appViewModel.appLockEnabled.value) {
                        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    }
                    Lifecycle.Event.ON_STOP -> appViewModel.wentToBackground()
                    Lifecycle.Event.ON_START -> appViewModel.cameToForeground()
                    Lifecycle.Event.ON_RESUME -> window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    else -> Unit
                }
            },
        )
    }

    /** Regolith is already running and something else handed it a film. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleViewIntent(intent)
        handleDownloadsIntent(intent)
    }

    /**
     * The download notification was tapped: go to where downloads live.
     *
     * Handed to the ViewModel for the same reason as the VIEW intent below —
     * a rotation brings this Activity back with the same intent attached,
     * and acting on it here would yank the user to the downloads list every
     * time they turned the phone.
     */
    private fun handleDownloadsIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(TransferQueueWorker.EXTRA_OPEN_DOWNLOADS, false) != true) return
        intent.removeExtra(TransferQueueWorker.EXTRA_OPEN_DOWNLOADS)
        appViewModel.requestDownloads()
    }

    /**
     * "Open with Regolith". The URI is handed to the ViewModel rather than
     * acted on here: this object is destroyed and recreated on every
     * rotation, with the same intent still attached, so handling it here
     * would reopen the player each time the device turned.
     */
    private fun handleViewIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val uri = intent.data ?: return
        appViewModel.openExternal(uri.toString(), displayNameOf(uri))
    }

    /**
     * What to call a film we know nothing else about. A content provider
     * usually states a display name; a file:// URI has only its last path
     * segment, and some providers give neither.
     */
    private fun displayNameOf(uri: Uri): String {
        if (uri.scheme == "content") {
            runCatching {
                contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst() && !cursor.isNull(0)) return cursor.getString(0).substringBeforeLast('.')
                }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/')?.substringBeforeLast('.').orEmpty().ifEmpty { "Video" }
    }
}
