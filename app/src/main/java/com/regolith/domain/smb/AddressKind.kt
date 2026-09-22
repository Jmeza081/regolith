package com.regolith.domain.smb

/**
 * Whether an address is one a machine is likely to keep.
 *
 * A literal address in a home router's DHCP range is the least durable
 * thing you can write down: it belongs to the LEASE, not to the machine,
 * and the machine gets a different one the next time the router feels like
 * it. That is not a hypothetical — it is what took the owner's library
 * offline, with the app dutifully holding a pin to an address where
 * nothing lived any more.
 *
 * A name — mDNS (`mac-mini.local`), a VPN name, anything the network
 * resolves — follows the machine instead, which is why the app can suggest
 * one rather than simply reporting the failure afterwards.
 *
 * Deliberately NOT a blocker. A static lease makes a literal address
 * perfectly durable, and plenty of people have one; this only earns a
 * sentence, never a refusal.
 */
fun isLikelyToMove(host: String): Boolean {
    val parts = host.split('.')
    if (parts.size != 4) return false
    val octets = parts.map { it.toIntOrNull() ?: return false }
    if (octets.any { it !in 0..255 }) return false
    val (a, b) = octets
    return when {
        a == 10 -> true
        a == 172 && b in 16..31 -> true
        a == 192 && b == 168 -> true
        else -> false
    }
}
