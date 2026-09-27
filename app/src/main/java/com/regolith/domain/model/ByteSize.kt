package com.regolith.domain.model

import java.util.Locale

/**
 * "8.4 GB", "890 GB", "24.8 GB": the design's chip style, one decimal under
 * 10, in decimal (1000-based) units the way drives and shares report size.
 *
 * In `domain/` rather than beside the UI's other formatters because the
 * background workers write sentences too — a notification saying a share
 * "needs 1.2 GB more" must use the same units as the row that says it in
 * the app. `ui/util/formatBytes` is this function under its old name.
 */
fun formatByteSize(bytes: Long): String {
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var i = 0
    while (value >= 1000 && i < units.lastIndex) {
        value /= 1000
        i++
    }
    return if (i == 0) "$bytes B" else String.format(Locale.US, if (value < 10) "%.1f %s" else "%.0f %s", value, units[i])
}
