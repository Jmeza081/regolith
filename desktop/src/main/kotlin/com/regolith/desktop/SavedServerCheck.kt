package com.regolith.desktop

import com.regolith.data.smb.JcifsGateway
import com.regolith.desktop.data.KeychainCredentialStore
import com.regolith.desktop.data.ServerStore
import com.regolith.desktop.ui.childPath
import com.regolith.domain.media.ChapterParser
import com.regolith.domain.media.ChapterSidecar
import com.regolith.domain.media.MediaFileTypes
import com.regolith.domain.smb.SmbEntry

/**
 * `Regolith Chapters --check-saved <id>`: signs in to a server saved in the
 * app, exactly as the app does (its `servers.json` row and its Keychain
 * password), and reports what it sees. It never writes and never prints
 * the password.
 *
 * It lists the share, walks two folder levels (capped), counts films and
 * chapter files, and reads the chapters inside a few films. That covers the
 * real NAS paths the test fixture cannot: a password sign-in, a large share
 * and films muxed by other tools. It also lists empty chapter files, the
 * trace of an open that created a file it only meant to look for.
 *
 * Film names are never printed, only counts: the output ends up in logs
 * and conversations, and a library is private. Empty chapter files are
 * listed by path, because those are what someone would need to clean up.
 */
internal object SavedServerCheck {
    private const val MAX_FOLDERS = 60
    private const val MAX_FILMS_READ = 5

    suspend fun run(id: Long): Int {
        val server = ServerStore().all().firstOrNull { it.id == id }
            ?: return fail("No saved server with id $id")
        val password = if (server.isGuest) "" else KeychainCredentialStore().get(id)
            ?: return fail("No password for ${server.label} in the Keychain")
        val connection = server.connection(password)
        val gateway = JcifsGateway()

        val started = System.nanoTime()
        val root = gateway.list(connection.host, connection.credentials, connection.share, "")
        println("signed in to ${server.label} as ${if (server.isGuest) "guest" else server.username}: ${root.size} entries at the root in ${(System.nanoTime() - started) / 1_000_000} ms")

        val films = mutableListOf<Pair<String, SmbEntry>>()
        var sidecars = 0
        val emptySidecars = mutableListOf<String>()
        var folders = 0
        fun take(folder: String, entries: List<SmbEntry>) {
            entries.filter { !it.isDirectory }.forEach { e ->
                if (e.name.endsWith(ChapterSidecar.SUFFIX)) {
                    sidecars++
                    if (e.sizeBytes == 0L) emptySidecars += childPath(folder, e.name)
                }
                if (MediaFileTypes.isVideo(e.name)) films += folder to e
            }
        }
        take("", root)
        val level1 = root.filter { it.isDirectory }.map { it.name }
        val level2 = mutableListOf<String>()
        for (dir in level1.take(MAX_FOLDERS)) {
            folders++
            val entries = runCatching { gateway.list(connection.host, connection.credentials, connection.share, dir) }.getOrElse { continue }
            take(dir, entries)
            level2 += entries.filter { it.isDirectory }.map { childPath(dir, it.name) }
        }
        for (dir in level2.take(MAX_FOLDERS)) {
            folders++
            take(dir, runCatching { gateway.list(connection.host, connection.credentials, connection.share, dir) }.getOrElse { continue })
        }
        println("walked $folders folders: ${films.size} films, $sidecars chapter files, ${emptySidecars.size} of them empty")
        // An empty chapter file is what jcifs's create-if-missing open leaves
        // behind when something looked for a file that was not there.
        emptySidecars.take(20).forEach { println("  empty: $it") }

        val readable = films.filter { (_, e) -> e.name.substringAfterLast('.').lowercase() in setOf("mkv", "mp4", "m4v") }
        readable.take(MAX_FILMS_READ).forEachIndexed { n, (folder, film) ->
            val t = System.nanoTime()
            val chapters = runCatching {
                gateway.open(connection.host, connection.credentials, connection.share, childPath(folder, film.name)).use { ChapterParser.read(it) }
            }
            val ms = (System.nanoTime() - t) / 1_000_000
            chapters.fold(
                onSuccess = { println("  film ${n + 1} (.${film.name.substringAfterLast('.').lowercase()}): ${it.size} chapters inside (${it.count { c -> c.title != null }} named), read in $ms ms") },
                onFailure = { println("  film ${n + 1}: could not read (${it::class.simpleName})") },
            )
        }
        return 0
    }

    private fun fail(message: String): Int {
        System.err.println("CHECK FAILED: $message")
        return 1
    }
}
