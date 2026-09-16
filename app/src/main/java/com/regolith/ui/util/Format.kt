package com.regolith.ui.util

import java.text.SimpleDateFormat
import java.util.Date
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

/**
 * Milliseconds from [nowMs] until the next whole minute.
 *
 * A wall clock that ticks on a one-minute timer drifts: it would show 8:04
 * while the phone says 8:05, for up to a minute. Waiting for the BOUNDARY
 * instead means the display changes when the minute does. Pure, so the
 * arithmetic is tested without waiting a minute for it.
 */
fun msUntilNextMinute(nowMs: Long): Long = 60_000L - Math.floorMod(nowMs, 60_000L)

/** "1h 07m", "42:18"-style clock for the player, "15m left" for rows. */
fun formatClock(ms: Long): String {
    val totalSec = (ms / 1000).coerceAtLeast(0)
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s) else String.format(Locale.US, "%d:%02d", m, s)
}

fun formatRemaining(positionMs: Long, durationMs: Long): String {
    val leftMs = (durationMs - positionMs).coerceAtLeast(0)
    val leftMin = leftMs / 60_000
    val h = leftMin / 60
    val m = leftMin % 60
    return when {
        h > 0 -> "${h}h ${String.format(Locale.US, "%02d", m)}m left"
        leftMin == 0L -> "Under a minute left"
        else -> "${m}m left"
    }
}

/** "1h 56m", "11m 04s": runtimes on chips and rows. */
fun formatDurationShort(ms: Long): String {
    val totalSec = (ms / 1000).coerceAtLeast(0)
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return when {
        h > 0 -> "${h}h ${String.format(Locale.US, "%02d", m)}m"
        m > 0 -> "${m}m ${String.format(Locale.US, "%02d", s)}s"
        else -> "${s}s"
    }
}

/** "1.0×", "1.25×", "2.0×": the speed pill. */
fun formatSpeed(speed: Float): String {
    val s = String.format(Locale.US, "%.2f", speed).trimEnd('0')
    return (if (s.endsWith('.')) s + "0" else s) + "×"
}

/** "2 Feb 2026": the Modified row on Title Detail. */
fun formatDate(epochMs: Long): String = SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date(epochMs))

/**
 * "last night", "Tuesday", "2 Feb": when something was watched, the way
 * the design's resume row says it. [nowMs] is a parameter so it is testable.
 */
fun formatWhen(thenMs: Long, nowMs: Long = System.currentTimeMillis()): String {
    val cal = java.util.Calendar.getInstance()
    cal.timeInMillis = nowMs
    cal.set(java.util.Calendar.HOUR_OF_DAY, 0); cal.set(java.util.Calendar.MINUTE, 0); cal.set(java.util.Calendar.SECOND, 0); cal.set(java.util.Calendar.MILLISECOND, 0)
    val startOfToday = cal.timeInMillis
    val day = 24 * 3_600_000L
    return when {
        thenMs >= startOfToday -> "today"
        thenMs >= startOfToday - day -> "last night"
        thenMs >= startOfToday - 6 * day -> SimpleDateFormat("EEEE", Locale.getDefault()).format(Date(thenMs))
        else -> SimpleDateFormat("d MMM", Locale.getDefault()).format(Date(thenMs))
    }
}

/** "1 file", "3 files": counts read as counts, not as a template that forgot to check. */
fun formatFileCount(count: Int): String = "$count file" + (if (count == 1) "" else "s")

/** "1 folder", "2 folders". */
fun formatFolderCount(count: Int): String = "$count folder" + (if (count == 1) "" else "s")
