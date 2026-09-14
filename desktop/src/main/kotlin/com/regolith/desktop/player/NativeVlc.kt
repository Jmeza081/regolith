package com.regolith.desktop.player

import com.sun.jna.NativeLibrary
import org.slf4j.LoggerFactory
import uk.co.caprica.vlcj.binding.lib.LibC
import uk.co.caprica.vlcj.binding.support.runtime.RuntimeUtil
import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.factory.discovery.NativeDiscovery
import uk.co.caprica.vlcj.factory.discovery.strategy.BaseNativeDiscoveryStrategy
import java.io.File

/**
 * Finds and loads libvlc, once, before any player is made.
 *
 * The packaged app carries its own libvlc (see `fetchVlc` in the build
 * file), so VLC does not have to be installed. The order tried:
 *  1. the packaged app's copy, at `compose.application.resources.dir`/vlc/lib;
 *  2. the checkout's fetched copy, `vlc-bundle/macos-arm64/vlc/lib`, for
 *     `:desktop:run`, `smoke` and tests run from the module directory;
 *  3. vlcj's own search, which finds an installed `/Applications/VLC.app`.
 *
 * vlcj remembers a successful discovery for the whole process, so every
 * player made afterwards uses whatever loaded here.
 *
 * For a bundled libvlc, plugins are loaded through [VlcPluginIndex]'s linked
 * folder so libvlc's plugin index stays valid; the first launch after an
 * install builds that index once. Any failure there falls back to the
 * bundled plugins folder: slower to start, same playback.
 */
object NativeVlc {
    private val log = LoggerFactory.getLogger("Regolith/VLC")

    @Volatile private var tried = false

    /** The directory libvlc was loaded from, or null when it was not found. */
    @Volatile var loadedFrom: String? = null
        private set

    /** What happened to the plugin index this launch, in words, for the self-check. */
    @Volatile var indexStatus: String = "not used"
        private set

    @Synchronized
    fun load(): String? {
        if (tried) return loadedFrom
        tried = true
        val bundled = candidateLibDirs().firstOrNull { BundledVlcStrategy.hasLibVlc(it) }
        loadedFrom = bundled?.let { dir ->
            val index = runCatching { VlcPluginIndex.prepare(File(dir, "../plugins"), supportDir()) }
                .onFailure { log.warn("Could not prepare the plugin index; libvlc will scan its plugins", it); indexStatus = "unavailable (${it::class.simpleName})" }
                .getOrNull()
            NativeDiscovery(BundledVlcStrategy(dir, index?.dir)).takeIf { it.discover() }?.discoveredPath()
                ?.also { if (index != null) ensureIndexed(index) }
                .also { if (it == null) log.warn("The bundled libvlc in {} did not load; trying an installed VLC", dir) }
        } ?: NativeDiscovery().takeIf { it.discover() }?.discoveredPath()
        if (loadedFrom != null) log.info("libvlc loaded from {}", loadedFrom) else log.warn("libvlc was not found")
        return loadedFrom
    }

    /** Builds libvlc's index in the linked folder the first time, so every later launch can trust it. */
    private fun ensureIndexed(index: VlcPluginIndex.Prepared) {
        if (index.ready) {
            indexStatus = "reused"
            return
        }
        val started = System.nanoTime()
        runCatching { MediaPlayerFactory("--reset-plugins-cache", "--quiet").release() }
            .onFailure { log.warn("libvlc could not build its plugin index", it) }
        val ms = (System.nanoTime() - started) / 1_000_000
        indexStatus = if (VlcPluginIndex.markIndexed(index)) "built this launch in $ms ms" else "not written; libvlc will scan its plugins"
        log.info("plugin index: {}", indexStatus)
    }

    /** The Mac app's own data folder, where the linked plugin folder lives. */
    private fun supportDir(): File =
        File(System.getProperty("user.home"), "Library/Application Support/Regolith Chapters")

    /** Where a bundled libvlc may be, in the order [load] tries them. */
    internal fun candidateLibDirs(): List<File> = listOfNotNull(
        System.getProperty("compose.application.resources.dir")?.let { File(it, "vlc/lib") },
        File("vlc-bundle/macos-arm64/vlc/lib").absoluteFile,
    )
}

/**
 * vlcj discovery pointed at exactly one directory: the bundled `lib/`.
 *
 * vlcj's own macOS strategy cannot be narrowed (its directory list is
 * final), so this does the same two things it does, for one folder:
 * libvlc names libvlccore by `@rpath`, which only resolves if libvlccore is
 * already loaded, so that is loaded first; and libvlc is told where its
 * plugins are through `VLC_PLUGIN_PATH`: [VlcPluginIndex]'s linked folder
 * when there is one, else the `plugins/` folder beside `lib/`.
 */
internal class BundledVlcStrategy(private val libDir: File, private val pluginDirOverride: File? = null) :
    BaseNativeDiscoveryStrategy(arrayOf("libvlc\\.dylib", "libvlccore\\.dylib"), arrayOf("%s/../plugins")) {

    override fun supported(): Boolean = RuntimeUtil.isMac()

    override fun discoveryDirectories(): List<String> = listOf(libDir.absolutePath)

    override fun onFound(path: String): Boolean {
        NativeLibrary.addSearchPath(RuntimeUtil.getLibVlcCoreLibraryName(), path)
        NativeLibrary.getInstance(RuntimeUtil.getLibVlcCoreLibraryName())
        return true
    }

    /** The linked folder with the valid index when there is one, else the plugins beside `lib/`. */
    override fun setPluginPath(pluginPath: String): Boolean =
        LibC.INSTANCE.setenv("VLC_PLUGIN_PATH", pluginDirOverride?.absolutePath ?: pluginPath, 1) == 0

    companion object {
        fun hasLibVlc(dir: File): Boolean = File(dir, "libvlc.dylib").exists() && File(dir, "libvlccore.dylib").exists()
    }
}
