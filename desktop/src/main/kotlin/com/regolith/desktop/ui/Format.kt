package com.regolith.desktop.ui

import java.util.Locale

/** `4:05`, or `1:02:15` past an hour. Negative and unknown read as `0:00`. */
fun formatClock(ms: Long): String {
    if (ms <= 0 || ms >= Long.MAX_VALUE / 4) return "0:00"
    val s = ms / 1000
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, sec) else String.format(Locale.US, "%d:%02d", m, sec)
}

/** A start time as the Start field shows it: `12:30`, or `12:30.5` when it is not on a whole second. */
fun formatStart(ms: Long): String {
    val tenth = (ms.coerceAtLeast(0) % 1000) / 100
    return formatClock(ms) + if (tenth > 0) ".$tenth" else ""
}

/** `Films/Heat` for `Films` + `Heat`; the share root has no prefix. */
fun childPath(folder: String, name: String): String = if (folder.isEmpty()) name else "$folder/$name"
