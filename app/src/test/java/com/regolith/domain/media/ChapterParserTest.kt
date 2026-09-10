package com.regolith.domain.media

import com.regolith.domain.playback.Chapter
import com.regolith.domain.smb.SeekableByteSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * The parser is pure, so the containers here are built byte by byte rather
 * than checked in as fixtures: a 60-line builder is easier to read — and to
 * mutate into the broken cases — than a hex blob.
 */
class ChapterParserTest {

    @Test fun `matroska chapters before the clusters`() {
        val mkv = matroska(
            chapters = listOf(0L to "Opening", 90_000L to "Act one", 1_800_000L to "Credits"),
            chaptersAfterClusters = false,
        )
        val out = ChapterParser.read(source(mkv))
        assertEquals(listOf(0L, 90_000L, 1_800_000L), out.map { it.startMs })
        assertEquals(listOf("Opening", "Act one", "Credits"), out.map { it.title })
    }

    @Test fun `matroska chapters after the clusters, found through SeekHead`() {
        val mkv = matroska(
            chapters = listOf(12_000L to "Cold open", 300_000L to "Titles"),
            chaptersAfterClusters = true,
        )
        val out = ChapterParser.read(source(mkv))
        assertEquals(listOf(12_000L, 300_000L), out.map { it.startMs })
        assertEquals("Cold open", out.first().title)
    }

    @Test fun `a hidden chapter is not offered`() {
        val mkv = matroska(listOf(0L to "Shown", 60_000L to "Hidden"), hiddenIndex = 1)
        assertEquals(listOf(0L), ChapterParser.read(source(mkv)).map { it.startMs })
    }

    @Test fun `a chapter with a time but no name falls back to its number`() {
        val mkv = matroska(listOf(0L to null, 45_000L to null))
        val out = ChapterParser.read(source(mkv))
        assertEquals(listOf(null, null), out.map { it.title })
        assertEquals("Chapter 2", out[1].label(1))
        assertEquals("Titles", Chapter(0, "Titles").label(0))
    }

    @Test fun `mp4 nero chapter list`() {
        val mp4 = mp4(listOf(0L to "Start", 61_000L to "Middle"))
        val out = ChapterParser.read(source(mp4))
        assertEquals(listOf(0L, 61_000L), out.map { it.startMs })
        assertEquals(listOf("Start", "Middle"), out.map { it.title })
    }

    @Test fun `a file with no chapters, an unknown container and a truncated one all read as empty`() {
        assertTrue(ChapterParser.read(source(matroska(emptyList()))).isEmpty())
        assertTrue(ChapterParser.read(source(mp4(emptyList()))).isEmpty())
        assertTrue(ChapterParser.read(source(ByteArray(64) { 0x7F })).isEmpty())
        assertTrue(ChapterParser.read(source(byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte()))).isEmpty())
        // A real header whose Chapters element claims more bytes than exist.
        val truncated = matroska(listOf(0L to "Opening")).copyOf(40)
        assertTrue(ChapterParser.read(source(truncated)).isEmpty())
    }

    @Test fun `markers arrive in time order with duplicates dropped`() {
        val mkv = matroska(listOf(90_000L to "Second", 0L to "First", 90_000L to "Also second"))
        assertEquals(listOf(0L, 90_000L), ChapterParser.read(source(mkv)).map { it.startMs })
    }

    // --- containers, built by hand -------------------------------------

    private fun source(bytes: ByteArray) = object : SeekableByteSource {
        override val size = bytes.size.toLong()
        override fun readAt(offset: Long, dst: ByteArray, dstOffset: Int, length: Int): Int {
            if (offset >= size) return -1
            val n = minOf(length.toLong(), size - offset).toInt()
            System.arraycopy(bytes, offset.toInt(), dst, dstOffset, n)
            return n
        }
        override fun close() = Unit
    }

    /** id bytes + a size as an 8-byte EBML vint + payload: valid, and never needs length maths. */
    private fun ebml(id: Long, payload: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        var shift = 56
        var started = false
        while (shift >= 0) {
            val b = ((id ushr shift) and 0xFF).toInt()
            if (b != 0) started = true
            if (started) out.write(b)
            shift -= 8
        }
        // 0x01 marker + 7 length bytes: the always-legal long form.
        out.write(0x01)
        for (i in 6 downTo 0) out.write(((payload.size.toLong() ushr (i * 8)) and 0xFF).toInt())
        out.write(payload)
        return out.toByteArray()
    }

    private fun uintElement(id: Long, value: Long): ByteArray {
        val bytes = ByteArrayOutputStream()
        var v = value
        val stack = ArrayDeque<Int>()
        do {
            stack.addFirst((v and 0xFF).toInt())
            v = v ushr 8
        } while (v != 0L)
        stack.forEach { bytes.write(it) }
        return ebml(id, bytes.toByteArray())
    }

    /** Big-endian unsigned in a FIXED width, so a layout does not shift when a value grows. */
    private fun uintFixed(id: Long, value: Long, bytes: Int): ByteArray {
        val out = ByteArrayOutputStream()
        for (i in bytes - 1 downTo 0) out.write(((value ushr (i * 8)) and 0xFF).toInt())
        return ebml(id, out.toByteArray())
    }

    private fun matroska(
        chapters: List<Pair<Long, String?>>,
        chaptersAfterClusters: Boolean = false,
        hiddenIndex: Int = -1,
    ): ByteArray {
        val atoms = ByteArrayOutputStream()
        chapters.forEachIndexed { i, (startMs, title) ->
            val atom = ByteArrayOutputStream()
            atom.write(uintElement(0x91, startMs * 1_000_000)) // ChapterTimeStart, nanoseconds
            if (i == hiddenIndex) atom.write(uintElement(0x98, 1)) // ChapterFlagHidden
            if (title != null) {
                atom.write(ebml(0x80, ebml(0x85, title.toByteArray(Charsets.UTF_8)))) // ChapterDisplay > ChapString
            }
            atoms.write(ebml(0xB6, atom.toByteArray())) // ChapterAtom
        }
        val chaptersElement = ebml(0x1043A770, ebml(0x45B9, atoms.toByteArray()))
        val cluster = ebml(0x1F43B675, ByteArray(512) { 0x11 })

        // SeekHead first, and always the same width: SeekPosition is written
        // in a fixed 8 bytes, so the offset it names does not move when it is
        // filled in. Positions are relative to the Segment's data start.
        val seek = ByteArrayOutputStream()
        seek.write(ebml(0x53AB, byteArrayOf(0x10, 0x43, 0xA7.toByte(), 0x70))) // SeekID = Chapters
        val seekHeadSize = ebml(0x114D9B74, ebml(0x4DBB, (seek.toByteArray() + uintFixed(0x53AC, 0, 8)))).size
        val chaptersPosition = (seekHeadSize + if (chaptersAfterClusters) cluster.size else 0).toLong()
        seek.write(uintFixed(0x53AC, chaptersPosition, 8))
        val seekHead = ebml(0x114D9B74, ebml(0x4DBB, seek.toByteArray()))
        require(seekHead.size == seekHeadSize)

        val body = ByteArrayOutputStream()
        body.write(seekHead)
        if (chaptersAfterClusters) {
            body.write(cluster)
            if (chapters.isNotEmpty()) body.write(chaptersElement)
        } else {
            if (chapters.isNotEmpty()) body.write(chaptersElement)
            body.write(cluster)
        }

        val out = ByteArrayOutputStream()
        out.write(ebml(0x1A45DFA3, byteArrayOf(0x42, 0x86.toByte(), 0x81.toByte(), 0x01))) // EBML header, contents irrelevant
        out.write(ebml(0x18538067, body.toByteArray())) // Segment
        return out.toByteArray()
    }

    private fun mp4(chapters: List<Pair<Long, String?>>): ByteArray {
        fun box(type: String, payload: ByteArray): ByteArray {
            val out = ByteArrayOutputStream()
            val size = payload.size + 8
            for (i in 3 downTo 0) out.write((size ushr (i * 8)) and 0xFF)
            out.write(type.toByteArray(Charsets.US_ASCII))
            out.write(payload)
            return out.toByteArray()
        }
        val chpl = ByteArrayOutputStream()
        chpl.write(1) // version 1
        repeat(3) { chpl.write(0) } // flags
        repeat(4) { chpl.write(0) } // the version-1-only field
        chpl.write(chapters.size)
        chapters.forEach { (startMs, title) ->
            val units = startMs * 10_000 // 100ns
            for (i in 7 downTo 0) chpl.write(((units ushr (i * 8)) and 0xFF).toInt())
            val name = (title ?: "").toByteArray(Charsets.UTF_8)
            chpl.write(name.size)
            chpl.write(name)
        }
        val udta = box("udta", if (chapters.isEmpty()) ByteArray(0) else box("chpl", chpl.toByteArray()))
        val out = ByteArrayOutputStream()
        out.write(box("ftyp", "isom".toByteArray(Charsets.US_ASCII)))
        out.write(box("mdat", ByteArray(256)))
        out.write(box("moov", udta))
        return out.toByteArray()
    }
}
