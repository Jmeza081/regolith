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

/**
 * How much slower a link has to be before it is worth saying so.
 *
 * Three times. Not a round number pulled from nowhere: the owner's own
 * addresses measured 30 ms on the LAN against 92 ms over the tunnel, which
 * is a factor of three and is the point at which "this will take a while"
 * stops being pedantry — a scan is thousands of round trips, so three times
 * the latency is three times the wall clock, and an artwork pass that takes
 * two hours at home takes six.
 */
const val SLOW_LINK_FACTOR = 3.0

/**
 * Whether the route in use is slow enough that unattended work should wait.
 *
 * Deliberately a comparison and not an absolute: a library on a NAS at the
 * end of a slow home connection is not "far away", it is just what this
 * person's setup costs, and telling them so on every screen would be noise.
 * What matters is that a FASTER way in exists and is not available from
 * here — which is exactly the case where waiting gets you something.
 */
fun isSlowLink(chosen: AddressProbe?, others: List<AddressProbe>): Boolean =
    (slowdownFactor(chosen, others) ?: 1.0) >= SLOW_LINK_FACTOR
