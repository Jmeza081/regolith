package com.regolith.domain.model

import com.regolith.domain.smb.SmbHost

/**
 * What a source server is CALLED, as opposed to where it lives.
 *
 * A server is identified by its address — `(host, port)` is the unique key
 * on the `servers` table — and nothing in the app looks a server up by its
 * name. That is what makes the name free to be whatever the user wants:
 * "Living room NAS" instead of `192.168.4.73`. There is no separate
 * nickname column, because there is nothing for a second name to disagree
 * with; a rename simply writes `servers.name`.
 *
 * These live in `domain/` rather than in the repository so the rule can be
 * tested without Room or Android, and so the "reset to the default" case is
 * the same code path as the first naming.
 */

/** Longest name a row will show before it ellipsises. The field stops here too, so nothing is silently cut. */
const val MAX_SERVER_NAME = 40

/**
 * The name the app picks when the user has not: "TOWER" for `tower.local`,
 * and the address itself for an IP, because there is nothing friendlier to
 * be had from four numbers.
 */
fun defaultServerName(host: SmbHost): String {
    val h = host.host
    val isIp = h.all { it.isDigit() || it == '.' } || h.contains(':')
    return if (isIp) h else h.substringBefore('.').uppercase().ifBlank { h }
}

/**
 * What to store for a name the user typed. Blank means "I don't want to
 * name this", which is the default rather than an empty row — an unnamed
 * server would be an unidentifiable one.
 */
fun serverNameOrDefault(typed: String, host: SmbHost): String =
    typed.trim().take(MAX_SERVER_NAME).ifBlank { defaultServerName(host) }
