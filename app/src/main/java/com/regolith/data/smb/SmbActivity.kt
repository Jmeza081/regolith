package com.regolith.data.smb

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What the share has said lately, from the requests the app was making
 * anyway: the gateway reports every success and every "out of reach" here,
 * so the connection dot learns of a drop the moment a real request meets
 * it, and of a share that answers without probing for it.
 *
 * Keyed by the host as the server row names it, lower case. Cheap enough to
 * call per request: a map write, and an event only on failure.
 *
 * Web analogy: a fetch interceptor that records each response's status for
 * a status badge.
 */
@Singleton
class SmbActivity @Inject constructor() {

    /** When [host] last answered a request, in wall-clock milliseconds. */
    private val answered = ConcurrentHashMap<String, Long>()

    /** One per request that found the share out of reach, with what went wrong in a few words. */
    val failures: SharedFlow<Failure> get() = _failures
    private val _failures = MutableSharedFlow<Failure>(extraBufferCapacity = 8)

    data class Failure(val host: String, val cause: String, val atMs: Long)

    fun answered(host: String) {
        answered[host.lowercase()] = System.currentTimeMillis()
    }

    fun failed(host: String, cause: String) {
        _failures.tryEmit(Failure(host.lowercase(), cause, System.currentTimeMillis()))
    }

    fun lastAnsweredAt(host: String): Long? = answered[host.lowercase()]
}

/**
 * Why a request found the share out of reach, in the words the connection
 * sheet shows under "What happened". Read off the cause chain, most specific
 * first: a name that would not resolve says so (the failure that started
 * all this), rather than the "device not functioning" jcifs wraps it in.
 */
internal fun outOfReachCause(e: Throwable): String {
    val chain = generateSequence(e) { it.cause }.toList()
    return when {
        chain.any { it is java.net.UnknownHostException } -> "Couldn't find it by name"
        chain.any { it is java.net.NoRouteToHostException } -> "No route to it from this network"
        chain.any { it is java.net.ConnectException } -> "It refused the connection"
        chain.any { it is java.net.SocketTimeoutException } -> "It stopped answering"
        else -> "The connection dropped"
    }
}
