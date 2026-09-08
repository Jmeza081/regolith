package com.regolith.ui.player

import android.app.Activity
import android.media.AudioManager
import android.provider.Settings
import kotlin.math.roundToInt

/**
 * Screen brightness and media volume for the drag gestures. Brightness is
 * a per-window override (it does not change the system setting and goes
 * back to normal when the player leaves); volume is the real media stream.
 */
class PlayerSystemControls(private val activity: Activity?) {
    private val audio = activity?.getSystemService(AudioManager::class.java)

    fun brightness(): Float {
        val act = activity ?: return 0.5f
        val override = act.window.attributes.screenBrightness
        if (override >= 0f) return override
        val system = runCatching { Settings.System.getInt(act.contentResolver, Settings.System.SCREEN_BRIGHTNESS) }.getOrDefault(128)
        return system / 255f
    }

    fun setBrightness(fraction: Float) {
        val window = activity?.window ?: return
        window.attributes = window.attributes.apply { screenBrightness = fraction.coerceIn(0.01f, 1f) }
    }

    fun resetBrightness() {
        val window = activity?.window ?: return
        window.attributes = window.attributes.apply { screenBrightness = -1f }
    }

    fun volume(): Float {
        val a = audio ?: return 0.5f
        val max = a.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        return a.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max
    }

    fun setVolume(fraction: Float) {
        val a = audio ?: return
        val max = a.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        a.setStreamVolume(AudioManager.STREAM_MUSIC, (fraction.coerceIn(0f, 1f) * max).roundToInt(), 0)
    }
}
