package com.regolith.domain.security

/**
 * The app lock: Regolith asks for your fingerprint, face or screen lock
 * before it shows what is on your share.
 *
 * The rules live here, pure, so "should it ask again?" is decided by
 * arithmetic rather than by whatever the Activity happened to see. The
 * prompt itself is the platform's, driven from `data/security`.
 */
object AppLock {
    /**
     * Whether coming back to the foreground should ask again.
     *
     * [leftAtMs] is when the app last went to the background, or null for a
     * cold start — which always asks, because nothing has been unlocked yet
     * in this run. A clock that has gone backwards (the user changed the
     * time, or the device rebooted) counts as "long enough ago".
     */
    fun shouldAsk(enabled: Boolean, after: LockAfter, leftAtMs: Long?, nowMs: Long): Boolean {
        if (!enabled) return false
        if (leftAtMs == null) return true
        val away = nowMs - leftAtMs
        return away < 0 || away >= after.graceMs
    }
}

/**
 * How long the app may sit in the background before it asks again.
 *
 * A grace period exists because the alternative — asking every time you
 * glance at a notification — is what makes people turn a lock off. Stored
 * by [name], so the order of this enum can change without moving anyone's
 * setting.
 */
enum class LockAfter(val label: String, val graceMs: Long) {
    IMMEDIATELY("At once", 0L),
    ONE_MINUTE("After 1 min", 60_000L),
    FIVE_MINUTES("After 5 min", 5 * 60_000L),
    ;

    companion object {
        val DEFAULT = ONE_MINUTE

        /** Reads a stored name back; anything unknown falls back to [DEFAULT]. */
        fun of(name: String?): LockAfter = entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}

/** What the device can do about biometrics right now. */
enum class BiometricAvailability {
    /** A fingerprint, face or screen lock is enrolled and usable. */
    READY,

    /** The hardware is there (or a PIN would do) but nothing is set up yet. */
    NONE_ENROLLED,

    /** No fingerprint reader, no face unlock, and no screen lock. */
    NO_HARDWARE,

    /** Temporarily unusable — too many attempts, or the sensor is busy. */
    UNAVAILABLE,
    ;

    val canEnable: Boolean get() = this == READY

    /**
     * There is nothing to check against and there never will be until the
     * user sets something up again — they removed the screen lock, or this
     * device never had a way to ask.
     *
     * [UNAVAILABLE] is deliberately NOT this: a sensor that is busy, or
     * locked out after too many tries, is a reason to keep the door shut,
     * not to open it.
     */
    val nothingToCheck: Boolean get() = this == NONE_ENROLLED || this == NO_HARDWARE
}

/** How one trip through the prompt ended. */
sealed interface AuthResult {
    /** Unlocked. */
    data object Success : AuthResult

    /** The user dismissed it, or pressed back. Stay locked, say nothing. */
    data object Cancelled : AuthResult

    /** The sensor or the system refused, with something worth showing. */
    data class Error(val message: String) : AuthResult
}
