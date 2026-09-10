package com.regolith.domain.media

import com.regolith.domain.playback.Chapter
import com.regolith.domain.smb.SeekableByteSource

/**
 * Reads chapter markers out of a container, by hand.
 *
 * Why by hand: nothing on the platform will do it. `MediaMetadataRetriever`
 * has no chapter API at all, and Media3's extractors parse what they need
 * to *play* a file — Matroska's `Chapters` element and MP4's `chpl` box are
 * not that, so neither extractor keeps them. The formats are small and
 * stable, so this is a page of parsing rather than a dependency.
 *
 * Pure: it reads through a [SeekableByteSource] and returns a list. That
 * makes it testable from a byte array, which is how the tests drive it.
 *
 * What it understands:
 *  - **Matroska / WebM** — `Segment > Chapters > EditionEntry > ChapterAtom`,
 *    using `SeekHead` when the chapters sit after the clusters.
 *  - **MP4 / M4V / MOV** — the Nero `chpl` box in `moov > udta`.
 *
 * What it does not, deliberately: QuickTime *chapter tracks* (a text track
 * referenced by a `tref` of type `chap`), which means reading the sample
 * tables and then the samples themselves — several times this much code for
 * a form almost nothing writes any more.
 *
 * Every read is guarded: a malformed or truncated file yields an empty
 * list, never an exception thrown at the caller.
 */
object ChapterParser {

    /** How far into the file to keep looking before giving up. */
    private const val WINDOW = 64 * 1024

    /** A file with more markers than this is mislabelled data, not chapters. */
    private const val MAX_CHAPTERS = 999

    /** Chapters, in time order, or empty when the file has none. */
    fun read(source: SeekableByteSource): List<Chapter> = try {
        val bytes = Cursor(source)
        val chapters = when {
            bytes.u32(0) == 0x1A45DFA3L -> matroska(bytes)
            bytes.u32(4) == 0x66747970L -> mp4(bytes) // 'ftyp'
            else -> emptyList()
        }
        chapters.asSequence()
            .filter { it.startMs >= 0 }
            .distinctBy { it.startMs }
            .sortedBy { it.startMs }
            .take(MAX_CHAPTERS)
            .toList()
    } catch (e: Exception) {
        emptyList()
    }

    // ------------------------------------------------------------------
    // Matroska (EBML)
    // ------------------------------------------------------------------

    private const val ID_SEGMENT = 0x18538067L
    private const val ID_SEEK_HEAD = 0x114D9B74L
    private const val ID_SEEK = 0x4DBBL
    private const val ID_SEEK_ID = 0x53ABL
    private const val ID_SEEK_POSITION = 0x53ACL
    private const val ID_CLUSTER = 0x1F43B675L
    private const val ID_CHAPTERS = 0x1043A770L
    private const val ID_EDITION_ENTRY = 0x45B9L
    private const val ID_CHAPTER_ATOM = 0xB6L
    private const val ID_CHAPTER_TIME_START = 0x91L
    private const val ID_CHAPTER_FLAG_HIDDEN = 0x98L
    private const val ID_CHAPTER_DISPLAY = 0x80L
    private const val ID_CHAP_STRING = 0x85L

    private fun matroska(c: Cursor): List<Chapter> {
        val segment = topLevel(c, 0L, c.size, ID_SEGMENT) ?: return emptyList()
        // Chapters usually sit before the first cluster; when they do not,
        // SeekHead says where they are. Scanning past the clusters instead
        // would be thousands of small reads over the share.
        var seekHeadChapters = -1L
        var at = segment.dataStart
        val end = segment.end(c.size)
        while (at < end) {
            val e = element(c, at) ?: break
            when (e.id) {
                ID_CHAPTERS -> return chaptersElement(c, e)
                ID_SEEK_HEAD -> seekHeadChapters = seekHeadEntry(c, e, segment.dataStart)
                ID_CLUSTER -> break // the header is behind us; ask SeekHead
            }
            at = e.next(c.size) ?: break
        }
        if (seekHeadChapters in 0 until c.size) {
            val e = element(c, seekHeadChapters)
            if (e != null && e.id == ID_CHAPTERS) return chaptersElement(c, e)
        }
        return emptyList()
    }

    /** The position of the Chapters element as SeekHead reports it, or -1. */
    private fun seekHeadEntry(c: Cursor, head: Element, segmentStart: Long): Long {
        var at = head.dataStart
        val end = head.end(c.size)
        while (at < end) {
            val seek = element(c, at) ?: return -1
            if (seek.id == ID_SEEK) {
                var id = -1L
                var position = -1L
                var inner = seek.dataStart
                val innerEnd = seek.end(c.size)
                while (inner < innerEnd) {
                    val f = element(c, inner) ?: break
                    when (f.id) {
                        ID_SEEK_ID -> id = c.uint(f.dataStart, f.size.toInt())
                        ID_SEEK_POSITION -> position = c.uint(f.dataStart, f.size.toInt())
                    }
                    inner = f.next(c.size) ?: break
                }
                if (id == ID_CHAPTERS && position >= 0) return segmentStart + position
            }
            at = seek.next(c.size) ?: return -1
        }
        return -1
    }

    private fun chaptersElement(c: Cursor, chapters: Element): List<Chapter> {
        var at = chapters.dataStart
        val end = chapters.end(c.size)
        while (at < end) {
            val edition = element(c, at) ?: break
            // The first edition is the one the file means; alternate editions
            // (a director's cut ordering, say) are a form nothing plays.
            if (edition.id == ID_EDITION_ENTRY) return chaptersOf(c, edition)
            at = edition.next(c.size) ?: break
        }
        return emptyList()
    }

    private fun chaptersOf(c: Cursor, edition: Element): List<Chapter> {
        val out = mutableListOf<Chapter>()
        var at = edition.dataStart
        val end = edition.end(c.size)
        while (at < end && out.size < MAX_CHAPTERS) {
            val atom = element(c, at) ?: break
            // Only the top level: a nested ChapterAtom is a sub-chapter, and
            // flattening them would put two markers on one moment.
            if (atom.id == ID_CHAPTER_ATOM) atomOf(c, atom)?.let { out += it }
            at = atom.next(c.size) ?: break
        }
        return out
    }

    private fun atomOf(c: Cursor, atom: Element): Chapter? {
        var startNs = -1L
        var hidden = false
        var title: String? = null
        var at = atom.dataStart
        val end = atom.end(c.size)
        while (at < end) {
            val f = element(c, at) ?: break
            when (f.id) {
                // Not scaled by TimestampScale: the spec fixes ChapterTimeStart
                // in nanoseconds, unlike almost every other timestamp in EBML.
                ID_CHAPTER_TIME_START -> startNs = c.uint(f.dataStart, f.size.toInt())
                ID_CHAPTER_FLAG_HIDDEN -> hidden = c.uint(f.dataStart, f.size.toInt()) != 0L
                ID_CHAPTER_DISPLAY -> if (title == null) title = chapString(c, f)
            }
            at = f.next(c.size) ?: break
        }
        if (hidden || startNs < 0) return null
        return Chapter(startMs = startNs / 1_000_000, title = title)
    }

    private fun chapString(c: Cursor, display: Element): String? {
        var at = display.dataStart
        val end = display.end(c.size)
        while (at < end) {
            val f = element(c, at) ?: break
            if (f.id == ID_CHAP_STRING) return c.utf8(f.dataStart, f.size.toInt())
            at = f.next(c.size) ?: break
        }
        return null
    }

    /** Walks the top level looking for one id, skipping whole elements by their size. */
    private fun topLevel(c: Cursor, from: Long, until: Long, id: Long): Element? {
        var at = from
        while (at < until) {
            val e = element(c, at) ?: return null
            if (e.id == id) return e
            at = e.next(c.size) ?: return null
        }
        return null
    }

    private data class Element(val id: Long, val dataStart: Long, val size: Long) {
        /** Long.MAX_VALUE marks EBML's "unknown size", which only Segment and Cluster use. */
        val unknown get() = size == Long.MAX_VALUE
        fun end(fileSize: Long) = if (unknown) fileSize else minOf(fileSize, dataStart + size)
        /** Where the next sibling starts, or null when this element runs to the end. */
        fun next(fileSize: Long): Long? {
            if (unknown) return null
            val n = dataStart + size
            return if (n > dataStart && n <= fileSize) n else null
        }
    }

    private fun element(c: Cursor, at: Long): Element? {
        if (at < 0 || at >= c.size) return null
        val id = vint(c, at, keepMarker = true) ?: return null
        val size = vint(c, at + id.length, keepMarker = false) ?: return null
        val dataStart = at + id.length + size.length
        if (dataStart > c.size) return null
        val bytes = if (size.unknown) Long.MAX_VALUE else size.value
        return Element(id.value, dataStart, bytes)
    }

    private data class Vint(val value: Long, val length: Int, val unknown: Boolean)

    private fun vint(c: Cursor, at: Long, keepMarker: Boolean): Vint? {
        val first = c.u8(at)
        if (first == 0) return null // more than 8 bytes: not something we read
        var length = 1
        var mask = 0x80
        while (first and mask == 0) {
            mask = mask shr 1
            length++
        }
        var value = if (keepMarker) first.toLong() else (first and (mask - 1)).toLong()
        for (i in 1 until length) value = (value shl 8) or c.u8(at + i).toLong()
        // All data bits set = "size unknown"; the marker bit is not part of that test.
        val allOnes = (1L shl (7 * length)) - 1
        val unknown = !keepMarker && value == allOnes
        return Vint(value, length, unknown)
    }

    // ------------------------------------------------------------------
    // MP4 / QuickTime
    // ------------------------------------------------------------------

    private fun mp4(c: Cursor): List<Chapter> {
        val moov = box(c, 0L, c.size, "moov") ?: return emptyList()
        val udta = box(c, moov.dataStart, moov.dataEnd, "udta") ?: return emptyList()
        val list = box(c, udta.dataStart, udta.dataEnd, "chpl") ?: return emptyList()
        return neroChapters(c, list)
    }

    /**
     * Nero's chapter list. Version, flags, a version-1-only 32-bit field
     * nobody documents, then a byte of count and, per chapter, a 64-bit
     * start in 100-nanosecond units and a length-prefixed UTF-8 title.
     */
    private fun neroChapters(c: Cursor, chpl: Box): List<Chapter> {
        var at = chpl.dataStart
        if (at + 5 > chpl.dataEnd) return emptyList()
        val version = c.u8(at)
        at += 4 // version + flags
        if (version != 0) at += 4
        val count = c.u8(at); at += 1
        val out = mutableListOf<Chapter>()
        for (i in 0 until count) {
            if (at + 9 > chpl.dataEnd) break
            val start100ns = c.uint(at, 8); at += 8
            val length = c.u8(at); at += 1
            if (at + length > chpl.dataEnd) break
            val title = c.utf8(at, length); at += length
            out += Chapter(startMs = start100ns / 10_000, title = title)
        }
        return out
    }

    private data class Box(val type: String, val dataStart: Long, val dataEnd: Long)

    /** The first child box of [type] between [from] and [until]. */
    private fun box(c: Cursor, from: Long, until: Long, type: String): Box? {
        var at = from
        while (at + 8 <= until) {
            var size = c.uint(at, 4)
            val name = c.ascii(at + 4, 4)
            var dataStart = at + 8
            when (size) {
                1L -> {
                    if (at + 16 > until) return null
                    size = c.uint(at + 8, 8)
                    dataStart = at + 16
                }
                0L -> size = until - at // "to the end of the file"
            }
            if (size < 8) return null
            val boxEnd = minOf(until, at + size)
            if (name == type) return Box(name, dataStart, boxEnd)
            at += size
        }
        return null
    }

    // ------------------------------------------------------------------

    /**
     * Random access with one buffered window. Chapter parsing is many tiny
     * reads over a small region, which is the worst possible shape for a
     * share; a [WINDOW]-sized window turns them into a handful of round
     * trips.
     */
    private class Cursor(private val source: SeekableByteSource) {
        val size: Long = source.size
        private val block = ByteArray(WINDOW)
        private var start = -1L
        private var length = 0

        fun u8(at: Long): Int {
            require(at in 0 until size) { "read past the end" }
            if (start < 0 || at < start || at >= start + length) fill(at)
            return block[(at - start).toInt()].toInt() and 0xFF
        }

        /** Big-endian unsigned, 1..8 bytes. Longer than 8 is a malformed field. */
        fun uint(at: Long, bytes: Int): Long {
            require(bytes in 1..8) { "field of $bytes bytes" }
            var v = 0L
            for (i in 0 until bytes) v = (v shl 8) or u8(at + i).toLong()
            return v
        }

        fun u32(at: Long): Long = if (at + 4 <= size) uint(at, 4) else -1L

        fun ascii(at: Long, bytes: Int): String =
            String(CharArray(bytes) { u8(at + it).toChar() })

        fun utf8(at: Long, bytes: Int): String? {
            if (bytes <= 0 || at + bytes > size) return null
            return String(ByteArray(bytes) { u8(at + it).toByte() }, Charsets.UTF_8).trim().takeIf { it.isNotEmpty() }
        }

        private fun fill(at: Long) {
            start = at
            length = 0
            val want = minOf(block.size.toLong(), size - at).toInt()
            while (length < want) {
                val n = source.readAt(at + length, block, length, want - length)
                if (n <= 0) break
                length += n
            }
            require(length > 0) { "no bytes at $at" }
        }
    }
}
