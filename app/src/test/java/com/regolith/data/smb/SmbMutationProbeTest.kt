package com.regolith.data.smb

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.regolith.domain.smb.SmbCredentials
import com.regolith.domain.smb.SmbFailure
import com.regolith.domain.smb.SmbHost
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.net.Socket

/**
 * SPIKE PROBE (not a contract test yet): what does a real Samba server
 * actually do when we delete and move files, and does jcifs-ng tell us the
 * truth about it? Every question the "delete / bulk move" feature rests on
 * gets one test here, run against the local fixture (localhost:1445,
 * share `media` = ~/RegolithShare).
 *
 * Files are staged and verified with java.io.File — the share is a local
 * folder — so "what SMB claimed" and "what is on disk" are checked
 * independently. Everything happens inside a throwaway `Archive/probe-*`
 * folder that is deleted afterwards.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class SmbMutationProbeTest {
    private val root = File(System.getProperty("user.home"), "RegolithShare")
    private val host = SmbHost("localhost", PORT)
    private val guest = SmbCredentials.Guest
    private val gateway = JcifsGateway()

    private companion object { const val PORT = 1446 }

    private lateinit var probeRel: String
    private lateinit var probeDir: File

    @Before
    fun setUp() {
        assumeTrue("Writable Samba fixture is not running", runCatching { Socket("localhost", PORT).close() }.isSuccess)
        assumeTrue("fixture folder missing", File(root, "Films").isDirectory)
        probeRel = "Archive/probe-${System.nanoTime()}"
        probeDir = File(root, probeRel).apply { mkdirs() }
        File(probeDir, "to").mkdirs()
        // Settle the dialect the way a real session does.
        runBlocking { gateway.list(host, guest, "media", "Archive") }
    }

    @After
    fun tearDown() {
        if (::probeDir.isInitialized) probeDir.deleteRecursively()
    }

    private fun stage(name: String, bytes: Int = 64): File =
        File(probeDir, name).apply { parentFile?.mkdirs(); writeBytes(ByteArray(bytes) { it.toByte() }) }

    private fun rel(name: String) = "$probeRel/$name"

    // ── Q1: is a cross-folder move one server-side rename? ──────────────

    @Test
    fun `q1 rename moves a file into another folder`() = runBlocking {
        stage("a.mp4")
        gateway.rename(host, guest, "media", rel("a.mp4"), rel("to/a.mp4"))
        assertFalse("source still there", File(probeDir, "a.mp4").exists())
        assertTrue("target missing", File(probeDir, "to/a.mp4").exists())
    }

    @Test
    fun `q1b moving a large file costs no copy time`() = runBlocking {
        val big = File(root, "Films/Long.Test.2026.mp4")
        assumeTrue("large fixture missing", big.exists() && big.length() > 1_000_000)
        big.copyTo(File(probeDir, "big.mp4"))
        val ms = System.currentTimeMillis()
        gateway.rename(host, guest, "media", rel("big.mp4"), rel("to/big.mp4"))
        val elapsed = System.currentTimeMillis() - ms
        println("PROBE q1b: moved ${big.length()} bytes in ${elapsed}ms")
        assertTrue("target missing", File(probeDir, "to/big.mp4").exists())
    }

    // ── Q2: does moving a FOLDER move its whole subtree at once? ─────────

    @Test
    fun `q2 rename moves a directory with contents`() = runBlocking {
        stage("season/e1.mkv")
        stage("season/e2.mkv")
        val failure = runCatching { gateway.rename(host, guest, "media", rel("season"), rel("to/season")) }.exceptionOrNull()
        println("PROBE q2: ${failure ?: "moved"}")
        if (failure == null) {
            assertTrue("subtree did not arrive", File(probeDir, "to/season/e2.mkv").exists())
            assertFalse("source dir still there", File(probeDir, "season").exists())
        }
    }

    // ── Q3: what happens when the target name already exists? ───────────

    @Test
    fun `q3 rename onto an existing name`() = runBlocking {
        stage("src.mp4", bytes = 10)
        stage("to/src.mp4", bytes = 999)
        val failure = runCatching { gateway.rename(host, guest, "media", rel("src.mp4"), rel("to/src.mp4")) }.exceptionOrNull()
        val target = File(probeDir, "to/src.mp4")
        println("PROBE q3: ${failure ?: "replaced"}; target=${target.length()} bytes, source exists=${File(probeDir, "src.mp4").exists()}")
    }

    // ── Q4: does a move into a folder that is not there fail cleanly? ────

    @Test
    fun `q4 rename into a missing folder leaves the source alone`() = runBlocking {
        stage("keep.mp4")
        val failure = runCatching { gateway.rename(host, guest, "media", rel("keep.mp4"), rel("nope/keep.mp4")) }.exceptionOrNull()
        println("PROBE q4: ${failure?.let { it::class.simpleName + ": " + it.message } ?: "SILENTLY SUCCEEDED"}")
        assertTrue("source lost on a failed move", File(probeDir, "keep.mp4").exists())
    }

    // ── Q5: can we delete a file that is open for reading (playing)? ─────

    @Test
    fun `q5 delete while a read handle is open`() = runBlocking {
        stage("open.mp4", bytes = 4096)
        val src = gateway.open(host, guest, "media", rel("open.mp4"))
        try {
            val failure = runCatching { gateway.delete(host, guest, "media", rel("open.mp4")) }.exceptionOrNull()
            val gone = !File(probeDir, "open.mp4").exists()
            println("PROBE q5: ${failure?.let { it::class.simpleName + ": " + it.message } ?: "delete returned ok"}; gone=$gone")
            // Can the handle still read after the delete attempt?
            val buf = ByteArray(16)
            val n = runCatching { src.readAt(0, buf, 0, 16) }.getOrElse { -99 }
            println("PROBE q5: read after delete = $n")
        } finally {
            src.close()
        }
    }

    // ── Q6: is delete on a folder recursive? (footgun check) ─────────────

    @Test
    fun `q6 delete aimed at a directory with contents`() = runBlocking {
        stage("dir/inner.mkv")
        val failure = runCatching { gateway.delete(host, guest, "media", rel("dir")) }.exceptionOrNull()
        val dirGone = !File(probeDir, "dir").exists()
        println("PROBE q6: ${failure?.let { it::class.simpleName + ": " + it.message } ?: "delete returned ok"}; dirGone=$dirGone")
    }

    // ── Q7: deleting something that is not there ─────────────────────────

    @Test
    fun `q7 delete a name that does not exist`() = runBlocking {
        val failure = runCatching { gateway.delete(host, guest, "media", rel("ghost.mp4")) }.exceptionOrNull()
        println("PROBE q7: ${failure?.let { it::class.simpleName + ": " + it.message } ?: "no-op, no error"}")
        assertFalse("delete created something", File(probeDir, "ghost.mp4").exists())
    }

    // ── Q8: does delete actually confirm, or can it lie? ─────────────────

    @Test
    fun `q8 delete reports success only when the file is gone`() = runBlocking {
        stage("bye.mp4")
        gateway.delete(host, guest, "media", rel("bye.mp4"))
        assertFalse("file survived a successful delete", File(probeDir, "bye.mp4").exists())
        // And the listing agrees.
        val names = gateway.list(host, guest, "media", probeRel).map { it.name }
        assertFalse("listing still shows it", "bye.mp4" in names)
    }

    // ── Q9: case-only rename on a case-insensitive filesystem ────────────

    @Test
    fun `q9 case only rename`() = runBlocking {
        stage("case.mp4")
        val failure = runCatching { gateway.rename(host, guest, "media", rel("case.mp4"), rel("CASE.mp4")) }.exceptionOrNull()
        val names = probeDir.list()?.toList()
        println("PROBE q9: ${failure?.let { it::class.simpleName + ": " + it.message } ?: "renamed"}; names=$names")
    }

    // ── Q10: a batch that hits a bad item in the middle ──────────────────

    @Test
    fun `q10 a five item move batch with a poisoned third item`() = runBlocking {
        val names = listOf("b1.mp4", "b2.mp4", "b3.mp4", "b4.mp4", "b5.mp4")
        names.forEach { stage(it) }
        // Poison: b3's destination already exists as a DIRECTORY.
        File(probeDir, "to/b3.mp4").mkdirs()
        val results = names.map { n ->
            n to runCatching { gateway.rename(host, guest, "media", rel(n), rel("to/$n")) }.exceptionOrNull()
        }
        results.forEach { (n, e) -> println("PROBE q10: $n -> ${e?.let { it::class.simpleName } ?: "ok"}") }
        val moved = names.count { File(probeDir, "to/$it").isFile }
        val stranded = names.count { File(probeDir, it).exists() }
        println("PROBE q10: moved=$moved stranded=$stranded")
        assertEquals("a file was lost entirely", names.size, moved + stranded)
    }

    // ── Q11: is a delete of an OPEN file deferred, or silently dropped? ──

    @Test
    fun `q11 delete while open, then close`() = runBlocking {
        stage("defer.mp4", bytes = 4096)
        val onDisk = File(probeDir, "defer.mp4")
        val src = gateway.open(host, guest, "media", rel("defer.mp4"))
        val failure = runCatching { gateway.delete(host, guest, "media", rel("defer.mp4")) }.exceptionOrNull()
        val goneWhileOpen = !onDisk.exists()
        src.close()
        Thread.sleep(300)
        val goneAfterClose = !onDisk.exists()
        val listed = gateway.list(host, guest, "media", probeRel).map { it.name }
        println("PROBE q11: err=${failure?.let { it::class.simpleName }} goneWhileOpen=$goneWhileOpen goneAfterClose=$goneAfterClose stillListed=${"defer.mp4" in listed}")
    }

    // ── Q12: can a move cross share boundaries? ──────────────────────────

    @Test
    fun `q12 rename across shares`() = runBlocking {
        stage("cross.mp4")
        // Same server, different share: jcifs builds one URL per share, so
        // this is the question of whether a "move to" picker may span shares.
        val failure = runCatching {
            val from = "media/$probeRel/cross.mp4"
            gateway.rename(host, guest, "media2", "../$from", "landed.mp4")
        }.exceptionOrNull()
        println("PROBE q12: ${failure?.let { it::class.simpleName + ": " + it.message } ?: "SUCCEEDED"}")
        println("PROBE q12: source still there=${File(probeDir, "cross.mp4").exists()}")
    }

    // ── Q13: the server dies in the middle of a batch ────────────────────

    @Test
    fun `q13 batch interrupted by the share going away`() = runBlocking {
        // This one KILLS the fixture it is running against, so every probe
        // after it in the same run would fail. Opt in explicitly:
        //   REGOLITH_PROBE_DROP=1 ./gradlew testDebugUnitTest --tests '*q13*'
        assumeTrue("set REGOLITH_PROBE_DROP=1 to run the drop probe", System.getenv("REGOLITH_PROBE_DROP") == "1")
        val names = (1..6).map { "i$it.mp4" }
        names.forEach { stage(it) }
        val outcomes = mutableListOf<String>()
        for ((i, n) in names.withIndex()) {
            if (i == 3) {
                // Pull the share out from under the batch, mid-run.
                ProcessBuilder("pkill", "-f", "rg-smbw").start().waitFor()
                Thread.sleep(500)
            }
            val e = runCatching { gateway.rename(host, guest, "media", rel(n), rel("to/$n")) }.exceptionOrNull()
            outcomes += "$n=${e?.let { it::class.simpleName } ?: "ok"}"
        }
        println("PROBE q13: ${outcomes.joinToString(" ")}")
        val moved = names.count { File(probeDir, "to/$it").isFile }
        val stranded = names.count { File(probeDir, it).isFile }
        val halves = names.count { File(probeDir, "to/$it").isFile && File(probeDir, it).isFile }
        println("PROBE q13: moved=$moved stranded=$stranded duplicated=$halves total=${names.size}")
        assertEquals("a file vanished when the share went away", names.size, moved + stranded - halves)
    }

    // ── Q14: does replace=false actually protect the file already there? ──

    @Test
    fun `q14 rename refuses to clobber when replace is off`() = runBlocking {
        stage("src.mp4", bytes = 10)
        stage("to/src.mp4", bytes = 999)
        val failure = runCatching {
            gateway.rename(host, guest, "media", rel("src.mp4"), rel("to/src.mp4"), replace = false)
        }.exceptionOrNull()
        println("PROBE q14: ${failure?.let { it::class.simpleName + ": " + it.message } ?: "REPLACED IT ANYWAY"}")
        assertEquals("the file already there was destroyed", 999L, File(probeDir, "to/src.mp4").length())
        assertTrue("the source was consumed by a rename that should have failed", File(probeDir, "src.mp4").exists())
    }
}
