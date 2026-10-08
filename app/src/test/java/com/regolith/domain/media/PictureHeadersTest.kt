package com.regolith.domain.media

import com.regolith.domain.smb.SeekableByteSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * What a picture's header says, for every format the Library shows: built
 * byte by byte here, so each test is exactly the structure it is about.
 */
class PictureHeadersTest {

    @Test
    fun `a JPEG's size comes from its frame header, turned by its EXIF orientation`() {
        val facts = PictureHeaders.read(source(jpeg(width = 4032, height = 3024, orientation = 6)))!!

        assertEquals("stored landscape, shown upright", 3024, facts.width)
        assertEquals(4032, facts.height)
    }

    @Test
    fun `a JPEG with no turn in its EXIF is the size it is stored at`() {
        val facts = PictureHeaders.read(source(jpeg(width = 4032, height = 3024, orientation = 1)))!!

        assertEquals(4032, facts.width)
        assertEquals(3024, facts.height)
    }

    @Test
    fun `a JPEG's EXIF says when it was taken, in which zone, and on what`() {
        val facts = PictureHeaders.read(
            source(jpeg(width = 400, height = 300, orientation = 1, taken = "2024:07:14 20:41:09", offset = "+02:00", model = "Pixel 8", fNumber = 17 to 10)),
        )!!

        assertEquals(LocalDateTime.of(2024, 7, 14, 20, 41, 9), facts.takenAt)
        assertEquals(ZoneOffset.ofHours(2), facts.takenOffset)
        assertEquals("Pixel 8 · f/1.7", facts.camera)
    }

    @Test
    fun `a JPEG with no EXIF still has its size`() {
        val facts = PictureHeaders.read(source(jpeg(width = 640, height = 480, exif = false)))!!

        assertEquals(640, facts.width)
        assertEquals(480, facts.height)
        assertNull(facts.takenAt)
        assertNull(facts.camera)
    }

    @Test
    fun `a progressive JPEG's frame header counts too`() {
        val facts = PictureHeaders.read(source(jpeg(width = 800, height = 1200, exif = false, sof = 0xC2)))!!

        assertEquals(800, facts.width)
        assertEquals(1200, facts.height)
    }

    @Test
    fun `an all-zero EXIF date means none`() {
        val facts = PictureHeaders.read(source(jpeg(width = 10, height = 10, orientation = 1, taken = "0000:00:00 00:00:00")))!!

        assertNull(facts.takenAt)
    }

    @Test
    fun `a big-endian EXIF reads the same as a little-endian one`() {
        val facts = PictureHeaders.read(source(jpeg(width = 4000, height = 3000, orientation = 8, taken = "2023:01:02 03:04:05", bigEndian = true)))!!

        assertEquals(3000, facts.width)
        assertEquals(4000, facts.height)
        assertEquals(LocalDateTime.of(2023, 1, 2, 3, 4, 5), facts.takenAt)
    }

    @Test
    fun `a PNG's size is in its IHDR`() {
        val bytes = PNG + chunk("IHDR", be32(1920) + be32(1080) + byteArrayOf(8, 6, 0, 0, 0))
        val facts = PictureHeaders.read(source(bytes))!!

        assertEquals(1920, facts.width)
        assertEquals(1080, facts.height)
    }

    @Test
    fun `a GIF's size is its logical screen`() {
        val bytes = "GIF89a".toByteArray() + le16(320) + le16(240) + ByteArray(8)
        val facts = PictureHeaders.read(source(bytes))!!

        assertEquals(320, facts.width)
        assertEquals(240, facts.height)
    }

    @Test
    fun `a lossy WebP's size is in its VP8 frame`() {
        val frame = byteArrayOf(0, 0, 0, 0x9D.toByte(), 0x01, 0x2A) + le16(1024) + le16(768) + ByteArray(10)
        val facts = PictureHeaders.read(source(webp("VP8 ", frame)))!!

        assertEquals(1024, facts.width)
        assertEquals(768, facts.height)
    }

    @Test
    fun `a lossless WebP packs its size into fourteen bits each`() {
        val bits = (1000 - 1).toLong() or ((500 - 1).toLong() shl 14)
        val frame = byteArrayOf(0x2F) + le32(bits) + ByteArray(10)
        val facts = PictureHeaders.read(source(webp("VP8L", frame)))!!

        assertEquals(1000, facts.width)
        assertEquals(500, facts.height)
    }

    @Test
    fun `an extended WebP's size is its canvas`() {
        val frame = byteArrayOf(0, 0, 0, 0) + le24(3000 - 1) + le24(2000 - 1) + ByteArray(10)
        val facts = PictureHeaders.read(source(webp("VP8X", frame)))!!

        assertEquals(3000, facts.width)
        assertEquals(2000, facts.height)
    }

    @Test
    fun `a HEIC's size is its primary item's, turned by its irot, with the EXIF item read for the date`() {
        val facts = PictureHeaders.read(source(heic(width = 4032, height = 3024, quarterTurns = 3, taken = "2025:12:24 18:00:00", model = "SM-F971U")))!!

        assertEquals("a quarter turn swaps the sides", 3024, facts.width)
        assertEquals(4032, facts.height)
        assertEquals(LocalDateTime.of(2025, 12, 24, 18, 0, 0), facts.takenAt)
        assertEquals("SM-F971U", facts.camera)
    }

    @Test
    fun `a HEIC with no turn and no EXIF is the size its ispe says`() {
        val facts = PictureHeaders.read(source(heic(width = 4000, height = 3000, quarterTurns = 0, taken = null, model = null)))!!

        assertEquals(4000, facts.width)
        assertEquals(3000, facts.height)
        assertNull(facts.takenAt)
    }

    @Test
    fun `an AVIF reads the same way`() {
        val facts = PictureHeaders.read(source(heic(width = 1920, height = 1080, quarterTurns = 0, taken = null, model = null, brand = "avif")))!!

        assertEquals(1920, facts.width)
        assertEquals(1080, facts.height)
    }

    @Test
    fun `something that is not a picture, or a picture cut short, is nothing`() {
        assertNull(PictureHeaders.read(source("not a picture at all".toByteArray())))
        assertNull(PictureHeaders.read(source(ByteArray(0))))
        val jpeg = jpeg(width = 100, height = 100, exif = false)
        assertNull(PictureHeaders.read(source(jpeg.copyOf(5))))
    }

    // --- builders

    private fun source(bytes: ByteArray) = object : SeekableByteSource {
        override val size: Long = bytes.size.toLong()
        override fun readAt(offset: Long, dst: ByteArray, dstOffset: Int, length: Int): Int {
            if (offset >= bytes.size) return -1
            val n = minOf(length.toLong(), bytes.size - offset).toInt()
            System.arraycopy(bytes, offset.toInt(), dst, dstOffset, n)
            return n
        }
        override fun close() = Unit
    }

    /** A JPEG: SOI, an APP1 EXIF (unless [exif] is false), an APP0 for padding, the frame header, SOS. */
    private fun jpeg(
        width: Int,
        height: Int,
        orientation: Int = 1,
        exif: Boolean = true,
        taken: String? = null,
        offset: String? = null,
        model: String? = null,
        fNumber: Pair<Int, Int>? = null,
        sof: Int = 0xC0,
        bigEndian: Boolean = false,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0xFF.toByte(), 0xD8.toByte()))
        // APP0 JFIF first, as most cameras write it.
        segment(out, 0xE0, "JFIF\u0000".toByteArray() + ByteArray(9))
        if (exif) {
            val tiff = tiff(orientation, taken, offset, model, fNumber, bigEndian)
            segment(out, 0xE1, "Exif\u0000\u0000".toByteArray(Charsets.ISO_8859_1) + tiff)
        }
        segment(out, sof, byteArrayOf(8) + be16(height) + be16(width) + byteArrayOf(3, 1, 0x22, 0, 2, 0x11, 1, 3, 0x11, 1))
        segment(out, 0xDA, ByteArray(10))
        out.write(byteArrayOf(0xFF.toByte(), 0xD9.toByte()))
        return out.toByteArray()
    }

    private fun segment(out: ByteArrayOutputStream, marker: Int, data: ByteArray) {
        out.write(byteArrayOf(0xFF.toByte(), marker.toByte()))
        out.write(be16(data.size + 2))
        out.write(data)
    }

    /**
     * A TIFF block: IFD0 with Orientation, Model and a pointer to the EXIF
     * IFD, which holds DateTimeOriginal, OffsetTimeOriginal and FNumber.
     * Values too long for an entry go after the IFDs.
     */
    private fun tiff(orientation: Int, taken: String?, offset: String?, model: String?, fNumber: Pair<Int, Int>?, bigEndian: Boolean): ByteArray {
        val u16: (Int) -> ByteArray = if (bigEndian) ::be16 else ::le16
        val u32: (Long) -> ByteArray = if (bigEndian) { v -> be32(v.toInt()) } else ::le32
        val ifd0Tags = mutableListOf<Triple<Int, Int, Any>>() // tag, type, value (Int for SHORT/LONG, String for ASCII, Pair for RATIONAL)
        ifd0Tags += Triple(0x0112, 3, orientation)
        if (model != null) ifd0Tags += Triple(0x0110, 2, model)
        val exifTags = mutableListOf<Triple<Int, Int, Any>>()
        if (taken != null) exifTags += Triple(0x9003, 2, taken)
        if (offset != null) exifTags += Triple(0x9011, 2, offset)
        if (fNumber != null) exifTags += Triple(0x829D, 5, fNumber)
        val hasExif = exifTags.isNotEmpty()
        if (hasExif) ifd0Tags += Triple(0x8769, 4, 0) // patched below

        val ifd0At = 8
        val ifd0Size = 2 + ifd0Tags.size * 12 + 4
        val exifAt = ifd0At + ifd0Size
        val exifSize = if (hasExif) 2 + exifTags.size * 12 + 4 else 0
        var dataAt = exifAt + exifSize
        val extra = ByteArrayOutputStream()

        fun ifd(tags: List<Triple<Int, Int, Any>>): ByteArray {
            val out = ByteArrayOutputStream()
            out.write(u16(tags.size))
            for ((tag, type, value) in tags) {
                out.write(u16(tag))
                out.write(u16(type))
                when (type) {
                    3 -> {
                        out.write(u32(1))
                        out.write(u16(value as Int))
                        out.write(ByteArray(2))
                    }
                    4 -> {
                        out.write(u32(1))
                        out.write(u32(if (tag == 0x8769) exifAt.toLong() else (value as Int).toLong()))
                    }
                    2 -> {
                        val text = (value as String).toByteArray(Charsets.ISO_8859_1) + 0
                        out.write(u32(text.size.toLong()))
                        if (text.size <= 4) {
                            out.write(text + ByteArray(4 - text.size))
                        } else {
                            out.write(u32(dataAt.toLong()))
                            extra.write(text)
                            dataAt += text.size
                        }
                    }
                    5 -> {
                        @Suppress("UNCHECKED_CAST")
                        val rational = value as Pair<Int, Int>
                        val (n, d) = rational
                        out.write(u32(1))
                        out.write(u32(dataAt.toLong()))
                        extra.write(u32(n.toLong()))
                        extra.write(u32(d.toLong()))
                        dataAt += 8
                    }
                }
            }
            out.write(u32(0))
            return out.toByteArray()
        }

        val out = ByteArrayOutputStream()
        out.write((if (bigEndian) "MM" else "II").toByteArray())
        out.write(u16(42))
        out.write(u32(ifd0At.toLong()))
        out.write(ifd(ifd0Tags))
        if (hasExif) out.write(ifd(exifTags))
        out.write(extra.toByteArray())
        return out.toByteArray()
    }

    private val PNG = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    private fun chunk(type: String, data: ByteArray) = be32(data.size) + type.toByteArray() + data + ByteArray(4)

    private fun webp(kind: String, data: ByteArray): ByteArray {
        val body = "WEBP".toByteArray() + kind.toByteArray() + le32(data.size.toLong()) + data
        return "RIFF".toByteArray() + le32(body.size.toLong()) + body
    }

    /**
     * A HEIC as a phone writes one: `ftyp`, then `meta` naming item 1 (the
     * picture) as primary, with item 2 its EXIF, `iloc` pointing at the EXIF
     * inside `mdat`, and `ipco`/`ipma` giving item 1 its `ispe` and `irot`.
     */
    private fun heic(width: Int, height: Int, quarterTurns: Int, taken: String?, model: String?, brand: String = "heic"): ByteArray {
        val ftyp = box("ftyp", brand.toByteArray() + be32(0) + "mif1".toByteArray() + brand.toByteArray())
        val exifPayload = if (taken != null || model != null) {
            be32(6) + "Exif\u0000\u0000".toByteArray(Charsets.ISO_8859_1) + tiff(1, taken, null, model, null, bigEndian = true)
        } else {
            null
        }
        fun meta(exifOffset: Int): ByteArray {
            val hdlr = fullBox("hdlr", 0, 0, be32(0) + "pict".toByteArray() + ByteArray(12) + byteArrayOf(0))
            val pitm = fullBox("pitm", 0, 0, be16(1))
            val infes = mutableListOf(fullBox("infe", 2, 0, be16(1) + be16(0) + "hvc1".toByteArray() + byteArrayOf(0)))
            if (exifPayload != null) infes += fullBox("infe", 2, 0, be16(2) + be16(0) + "Exif".toByteArray() + byteArrayOf(0))
            val iinf = fullBox("iinf", 0, 0, be16(infes.size) + infes.fold(ByteArray(0)) { a, b -> a + b })
            // iloc v0: offset 4, length 4, base offset 0; the EXIF item only.
            val ilocItems = if (exifPayload != null) be16(2) + be16(0) + be16(1) + be32(exifOffset) + be32(exifPayload.size) else ByteArray(0)
            val iloc = fullBox("iloc", 0, 0, byteArrayOf(0x44, 0x00) + be16(if (exifPayload != null) 1 else 0) + ilocItems)
            val ispe = fullBox("ispe", 0, 0, be32(width) + be32(height))
            val irot = box("irot", byteArrayOf(quarterTurns.toByte()))
            val ipco = box("ipco", ispe + irot)
            val ipma = fullBox("ipma", 0, 0, be32(1) + be16(1) + byteArrayOf(2, 0x81.toByte(), 0x02))
            val iprp = box("iprp", ipco + ipma)
            return fullBox("meta", 0, 0, hdlr + pitm + iinf + iloc + iprp)
        }
        // The EXIF's offset depends on the size of what comes before it, which does not depend on the offset.
        val before = ftyp.size + meta(0).size + 8
        return ftyp + meta(before) + box("mdat", (exifPayload ?: ByteArray(0)) + ByteArray(16))
    }

    private fun box(type: String, data: ByteArray) = be32(8 + data.size) + type.toByteArray() + data

    private fun fullBox(type: String, version: Int, flags: Int, data: ByteArray) =
        box(type, byteArrayOf(version.toByte(), (flags shr 16).toByte(), (flags shr 8).toByte(), flags.toByte()) + data)

    private fun be16(v: Int) = byteArrayOf((v shr 8).toByte(), v.toByte())
    private fun be32(v: Int) = byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())
    private fun le16(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte())
    private fun le24(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte())
    private fun le32(v: Long) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())
}
