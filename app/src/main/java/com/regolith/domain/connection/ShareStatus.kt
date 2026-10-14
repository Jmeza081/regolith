package com.regolith.domain.connection

/** Whether the share being browsed can be reached right now: the connection dot's three looks. */
enum class ShareLink {
    /** Not heard from yet: the app just opened, or the network changed. */
    CHECKING,

    /** The last check, or the last request, got an answer. Green. */
    CONNECTED,

    /** Two checks in a row got nothing. Grey and hollow. */
    OUT_OF_REACH,
}

/** How the phone is online, for the sheet's "Network" line. */
enum class NetworkKind(val label: String) {
    WIFI("Wi-Fi"),
    MOBILE("Mobile data"),
    VPN("VPN"),
    ETHERNET("Ethernet"),
    OTHER("Online"),
    NONE("Offline"),
}

/** A chapter file the share still owes, by its video's name. */
data class OwedWrite(val fileId: Long, val name: String)

/**
 * Everything the connection dot and its sheet say about the share being
 * browsed. Built by `ShareConnectionMonitor`; pure data, so the UI reads it
 * like a store's state.
 *
 * @property serverName the server row's name ("JESSES-MAC-MINI")
 * @property shareName the share the dot is watching ("Movies")
 * @property host what the server row names it by, a `.local` name or an address
 * @property address the IPv4 address requests go to, once resolved
 * @property rttMs how long the last check took to connect
 * @property lastAnsweredAtMs the last time the share answered anything, a check or a request
 * @property outSinceMs when it went out of reach, while it is
 * @property cause what went wrong, in a few words, while it is out of reach
 * @property drops how many times it went out of reach since the app opened
 * @property owed the chapter files waiting to be written
 */
data class ShareStatus(
    val serverId: Long,
    val serverName: String,
    val shareName: String?,
    val link: ShareLink,
    val host: String,
    val port: Int,
    val address: String? = null,
    val rttMs: Int? = null,
    val network: NetworkKind = NetworkKind.OTHER,
    val lastAnsweredAtMs: Long? = null,
    val outSinceMs: Long? = null,
    val cause: String? = null,
    val drops: Int = 0,
    val owed: List<OwedWrite> = emptyList(),
)

/** What the monitor says out loud, as a message over the pill. */
sealed interface ShareEvent {
    /** It went out of reach and stayed out past the recheck. */
    data class Dropped(val serverName: String, val owed: Int) : ShareEvent

    /** It is back; [written] chapter files that were waiting have gone to it. */
    data class Back(val serverName: String, val written: Int) : ShareEvent
}

/**
 * The dot's rule for one check, given the one before: a failed check is only
 * believed when the recheck fails too, so one missed answer never turns the
 * dot grey. Pure, so the hysteresis is unit-tested.
 *
 * @param firstOk the check's first try answered
 * @param recheckOk the second try, made only when the first failed (null if it was not)
 */
fun nextLink(firstOk: Boolean, recheckOk: Boolean?): ShareLink = when {
    firstOk -> ShareLink.CONNECTED
    recheckOk == true -> ShareLink.CONNECTED
    else -> ShareLink.OUT_OF_REACH
}
