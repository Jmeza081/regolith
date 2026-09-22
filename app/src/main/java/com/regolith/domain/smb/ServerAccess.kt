package com.regolith.domain.smb

/**
 * "How do I open a share on this server, and as whom?"
 *
 * What a component needs when it must reach a share but has no business
 * with how passwords are kept or how addresses are chosen;
 * `SourceRepository` is the one implementation.
 *
 * It was [credentialsFor] alone, under the name `CredentialSource`, while
 * an address was a fixed property of a server row that any caller could
 * read. Once a server could be reached more than one way — a LAN address
 * at home, a VPN name from anywhere — the address stopped being something
 * you look up and became something you ASK for, at the moment you need it,
 * because the answer depends on where the phone is. The two questions have
 * the same shape and the same lifetime, so they travel together.
 */
interface ServerAccess {
    suspend fun credentialsFor(serverId: Long): SmbCredentials

    /** The address to use right now. A server with one address answers straight from its row. */
    suspend fun hostFor(serverId: Long): SmbHost
}
