package com.regolith.ui.settings

import com.regolith.data.artwork.PrefetchStatus
import com.regolith.domain.playback.UserChapterStats
import com.regolith.domain.security.BiometricAvailability
import com.regolith.domain.media.ShortsLength
import com.regolith.domain.security.LockAfter
import com.regolith.domain.transfer.QueueProgress
import com.regolith.ui.util.formatBytes

/** One server row under SHARES: "TOWER · SHOWING · 2.4 TB free", "STUDIO · out of reach". */
data class ServerRow(
    val serverId: Long,
    val name: String,
    /** "Scanning · 312 files", "2.4 TB free", "out of reach", "idle". */
    val status: String,
    val scanning: Boolean,
    /** White dot when reachable, grey when out of reach. */
    val reachable: Boolean = true,
    /** "SHOWING": its media is what Library lists. */
    val showing: Boolean = false,
    /**
     * "192.168.4.73", or "tower.local:1445" off the usual port. Blank for
     * the demo library, which has no address to show.
     *
     * The row used to identify a server by its name alone, which worked
     * only because the name WAS the address. Now that the name can be
     * anything, the address has to be said somewhere or two nicknamed NAS
     * boxes become indistinguishable.
     */
    val host: String = "",
) {
    val testTag get() = "settings_server_$serverId"

    /** The second line: where it is, then how it is. */
    val meta: String get() = if (host.isBlank()) status else "$host · $status"
}

/**
 * Everything the Settings screen needs to draw, as one immutable value.
 * The ViewModel owns a single StateFlow<SettingsUiState>; the screen only reads.
 */
data class SettingsUiState(
    val title: String = "Settings",
    val servers: List<ServerRow> = emptyList(),
    /** Server the "Disconnect X?" confirm is open for. */
    val confirmDisconnect: ServerRow? = null,
    /** Server the rename dialog is open for. */
    val renaming: ServerRow? = null,
    val hardwareDecoding: Boolean = true,
    val scrubThumbnails: Boolean = true,
    val autoplayNext: Boolean = true,
    val autoplayImmediately: Boolean = false,
    /** Wide windows only: whether the nav rail retracts after three idle seconds. */
    val autoHideRail: Boolean = true,
    val ambientLight: Boolean = true,
    /** Whether tiles play a few seconds of the film instead of showing a still. */
    /** Images cached on the device, excluding placeholders. */
    val artworkCount: Int = 0,
    val artworkBytes: Long = 0,
    val clearing: Boolean = false,
    /** The background artwork walk, so the app says what the notification says. */
    val prefetch: PrefetchStatus = PrefetchStatus(),
    /** The download queue, so Settings says what the notification says. */
    val downloads: DownloadsStatus = DownloadsStatus(),
    /** Chapters the user wrote, across every film (P9). */
    val userChapters: UserChapterStats = UserChapterStats(),
    /** The "Clear chapters?" confirm is open. */
    val confirmClearChapters: Boolean = false,
    /** One switch per enabled share: does Done write a chapter file beside each film there (P10). */
    val shareWrites: List<ShareWriteRow> = emptyList(),
    // --- Privacy: the app lock (P11).
    /** Settings › Shorts: how long a clip may be and still reach the feed. */
    val shortsLength: ShortsLength = ShortsLength.DEFAULT,
    val appLock: Boolean = false,
    val appLockAfter: LockAfter = LockAfter.DEFAULT,
    /** What the device can do about biometrics; the switch is only usable when it is ready. */
    val biometrics: BiometricAvailability = BiometricAvailability.UNAVAILABLE,
    // --- Demo library (BuildConfig.DEMO_LIBRARY builds only).
    /** True while the demo server exists; the row offers the opposite action. */
    val demoInstalled: Boolean = false,
    /** True while it is being written or removed: both take a second or two. */
    val demoWorking: Boolean = false,
    /** What the demo's clips occupy on the device. */
    val demoBytes: Long = 0,
)

/** "Write to media on TOWER": one enabled share and whether chapter files go to it. */
data class ShareWriteRow(val shareId: Long, val label: String, val enabled: Boolean) {
    val testTag get() = "settings_share_write_$shareId"
}

/**
 * The download queue as the Settings row reports it.
 *
 * Reads the same `transfers` rows the notification does (guardrail G3), so
 * the row and the shade can never disagree.
 */
data class DownloadsStatus(
    /** Queued, copying or paused. */
    val arriving: Int = 0,
    /** Copies finished, for the idle line. */
    val done: Int = 0,
    val failed: Int = 0,
    val bytesDone: Long = 0,
    val bytesTotal: Long = 0,
    /** A picked folder is still being walked, so the count can only go up. */
    val discovering: Boolean = false,
    val usedBytes: Long = 0,
    val deviceTotalBytes: Long = 0,
) {
    val running: Boolean get() = arriving > 0 || discovering

    /** 0..1 across the batch, by bytes. Null while there is nothing to measure. */
    val fraction: Float?
        get() = if (bytesTotal > 0) QueueProgress.permille(bytesDone, bytesTotal) / 1000f else null

    /** The 8dp dot: white while arriving, accent on a failure, grey at rest. */
    val dotState: DownloadDot
        get() = when {
            running -> DownloadDot.ARRIVING
            failed > 0 -> DownloadDot.FAILED
            else -> DownloadDot.IDLE
        }

    /** "3 of 34 · 61.2 GB left", "Finding files…", "3 failed", or what is already kept. */
    val meta: String
        get() = when {
            discovering && arriving == 0 -> "Finding files…"
            running -> {
                val left = (bytesTotal - bytesDone).coerceAtLeast(0)
                val prefix = if (discovering) "At least " else ""
                "$prefix${QueueProgress.label(done + 1, done + arriving)} · ${formatBytes(left)} left"
            }
            failed > 0 -> if (failed == 1) "1 failed" else "$failed failed"
            else -> "Nothing arriving · ${formatBytes(usedBytes)} of ${formatBytes(deviceTotalBytes)} used"
        }
}

enum class DownloadDot { ARRIVING, FAILED, IDLE }
