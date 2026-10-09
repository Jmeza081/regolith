package com.regolith.domain.media

import com.regolith.domain.smb.SeekableByteSource
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * What a picture's own header says about it, read without decoding a pixel:
 * its size as it is meant to be SEEN, when it was taken, and on what.
 *
 * [width] and [height] are already turned the way the picture is shown: a
 * phone photo stored 4032×3024 with "rotate 90°" in its EXIF is 3024×4032
 * here, because that is the shape of its tile in a mosaic. [takenAt] is the
 * camera's own wall-clock time ("14 Jul 2024, 20:41" where it was taken);
 * [takenOffset] is the time zone it was in, when the camera wrote one.
 * [camera] is the model and, when known, the aperture: "Pixel 8 · f/1.7".
 */
data class PictureFacts(
    val width: Int,
    val height: Int,
    val takenAt: LocalDateTime? = null,
    val takenOffset: ZoneOffset? = null,
    val camera: String? = null,
)

/**
 * Reads [PictureFacts] from the first few kilobytes of a picture, so a
 * mosaic can be laid out before a single thumbnail exists — the way a web
 * page gives an `<img>` its width and height to stop the page jumping as it
 * loads.
 *
 * Pure: it reads through [SeekableByteSource], so the same code reads a
 * file on the share, one on the phone, or a test's bytes. JPEG (its frame
 * header and EXIF), PNG, GIF, WebP and HEIF/AVIF (the ISO box structure a
 * phone's HEIC is). Anything it does not understand, or any picture whose
 * header is damaged, is null: the caller then decodes it the slow way, or
 * shows it square.
 */
object PictureHeaders {

    /** The facts, or null when the header is unreadable or the format unknown. */
    fun read(source: SeekableByteSource): PictureFacts? = runCatching {
        val head = Bytes(source).read(0, 32) ?: return null
        when {
            head.size >= 3 && head.u8(0) == 0xFF && head.u8(1) == 0xD8 && head.u8(2) == 0xFF -> jpeg(Bytes(source))
            head.size >= 24 && head.startsWith(PNG_SIGNATURE) -> png(head)
            head.size >= 10 && (head.ascii(0, 6) == "GIF87a" || head.ascii(0, 6) == "GIF89a") -> gif(head)
            head.size >= 16 && head.ascii(0, 4) == "RIFF" && head.ascii(8, 4) == "WEBP" -> webp(Bytes(source))
            head.size >= 12 && head.ascii(4, 4) == "ftyp" -> heif(Bytes(source))
            else -> null
        }
    }.getOrNull()?.takeIf { it.width > 0 && it.height > 0 }

    // --- JPEG

    private fun jpeg(bytes: Bytes): PictureFacts? {
        var at = 2L
        var exif: Exif? = null
        while (at < JPEG_SEARCH_LIMIT) {
            val marker = bytes.read(at, 4) ?: return null
            if (marker.u8(0) != 0xFF) return null
            val type = marker.u8(1)
            // Fill bytes: a marker may be padded with any number of 0xFF.
            if (type == 0xFF) {
                at++
                continue
            }
            // Markers that stand alone, with no length after them.
            if (type == 0x01 || type in 0xD0..0xD7) {
                at += 2
                continue
            }
            // Start of scan (or the end): the image data begins, and the frame header has not been seen.
            if (type == 0xDA || type == 0xD9) return null
            val length = marker.u16be(2)
            if (length < 2) return null
            if (type == 0xE1 && exif == null) {
                val segment = bytes.read(at + 4, length - 2)
                if (segment != null && segment.size >= 6 && segment.ascii(0, 4) == "Exif" && segment.u8(4) == 0 && segment.u8(5) == 0) {
                    exif = Exif.parse(segment, 6)
                }
            }
            if (type in SOF_MARKERS) {
                val frame = bytes.read(at + 4, 5) ?: return null
                val height = frame.u16be(1)
                val width = frame.u16be(3)
                return oriented(width, height, exif?.orientation ?: 1, exif)
            }
            at += 2 + length
        }
        return null
    }

    /** The JPEG frame headers (SOF0–SOF15, less DHT, JPG and DAC), the ones that carry the size. */
    private val SOF_MARKERS = setOf(0xC0, 0xC1, 0xC2, 0xC3, 0xC5, 0xC6, 0xC7, 0xC9, 0xCA, 0xCB, 0xCD, 0xCE, 0xCF)

    // --- PNG and GIF: the size sits at a fixed place.

    private fun png(head: ByteArray): PictureFacts? {
        if (head.ascii(12, 4) != "IHDR") return null
        return PictureFacts(width = head.u32be(16).toInt(), height = head.u32be(20).toInt())
    }

    private fun gif(head: ByteArray): PictureFacts = PictureFacts(width = head.u16le(6), height = head.u16le(8))

    // --- WebP: the first chunk says, in one of three ways. No orientation:
    // a WebP is decoded the way it is stored.

    private fun webp(bytes: Bytes): PictureFacts? {
        val chunk = bytes.read(12, 30) ?: return null
        val data = 8
        return when (chunk.ascii(0, 4)) {
            "VP8 " -> {
                if (chunk.u8(data + 3) != 0x9D || chunk.u8(data + 4) != 0x01 || chunk.u8(data + 5) != 0x2A) return null
                PictureFacts(width = chunk.u16le(data + 6) and 0x3FFF, height = chunk.u16le(data + 8) and 0x3FFF)
            }
            "VP8L" -> {
                if (chunk.u8(data) != 0x2F) return null
                val bits = chunk.u32le(data + 1)
                PictureFacts(width = (bits and 0x3FFF).toInt() + 1, height = ((bits shr 14) and 0x3FFF).toInt() + 1)
            }
            "VP8X" -> PictureFacts(width = chunk.u24le(data + 4) + 1, height = chunk.u24le(data + 7) + 1)
            else -> null
        }
    }

    // --- HEIF and AVIF: ISO boxes. The size is the primary item's `ispe`,
    // turned by its `irot`; the EXIF is an item of its own, wherever `iloc`
    // says it is (usually just after the boxes, in the first block read).

    private fun heif(bytes: Bytes): PictureFacts? {
        val ftypSize = bytes.read(0, 4)?.u32be(0) ?: return null
        val brands = bytes.read(8, (ftypSize - 8).coerceIn(4, 64).toInt()) ?: return null
        val knownBrand = (0 until brands.size / 4).any { i -> brands.ascii(i * 4, 4) in HEIF_BRANDS }
        if (!knownBrand) return null
        // The top-level boxes, looking for `meta`.
        var at = ftypSize
        while (at < HEIF_SEARCH_LIMIT) {
            val header = bytes.read(at, 16) ?: return null
            val (size, headerLength) = boxSize(header, bytes.size - at) ?: return null
            if (header.ascii(4, 4) == "meta") {
                val meta = bytes.read(at + headerLength, (size - headerLength).coerceAtMost(HEIF_META_LIMIT).toInt()) ?: return null
                // A full box: version and flags before its children.
                return heifMeta(meta, 4, meta.size, bytes)
            }
            at += size
        }
        return null
    }

    private fun heifMeta(meta: ByteArray, from: Int, to: Int, bytes: Bytes): PictureFacts? {
        var primary: Long? = null
        val properties = mutableListOf<Pair<String, ByteArray>>()
        val associations = mutableMapOf<Long, List<Int>>()
        var exifItem: Long? = null
        val locations = mutableMapOf<Long, Pair<Long, Long>>()
        children(meta, from, to) { type, start, end ->
            when (type) {
                "pitm" -> primary = if (meta.u8(start) == 0) meta.u16be(start + 4).toLong() else meta.u32be(start + 4)
                "iinf" -> exifItem = exifItemOf(meta, start, end)
                "iloc" -> locations += itemLocations(meta, start, end)
                "iprp" -> children(meta, start, end) { inner, s, e ->
                    when (inner) {
                        "ipco" -> children(meta, s, e) { property, ps, pe -> properties += property to meta.copyOfRange(ps, pe) }
                        "ipma" -> associations += propertyAssociations(meta, s, e)
                    }
                }
            }
        }
        val item = primary ?: return null
        val own = associations[item].orEmpty().mapNotNull { properties.getOrNull(it - 1) }
        val ispe = own.firstOrNull { it.first == "ispe" }?.second ?: return null
        val width = ispe.u32be(4).toInt()
        val height = ispe.u32be(8).toInt()
        val quarterTurns = own.firstOrNull { it.first == "irot" }?.second?.let { it.u8(0) and 0x03 } ?: 0
        val exif = exifItem?.let { locations[it] }?.let { (offset, length) -> heifExif(bytes, offset, length) }
        // A HEIF is turned by its `irot`, never by the EXIF orientation beside it
        // (the format says so, and the phone's decoder agrees).
        val turned = quarterTurns % 2 == 1
        return PictureFacts(
            width = if (turned) height else width,
            height = if (turned) width else height,
            takenAt = exif?.takenAt,
            takenOffset = exif?.takenOffset,
            camera = exif?.camera,
        )
    }

    /** The id of the item whose type is `Exif`, from `iinf`'s `infe` entries (version 2 and up carry a type). */
    private fun exifItemOf(meta: ByteArray, start: Int, end: Int): Long? {
        val version = meta.u8(start)
        val first = start + 4 + if (version == 0) 2 else 4
        var found: Long? = null
        children(meta, first, end) { type, s, _ ->
            if (type != "infe" || found != null) return@children
            val infeVersion = meta.u8(s)
            if (infeVersion < 2) return@children
            val idLength = if (infeVersion == 2) 2 else 4
            val id = if (idLength == 2) meta.u16be(s + 4).toLong() else meta.u32be(s + 4)
            val itemType = meta.ascii(s + 4 + idLength + 2, 4)
            if (itemType == "Exif") found = id
        }
        return found
    }

    /** Where each item's bytes are in the file (`iloc`): its first extent's absolute offset and length. */
    private fun itemLocations(meta: ByteArray, start: Int, end: Int): Map<Long, Pair<Long, Long>> {
        val version = meta.u8(start)
        var at = start + 4
        val sizes = meta.u8(at)
        val offsetSize = sizes shr 4
        val lengthSize = sizes and 0x0F
        val more = meta.u8(at + 1)
        val baseOffsetSize = more shr 4
        val indexSize = if (version == 1 || version == 2) more and 0x0F else 0
        at += 2
        val count = if (version < 2) meta.u16be(at).also { at += 2 }.toLong() else meta.u32be(at).also { at += 4 }
        val out = mutableMapOf<Long, Pair<Long, Long>>()
        repeat(count.toInt().coerceAtMost(4096)) {
            if (at >= end) return out
            val id = if (version < 2) meta.u16be(at).also { at += 2 }.toLong() else meta.u32be(at).also { at += 4 }
            // Construction method 0 is "an offset in this file"; anything else is not something to read here.
            val method = if (version == 1 || version == 2) (meta.u16be(at) and 0x0F).also { at += 2 } else 0
            at += 2 // data reference index
            val base = meta.uN(at, baseOffsetSize).also { at += baseOffsetSize }
            val extents = meta.u16be(at).also { at += 2 }
            var first: Pair<Long, Long>? = null
            repeat(extents) {
                at += indexSize
                val offset = meta.uN(at, offsetSize).also { at += offsetSize }
                val length = meta.uN(at, lengthSize).also { at += lengthSize }
                if (first == null) first = (base + offset) to length
            }
            if (method == 0) first?.let { out[id] = it }
        }
        return out
    }

    /** Which properties belong to which item (`ipma`): 1-based indexes into `ipco`. */
    private fun propertyAssociations(meta: ByteArray, start: Int, end: Int): Map<Long, List<Int>> {
        val version = meta.u8(start)
        val wide = (meta.u8(start + 3) and 1) == 1
        var at = start + 4
        val count = meta.u32be(at).also { at += 4 }
        val out = mutableMapOf<Long, List<Int>>()
        repeat(count.toInt().coerceAtMost(4096)) {
            if (at >= end) return out
            val id = if (version < 1) meta.u16be(at).also { at += 2 }.toLong() else meta.u32be(at).also { at += 4 }
            val n = meta.u8(at).also { at += 1 }
            out[id] = List(n) {
                if (wide) (meta.u16be(at) and 0x7FFF).also { at += 2 } else (meta.u8(at) and 0x7F).also { at += 1 }
            }
        }
        return out
    }

    /** A HEIF's EXIF item: four bytes saying where its TIFF header starts, then the EXIF itself. */
    private fun heifExif(bytes: Bytes, offset: Long, length: Long): Exif? {
        if (length < 8 || length > EXIF_LIMIT) return null
        val data = bytes.read(offset, length.toInt()) ?: return null
        val tiff = 4 + data.u32be(0).toInt()
        return if (tiff in 4 until data.size) Exif.parse(data, tiff) else null
    }

    /** Box size and header length at the start of [header]: 64-bit sizes and "to the end" (0) included. */
    private fun boxSize(header: ByteArray, remaining: Long): Pair<Long, Int>? {
        val size32 = header.u32be(0)
        return when (size32) {
            1L -> header.u64be(8) to 16
            0L -> remaining to 8
            else -> size32 to 8
        }.takeIf { it.first >= it.second }
    }

    /** Calls [visit] with each child box of `[from, to)`: its type and where its contents start and end. */
    private inline fun children(data: ByteArray, from: Int, to: Int, visit: (type: String, start: Int, end: Int) -> Unit) {
        var at = from
        while (at + 8 <= to) {
            val (size, headerLength) = boxSize(data.copyOfRange(at, minOf(at + 16, to)).let { if (it.size < 16) it + ByteArray(16 - it.size) else it }, (to - at).toLong())
                ?: return
            val end = (at + size).coerceAtMost(to.toLong()).toInt()
            visit(data.ascii(at + 4, 4), at + headerLength, end)
            if (size <= 0) return
            at = end
        }
    }

    // --- EXIF (a TIFF header and its IFDs), shared by JPEG and HEIF

    private class Exif(
        val orientation: Int,
        val takenAt: LocalDateTime?,
        val takenOffset: ZoneOffset?,
        val camera: String?,
    ) {
        companion object {
            fun parse(data: ByteArray, tiff: Int): Exif? {
                if (tiff + 8 > data.size) return null
                val little = when (data.ascii(tiff, 2)) {
                    "II" -> true
                    "MM" -> false
                    else -> return null
                }
                val t = Tiff(data, tiff, little)
                if (t.u16(2) != 42) return null
                val ifd0 = t.entries(t.u32(4))
                val exifIfd = ifd0[TAG_EXIF_IFD]?.let { t.entries(t.long(it)) }.orEmpty()
                val orientation = ifd0[TAG_ORIENTATION]?.let { t.short(it) } ?: 1
                val taken = (exifIfd[TAG_DATE_TIME_ORIGINAL] ?: ifd0[TAG_DATE_TIME])?.let { t.ascii(it) }?.let(::exifDate)
                val offset = exifIfd[TAG_OFFSET_TIME_ORIGINAL]?.let { t.ascii(it) }?.let { runCatching { ZoneOffset.of(it.trim()) }.getOrNull() }
                val model = ifd0[TAG_MODEL]?.let { t.ascii(it) }?.trim()?.ifEmpty { null }
                val aperture = exifIfd[TAG_F_NUMBER]?.let { t.rational(it) }?.takeIf { it > 0.0 && it < 100.0 }
                val camera = model?.let { m -> if (aperture != null) "$m · f/${"%.1f".format(java.util.Locale.ROOT, aperture)}" else m }
                return Exif(orientation, taken, offset, camera)
            }

            /** "2024:07:14 20:41:09"; the all-zero date some cameras write means "not set". */
            private fun exifDate(text: String): LocalDateTime? =
                runCatching { LocalDateTime.parse(text.trim().take(19), EXIF_DATE) }.getOrNull()

            private val EXIF_DATE = DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss")

            const val TAG_ORIENTATION = 0x0112
            const val TAG_MODEL = 0x0110
            const val TAG_DATE_TIME = 0x0132
            const val TAG_EXIF_IFD = 0x8769
            const val TAG_DATE_TIME_ORIGINAL = 0x9003
            const val TAG_OFFSET_TIME_ORIGINAL = 0x9011
            const val TAG_F_NUMBER = 0x829D
        }
    }

    /** One IFD entry: its type, count, and the four bytes that are its value or point at it. */
    private class Entry(val type: Int, val count: Long, val valueAt: Int)

    /** Reads a TIFF structure inside [data] starting at [base], in its own byte order. */
    private class Tiff(private val data: ByteArray, private val base: Int, private val little: Boolean) {
        fun u16(at: Int): Int = if (little) data.u16le(base + at) else data.u16be(base + at)
        fun u32(at: Int): Long = if (little) data.u32le(base + at) else data.u32be(base + at)

        fun entries(ifd: Long): Map<Int, Entry> {
            val at = ifd.toInt()
            if (at <= 0 || base + at + 2 > data.size) return emptyMap()
            val count = u16(at).coerceAtMost(512)
            val out = mutableMapOf<Int, Entry>()
            for (i in 0 until count) {
                val e = at + 2 + i * 12
                if (base + e + 12 > data.size) break
                out[u16(e)] = Entry(type = u16(e + 2), count = u32(e + 4), valueAt = e + 8)
            }
            return out
        }

        fun short(entry: Entry): Int? = if (entry.type == 3) u16(entry.valueAt) else null

        fun long(entry: Entry): Long = if (entry.type == 3) u16(entry.valueAt).toLong() else u32(entry.valueAt)

        fun ascii(entry: Entry): String? {
            if (entry.type != 2 || entry.count <= 0 || entry.count > 256) return null
            val n = entry.count.toInt()
            val at = if (n <= 4) entry.valueAt else u32(entry.valueAt).toInt()
            if (base + at + n > data.size || at < 0) return null
            return String(data, base + at, n, Charsets.ISO_8859_1).trimEnd('\u0000').ifEmpty { null }
        }

        fun rational(entry: Entry): Double? {
            if (entry.type != 5) return null
            val at = u32(entry.valueAt).toInt()
            if (at < 0 || base + at + 8 > data.size) return null
            val numerator = u32(at)
            val denominator = u32(at + 4)
            return if (denominator == 0L) null else numerator.toDouble() / denominator
        }
    }

    /** [width] × [height] as stored, turned the way EXIF [orientation] 5–8 says (a quarter turn). */
    private fun oriented(width: Int, height: Int, orientation: Int, exif: Exif?): PictureFacts {
        val turned = orientation in 5..8
        return PictureFacts(
            width = if (turned) height else width,
            height = if (turned) width else height,
            takenAt = exif?.takenAt,
            takenOffset = exif?.takenOffset,
            camera = exif?.camera,
        )
    }

    /** Reads whole runs of bytes at an offset, looping over short reads; null past the end. */
    private class Bytes(private val source: SeekableByteSource) {
        val size: Long get() = source.size

        fun read(offset: Long, length: Int): ByteArray? {
            if (offset < 0 || length < 0 || offset >= source.size) return null
            val want = minOf(length.toLong(), source.size - offset).toInt()
            val out = ByteArray(want)
            var got = 0
            while (got < want) {
                val n = source.readAt(offset + got, out, got, want - got)
                if (n <= 0) break
                got += n
            }
            return if (got == want) out else out.copyOf(got).takeIf { got > 0 }
        }
    }

    private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    /** The HEIF family: still pictures and sequences in HEVC, the generic image brands, and AVIF. */
    private val HEIF_BRANDS = setOf("heic", "heix", "hevc", "hevx", "heim", "heis", "mif1", "msf1", "avif", "avis")

    /** How far into a JPEG to look for its frame header; past this the EXIF thumbnail or a maker note has gone wrong. */
    private const val JPEG_SEARCH_LIMIT = 2L * 1024 * 1024

    /** How far into a HEIF to look for its `meta` box, which is near the start in every phone's file. */
    private const val HEIF_SEARCH_LIMIT = 1L * 1024 * 1024
    private const val HEIF_META_LIMIT = 512L * 1024
    private const val EXIF_LIMIT = 256L * 1024
}

// Little readers over a byte array; every one masks to unsigned.
private fun ByteArray.u8(at: Int): Int = this[at].toInt() and 0xFF
private fun ByteArray.u16be(at: Int): Int = (u8(at) shl 8) or u8(at + 1)
private fun ByteArray.u16le(at: Int): Int = u8(at) or (u8(at + 1) shl 8)
private fun ByteArray.u24le(at: Int): Int = u8(at) or (u8(at + 1) shl 8) or (u8(at + 2) shl 16)
private fun ByteArray.u32be(at: Int): Long = (u16be(at).toLong() shl 16) or u16be(at + 2).toLong()
private fun ByteArray.u32le(at: Int): Long = u16le(at).toLong() or (u16le(at + 2).toLong() shl 16)
private fun ByteArray.u64be(at: Int): Long = (u32be(at) shl 32) or u32be(at + 4)

/** A big-endian number of [length] bytes (0, 4 or 8, as `iloc` sizes them). */
private fun ByteArray.uN(at: Int, length: Int): Long = when (length) {
    0 -> 0L
    4 -> u32be(at)
    8 -> u64be(at)
    else -> (0 until length).fold(0L) { acc, i -> (acc shl 8) or u8(at + i).toLong() }
}

private fun ByteArray.ascii(at: Int, length: Int): String =
    if (at < 0 || at + length > size) "" else String(this, at, length, Charsets.ISO_8859_1)

private fun ByteArray.startsWith(prefix: ByteArray): Boolean = size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }
