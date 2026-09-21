package com.regolith.domain.smb

/**
 * How many times a directory listing is attempted before the share is
 * called out of reach, and how long to wait between tries.
 *
 * One retry, not five. The failure this exists for is a connection that
 * dropped rather than a server that left: a VPN tunnel re-keying, a relay
 * closing an idle socket, a phone moving between cells. Those come back
 * immediately, so a single pause catches almost all of them.
 *
 * More attempts would be worse, not better. A listing that failed by
 * TIMING OUT has already spent the full response timeout, so every extra
 * attempt costs that again before anyone is told anything — and a server
 * that really has gone is found out by [SmbFailure.Unreachable] on connect,
 * which is fast and not affected by any of this.
 */
const val LISTING_ATTEMPTS = 2

/** Long enough for a tunnel to re-establish, short enough not to be felt. */
const val LISTING_RETRY_PAUSE_MS = 2_000L

/**
 * How long to wait before trying [attempt] again (1-based), or null when
 * this failure is not worth another go.
 *
 * Only [SmbFailure.Unreachable] is retried, because only it can be
 * transient. A refused login is refused the same way a second time; a path
 * that is not there will not have appeared; a permission denial is the
 * server's settled opinion. Retrying those would turn one wrong answer into
 * the same wrong answer, slower.
 */
fun listingRetryDelayMs(attempt: Int, failure: SmbFailure): Long? =
    if (failure is SmbFailure.Unreachable && attempt < LISTING_ATTEMPTS) LISTING_RETRY_PAUSE_MS else null
