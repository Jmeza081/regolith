package com.regolith.ui.player

import android.text.format.DateFormat
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import com.regolith.ui.theme.RegolithTheme
import com.regolith.ui.theme.TextStyles
import com.regolith.ui.util.msUntilNextMinute
import kotlinx.coroutines.delay
import java.util.Date

/**
 * The time of day, for the full-screen player.
 *
 * A film covers the status bar, so the one thing a phone always told you --
 * what time it is -- goes away exactly when you are most likely to wonder.
 * This puts it back, but only while the controls are up: it lives inside the
 * chrome's `AnimatedVisibility`, so it appears and fades with everything
 * else and needs no visibility logic of its own.
 *
 * The format is the DEVICE's, not ours: `DateFormat.getTimeFormat` follows
 * the 24-hour setting and the locale, so this reads 21:07 or 9:07 PM as the
 * phone does everywhere else. Recreated when the configuration changes,
 * because that is what a locale switch looks like to a composable.
 *
 * It ticks on the minute BOUNDARY rather than every 60 seconds (see
 * [msUntilNextMinute]), and only while it is composed -- controls hidden
 * means no coroutine running behind the picture.
 */
@Composable
internal fun PlayerClock(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    // LocalConfiguration is read so a locale or 12/24-hour change rebuilds
    // the formatter instead of leaving a stale one in place.
    val configuration = LocalConfiguration.current
    val formatter = remember(configuration) { DateFormat.getTimeFormat(context) }
    val now by produceState(initialValue = System.currentTimeMillis(), formatter) {
        while (true) {
            value = System.currentTimeMillis()
            delay(msUntilNextMinute(value))
        }
    }
    Text(
        text = formatter.format(Date(now)),
        style = TextStyles.buttonSmall,
        color = RegolithTheme.colors.body,
        modifier = modifier.testTag("player_clock"),
    )
}
