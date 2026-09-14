package com.regolith.domain.media

/**
 * The one thing the rest of the app needs to know about the demo library:
 * which server is pretend.
 *
 * A demo server has no host behind it, so every SMB call to it would fail
 * and — worse — mark it "out of reach", which would make a perfectly good
 * demo library look broken the first time someone pulled to refresh. The
 * three places that would dial it (the scan, a folder refresh, the
 * reachability probe) check here and skip instead.
 *
 * It lives in `domain` rather than in `data/demo` so the repositories can
 * ask without depending on the seeder.
 */
object DemoSource {
    /** Not a real TLD, not resolvable, and obvious in a log line. */
    const val HOST = "demo.regolith.local"

    fun isDemo(host: String): Boolean = host.equals(HOST, ignoreCase = true)
}
