package com.regolith.desktop.data

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption

/**
 * The servers the Mac app remembers, in one small JSON file.
 *
 * A file rather than a database: it is a handful of rows read once per
 * launch, and a person can open it to see what the app knows. Writes go to a
 * temporary file that is moved over the real one, so a crash mid-write
 * never leaves half a file. A file that cannot be read is moved aside to
 * `servers.json.bad` rather than silently overwritten.
 *
 * Web analogy: localStorage for a desktop app, with the secrets kept out.
 */
class ServerStore(
    private val file: Path = defaultFile(),
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val lock = Mutex()

    /** Every saved server, in the order they were first saved. */
    suspend fun all(): List<SavedServer> = lock.withLock { withContext(io) { read() } }

    /**
     * Add or update [server] and return it as saved (with its id). A server
     * with the same host, port, share and username as an existing row
     * updates that row, so connecting twice does not make a duplicate.
     */
    suspend fun save(server: SavedServer): SavedServer = lock.withLock {
        withContext(io) {
            val rows = read()
            val existing = rows.firstOrNull { server.id != 0L && it.id == server.id } ?: rows.firstOrNull { it.sameShareAs(server) }
            val saved = server.copy(id = existing?.id ?: ((rows.maxOfOrNull { it.id } ?: 0L) + 1))
            write(rows.map { if (it.id == saved.id) saved else it }.let { if (existing == null) it + saved else it })
            saved
        }
    }

    suspend fun remove(id: Long) = lock.withLock {
        withContext(io) { write(read().filterNot { it.id == id }) }
    }

    private fun read(): List<SavedServer> {
        if (!Files.exists(file)) return emptyList()
        return try {
            json.decodeFromString<ServersFile>(Files.readString(file)).servers
        } catch (e: SerializationException) {
            setAside(e)
        } catch (e: IllegalArgumentException) {
            setAside(e)
        }
    }

    private fun setAside(e: Exception): List<SavedServer> {
        val bad = file.resolveSibling(file.fileName.toString() + ".bad")
        log.warn("Could not read {}; moved it to {}", file, bad, e)
        Files.move(file, bad, StandardCopyOption.REPLACE_EXISTING)
        return emptyList()
    }

    private fun write(rows: List<SavedServer>) {
        Files.createDirectories(file.parent)
        val tmp = file.resolveSibling(file.fileName.toString() + ".tmp")
        Files.writeString(tmp, json.encodeToString(ServersFile.serializer(), ServersFile(servers = rows)))
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    /** The file's shape. [version] leaves room to change it without guessing. */
    @Serializable
    private data class ServersFile(val version: Int = 1, val servers: List<SavedServer>)

    companion object {
        private val log = LoggerFactory.getLogger("Regolith/Servers")

        /** `~/Library/Application Support/Regolith Chapters/servers.json`, where macOS apps keep their data. */
        fun defaultFile(): Path =
            Paths.get(System.getProperty("user.home"), "Library", "Application Support", "Regolith Chapters", "servers.json")
    }
}
