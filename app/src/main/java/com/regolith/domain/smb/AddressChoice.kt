package com.regolith.domain.smb

/** One route to a server, as the chooser sees it. */
data class AddressProbe(
    val id: Long,
    val host: String,
    val port: Int,
    /** How long it took to answer, or null when it did not. */
    val rttMs: Int?,
)

/**
 * Which address to use, given how each one answered.
 *
 * Pure, so the rule can be stated once and tested. The rule itself is
 * deliberately boring: **the fastest address that answered at all**. There
 * is no preference for a private range, no "home network" detection, no
 * SSID list — a direct path on the LAN wins because it IS faster (2 ms
 * against 34 ms on the owner's own network), not because anything taught
 * the app what a home network looks like. That keeps it honest on networks
 * nobody anticipated: a second site, a friend's Wi-Fi, a hotel.
 *
 * Ties go to the earlier address, so a stable network keeps picking the
 * same route and does not flap between two equals.
 *
 * @return the winner, or null when nothing answered — which is the only
 *   thing that should ever be called "out of reach".
 */
fun chooseAddress(probes: List<AddressProbe>): AddressProbe? =
    probes.filter { it.rttMs != null }.minByOrNull { it.rttMs!! }

/**
 * How much slower one address is than the best one, as a multiple.
 *
 * Drives the sentence on a slow link ("about 90× slower than the home
 * one"), and eventually the decision to hold a scan back until you are
 * somewhere it can finish. Null when there is nothing to compare against.
 */
fun slowdownFactor(chosen: AddressProbe?, others: List<AddressProbe>): Double? {
    val best = others.plus(listOfNotNull(chosen)).mapNotNull { it.rttMs }.minOrNull() ?: return null
    val mine = chosen?.rttMs ?: return null
    if (best <= 0) return null
    return mine.toDouble() / best.toDouble()
}
