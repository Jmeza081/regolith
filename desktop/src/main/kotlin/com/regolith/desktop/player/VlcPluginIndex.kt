package com.regolith.desktop.player

import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

/**
 * Keeps libvlc's plugin index valid for the bundled plugins without touching
 * the signed app.
 *
 * libvlc reads `plugins.dat` from its plugin folder and trusts it only while
 * every plugin's modification time and size match. The bundled plugins are
 * copied several times on the way into an installed app (fetch, Gradle,
 * jpackage, the DMG, a Finder drag), so VideoLAN's index never matches and
 * libvlc rescans every plugin on every launch (measured: 3.3 to 4.0 s). The
 * index cannot be rebuilt inside the app either: the app is code-signed, and
 * changing a file in it breaks the signature.
 *
 * So the index lives outside the app. [prepare] makes a folder in
 * Application Support holding a symbolic link to each bundled plugin; libvlc
 * is pointed at that folder, follows the links (so it sees the real plugins'
 * times, which do not change once installed) and writes its index there
 * once. The folder's name is a stamp of the plugins' location, sizes and
 * times: move or update the app and a new folder is made and indexed once,
 * and the old one is removed.
 *
 * Web analogy: a build cache keyed by a content hash, kept beside the app
 * rather than inside it.
 */
internal object VlcPluginIndex {
    private const val PREFIX = "vlc-plugins-"
    private const val INDEX = "plugins.dat"
    /** Written once every link exists, so a half-made folder is redone. */
    private const val LINKED = ".regolith-linked"
    /** Written once libvlc has written its index here. */
    private const val INDEXED = ".regolith-indexed"

    data class Prepared(val dir: File, val ready: Boolean)

    /** The linked folder for [pluginsDir] under [root], made if needed; [Prepared.ready] once it has a valid index. */
    fun prepare(pluginsDir: File, root: File): Prepared {
        val source = pluginsDir.canonicalFile
        val dir = File(root, PREFIX + stamp(source))
        // Only folders this made, and only by name: never anything the links point at.
        root.listFiles { f -> f.isDirectory && f.name.startsWith(PREFIX) && f.name != dir.name }?.forEach { it.deleteRecursively() }
        if (!File(dir, LINKED).exists()) {
            dir.deleteRecursively()
            mirror(source, dir)
            File(dir, LINKED).writeText(source.path)
        }
        return Prepared(dir, isReady(dir))
    }

    /** Call after libvlc was asked to rebuild its index in [prepared]'s folder; true when the index is there. */
    fun markIndexed(prepared: Prepared): Boolean {
        if (!File(prepared.dir, INDEX).exists()) return false
        File(prepared.dir, INDEXED).writeText("")
        return true
    }

    private fun isReady(dir: File): Boolean = File(dir, INDEXED).exists() && File(dir, INDEX).exists()

    /** A link per plugin file (folders recreated as folders); VideoLAN's own index is left out. */
    private fun mirror(source: File, dest: File) {
        dest.mkdirs()
        source.walkTopDown().forEach { f ->
            val rel = f.relativeTo(source).path
            if (rel.isEmpty()) return@forEach
            val target = File(dest, rel)
            when {
                f.isDirectory -> target.mkdirs()
                f.name == INDEX -> Unit
                else -> Files.createSymbolicLink(target.toPath(), f.toPath())
            }
        }
    }

    /** Where the plugins are, and each one's path, size and time: what libvlc's index depends on. */
    internal fun stamp(source: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(source.path.toByteArray())
        source.walkTopDown().filter { it.isFile && it.name != INDEX }.sortedBy { it.path }.forEach { f ->
            digest.update("${f.relativeTo(source).path}|${f.length()}|${f.lastModified()}\n".toByteArray())
        }
        return digest.digest().take(8).joinToString("") { "%02x".format(it) }
    }
}
