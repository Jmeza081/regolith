package com.regolith.desktop.data

import com.regolith.data.credentials.CredentialStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * Server passwords in the macOS Keychain: the Mac's implementation of the
 * same [CredentialStore] the phone backs with the Android Keystore.
 *
 * It drives `/usr/bin/security`, which ships with macOS, instead of binding
 * Security.framework: three short commands, no native code. One entry per
 * server: service [service], account = the server's id. They show up in
 * Keychain Access under that service name.
 *
 * Two details that are not obvious:
 *  - **The password never goes on a command line**, where any local process
 *    could read it with `ps`. Writes are sent to `security -i` over stdin.
 *  - **Passwords are stored as `b64:` + base64 of their UTF-8 bytes.**
 *    `security find-generic-password -w` prints a password that is not plain
 *    ASCII as hex, and a real password made of hex digits would then be
 *    indistinguishable. Encoding keeps every stored value ASCII and
 *    unambiguous. A plain entry made by hand in Keychain Access still reads.
 */
class KeychainCredentialStore(
    private val service: String = SERVICE,
    private val securityTool: String = "/usr/bin/security",
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : CredentialStore {

    override suspend fun get(serverId: Long): String? = withContext(io) {
        val r = run(listOf(securityTool, "find-generic-password", "-s", service, "-a", serverId.toString(), "-w"))
        when (r.exit) {
            0 -> decode(r.out.removeSuffix("\n"))
            NOT_FOUND -> null
            else -> throw KeychainException("The Keychain could not read the password", r.err)
        }
    }

    override suspend fun put(serverId: Long, password: String) = withContext(io) {
        val stored = PREFIX + Base64.getEncoder().encodeToString(password.toByteArray(Charsets.UTF_8))
        val command = "add-generic-password -U -s ${quote(service)} -a ${quote(serverId.toString())} -w ${quote(stored)}\n"
        val r = run(listOf(securityTool, "-i"), stdin = command)
        // `security -i` reports a failed command on stderr, not always in its
        // exit code, so the write is proven by reading it back.
        val back = run(listOf(securityTool, "find-generic-password", "-s", service, "-a", serverId.toString(), "-w"))
        if (back.exit != 0 || back.out.removeSuffix("\n") != stored) {
            throw KeychainException("The Keychain did not save the password", r.err.ifBlank { back.err })
        }
    }

    override suspend fun clear(serverId: Long) = withContext(io) {
        val r = run(listOf(securityTool, "delete-generic-password", "-s", service, "-a", serverId.toString()))
        if (r.exit != 0 && r.exit != NOT_FOUND) throw KeychainException("The Keychain could not remove the password", r.err)
    }

    private fun decode(value: String): String =
        if (value.startsWith(PREFIX)) String(Base64.getDecoder().decode(value.removePrefix(PREFIX)), Charsets.UTF_8) else value

    private data class Result(val exit: Int, val out: String, val err: String)

    private fun run(args: List<String>, stdin: String? = null): Result {
        val process = ProcessBuilder(args).start()
        process.outputStream.use { if (stdin != null) it.write(stdin.toByteArray(Charsets.UTF_8)) }
        val out = process.inputStream.bufferedReader().readText()
        val err = process.errorStream.bufferedReader().readText()
        if (!process.waitFor(15, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            throw KeychainException("The Keychain did not answer", null)
        }
        return Result(process.exitValue(), out, err.trim())
    }

    /** `security -i` splits its input like a shell: double-quote, escaping `\` and `"`. */
    private fun quote(s: String): String = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    companion object {
        /** The service name entries are filed under in Keychain Access. */
        const val SERVICE = "Regolith Chapters"
        private const val PREFIX = "b64:"
        /** `errSecItemNotFound`, as `security` reports it. */
        private const val NOT_FOUND = 44
    }
}

/** The Keychain refused or failed; [detail] is `security`'s own message, for the small print. */
class KeychainException(message: String, val detail: String?) : Exception(message)
