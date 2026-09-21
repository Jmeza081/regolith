package com.regolith.domain.media

/**
 * "On this device": the source a downloaded copy belongs to once it has
 * outlived the share it came from.
 *
 * **Why a source and not a flag.** A copy on the phone used to be defined
 * by the share file it came from — `transfers.fileId` points at
 * `media_files`, which cascades from `shares`, which cascades from
 * `servers`. Disconnecting a server therefore deleted every download row
 * and left the bytes on disk with nothing pointing at them: Settings kept
 * counting gigabytes that the On-this-device page could no longer list.
 *
 * Rather than loosening that foreign key, a disconnect MOVES the file rows
 * to a source that is not going anywhere — this one. The row keeps its id,
 * so its resume point, its chapters and its artwork come with it for free
 * (guardrail G3: identity is stable, and a row is never deleted when it can
 * be re-pointed).
 *
 * It is a synthetic server for the same reason [DemoSource] is: the library,
 * Browse, Search and the artwork pipeline all address media through a
 * share, and inventing a second kind of file would mean teaching every one
 * of them about it. A source they already understand costs nothing.
 *
 * Web analogy: a local-storage "bucket" that implements the same interface
 * as the remote one, so callers never branch.
 */
object DeviceSource {
    /** Not a real TLD, not resolvable, and obvious in a log line. */
    const val HOST = "device.regolith.local"

    /** What Settings and the Library wall call it. */
    const val NAME = "This device"

    /** Its one share. Files land flat inside it, named as [com.regolith.data.transfer.DownloadStore] named them. */
    const val SHARE = "Downloads"

    fun isDevice(host: String): Boolean = host.equals(HOST, ignoreCase = true)
}

/**
 * The sources that live on the phone rather than on a network.
 *
 * Both [DemoSource] and [DeviceSource] have no SMB server behind them, so
 * every caller that would dial one has to skip them — and skipping matters
 * as much as connecting: a listing, scan or reachability probe against a
 * host that cannot exist does not merely fail, it marks the source "out of
 * reach" and makes a perfectly good library look broken.
 *
 * One predicate so a third local source can never be half-added.
 */
object LocalSource {
    fun isLocal(host: String): Boolean = DemoSource.isDemo(host) || DeviceSource.isDevice(host)
}
