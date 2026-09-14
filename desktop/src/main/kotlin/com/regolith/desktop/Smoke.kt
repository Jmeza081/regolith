package com.regolith.desktop

import com.regolith.domain.media.ChapterSidecar
import com.regolith.domain.playback.ChapterDraft
import com.regolith.domain.smb.SmbCredentials
import com.regolith.domain.smb.SmbHost
import kotlinx.coroutines.runBlocking
import kotlin.system.exitProcess

/**
 * Headless check of the path the Mac app depends on, runnable without a
 * window: `./gradlew :desktop:smoke` (the local Samba fixture by default,
 * or `-PsmokeArgs="host port share"`).
 *
 *  1. The shared jcifs gateway lists and opens a film.
 *  2. libvlc (the fetched bundle, via [SelfCheck]) decodes it through
 *     `SeekableByteSource`, and a seek lands.
 *  3. The shared chapter code writes a sidecar and reads the same one back;
 *     the fixture's file is put back afterwards.
 *
 * Guest only; exits non-zero on the first failure.
 */
fun main(args: Array<String>) {
    runBlocking { smoke(args) }
}

private suspend fun smoke(args: Array<String>) {
    val host = SmbHost(args.getOrElse(0) { "localhost" }, args.getOrElse(1) { "1445" }.toInt())
    val share = args.getOrElse(2) { "media" }
    val creds = SmbCredentials.Guest
    val graph = AppGraph()
    val folder = "Films"
    val name = "Long.Test.2026.mp4"
    try {
        val films = graph.gateway.list(host, creds, share, folder)
        check(films.any { it.name == name }) { "$folder/$name is not on the share" }
        println("1. listed $folder: ${films.size} entries")

        val report = SelfCheck.play(graph.gateway, host, creds, share, "$folder/$name")
        check(report.landedMs in 235_000..250_000) { "seek to 4:00 landed at ${report.landedMs} ms" }
        val length = report.lengthMs
        println("2. libvlc from ${report.libvlc}: length $length ms, seek to 4:00 landed at ${report.landedMs} ms")

        val sidecar = "$folder/Long.Test.2026${ChapterSidecar.SUFFIX}"
        val before = graph.sidecars.read(host, creds, share, sidecar)
        val draft = ChapterDraft.seed(0, ChapterSidecar.parse(before.orEmpty()), length)
            .mark(30_000).rename(1, "Smoke test")
        graph.sidecars.write(host, creds, share, folder, name, ChapterSidecar.format(draft.chapters))
        val after = checkNotNull(graph.sidecars.read(host, creds, share, sidecar))
        val restored = runCatching {
            if (before != null) graph.sidecars.write(host, creds, share, folder, name, before)
            else graph.sidecars.delete(host, creds, share, folder, name)
        }
        check(ChapterSidecar.parse(after) == draft.chapters) { "sidecar read back differs:\n$after" }
        restored.getOrThrow()
        println("3. sidecar round-trip equal, fixture restored")
        exitProcess(0)
    } catch (e: Throwable) {
        System.err.println("SMOKE FAILED: $e")
        exitProcess(1)
    }
}
