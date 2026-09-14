package com.regolith.domain.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLockTest {
    private val minute = 60_000L

    @Test
    fun `off never asks`() {
        assertFalse(AppLock.shouldAsk(enabled = false, after = LockAfter.IMMEDIATELY, leftAtMs = null, nowMs = 0))
        assertFalse(AppLock.shouldAsk(enabled = false, after = LockAfter.IMMEDIATELY, leftAtMs = 0, nowMs = minute))
    }

    @Test
    fun `a cold start always asks`() {
        LockAfter.entries.forEach { after ->
            assertTrue(after.name, AppLock.shouldAsk(enabled = true, after = after, leftAtMs = null, nowMs = 1_000))
        }
    }

    @Test
    fun `the grace period is the whole difference between the choices`() {
        // Away for half a minute.
        assertTrue(AppLock.shouldAsk(true, LockAfter.IMMEDIATELY, leftAtMs = 0, nowMs = 30_000))
        assertFalse(AppLock.shouldAsk(true, LockAfter.ONE_MINUTE, leftAtMs = 0, nowMs = 30_000))
        assertFalse(AppLock.shouldAsk(true, LockAfter.FIVE_MINUTES, leftAtMs = 0, nowMs = 30_000))
        // Away for two minutes.
        assertTrue(AppLock.shouldAsk(true, LockAfter.ONE_MINUTE, leftAtMs = 0, nowMs = 2 * minute))
        assertFalse(AppLock.shouldAsk(true, LockAfter.FIVE_MINUTES, leftAtMs = 0, nowMs = 2 * minute))
        // Exactly on the boundary counts as long enough.
        assertTrue(AppLock.shouldAsk(true, LockAfter.ONE_MINUTE, leftAtMs = 0, nowMs = minute))
    }

    @Test
    fun `a clock that went backwards asks rather than trusts itself`() {
        assertTrue(AppLock.shouldAsk(true, LockAfter.FIVE_MINUTES, leftAtMs = 10 * minute, nowMs = 0))
    }

    @Test
    fun `a stored name survives the enum being reordered, and nonsense falls back`() {
        assertEquals(LockAfter.FIVE_MINUTES, LockAfter.of("FIVE_MINUTES"))
        assertEquals(LockAfter.DEFAULT, LockAfter.of(null))
        assertEquals(LockAfter.DEFAULT, LockAfter.of("AFTER_A_FORTNIGHT"))
    }
}

class BiometricAvailabilityTest {
    @Test
    fun `only a ready device can switch the lock on`() {
        assertTrue(BiometricAvailability.READY.canEnable)
        BiometricAvailability.entries.filter { it != BiometricAvailability.READY }.forEach {
            assertFalse(it.name, it.canEnable)
        }
    }

    @Test
    fun `a busy sensor keeps the door shut, a phone with no screen lock cannot hold it`() {
        assertTrue(BiometricAvailability.NONE_ENROLLED.nothingToCheck)
        assertTrue(BiometricAvailability.NO_HARDWARE.nothingToCheck)
        // Temporary: too many tries, or the reader is in use. Still a lock.
        assertFalse(BiometricAvailability.UNAVAILABLE.nothingToCheck)
        assertFalse(BiometricAvailability.READY.nothingToCheck)
    }
}
