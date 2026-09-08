package com.regolith.ui.util

import java.util.Locale

/** "8.4 GB", "890 GB", "24.8 GB": the design's chip style, one decimal under 10. */
fun formatBytes(bytes: Long): String {
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var i = 0
    while (value >= 1000 && i < units.lastIndex) {
        value /= 1000
        i++
    }
    return if (i == 0) "$bytes B" else String.format(Locale.US, if (value < 10) "%.1f %s" else "%.0f %s", value, units[i])
}

/** "1h 07m", "42:18"-style clock for the player, "15m left" for rows. */
fun formatClock(ms: Long): String {
    val totalSec = (ms / 1000).coerceAtLeast(0)
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s) else String.format(Locale.US, "%d:%02d", m, s)
}

fun formatRemaining(positionMs: Long, durationMs: Long): String {
    val leftMin = ((durationMs - positionMs) / 60_000).coerceAtLeast(0)
    val h = leftMin / 60
    val m = leftMin % 60
    return if (h > 0) "${h}h ${String.format(Locale.US, "%02d", m)}m left" else "${m}m left"
}
