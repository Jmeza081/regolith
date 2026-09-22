package com.regolith.ui

import com.regolith.ui.serverdetail.slowLinkBody
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the app tells you when it is holding work back.
 *
 * The number has to be wall-clock. "3x slower" is a fact about the link;
 * "about six hours" is a fact about the decision in front of you, and it
 * is the one that makes waiting an obvious choice rather than the app
 * being unhelpful.
 */
class SlowLinkCopyTest {
    @Test
    fun `a real library over a slow link is counted in hours`() {
        val body = slowLinkBody(fileCount = 4_547, slowdown = 3.0)
        assertTrue("was: $body", body.contains("hours"))
    }

    @Test
    fun `a small library is not made to sound like a day's work`() {
        val body = slowLinkBody(fileCount = 200, slowdown = 3.0)
        assertTrue("was: $body", body.contains("a while") || body.contains("an hour"))
    }

    @Test
    fun `it always says what the waiting is for`() {
        listOf(0, 200, 4_547, 50_000).forEach { n ->
            assertTrue("$n files", slowLinkBody(n, 3.0).contains("waits until you are somewhere faster"))
        }
    }
}
