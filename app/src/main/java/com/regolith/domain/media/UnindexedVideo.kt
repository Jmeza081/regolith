package com.regolith.domain.media

import kotlin.math.abs
import com.regolith.domain.smb.SeekableByteSource

/**
 * Videos with no index: a Matroska file (MKV, WebM) without its Cues, or a
 * fragmented MP4 without a `sidx`. Recorders write them that way (OBS,
 * screen recorders, a stream saved as it played), because an index can only
 * be written once a recording is over. Nothing can seek in one, and nothing
 * can say how long it runs, so a frame grab meant for the middle lands on
 * the opening frame instead: black, or the intro's logo.
 *
 * This reads just enough of one to put that right, without decoding a frame:
 *  - [layout]: where its header ends (the description of its tracks, before
 *    the first cluster or fragment), from its first bytes;
 *  - [runtimeMs]: the time of its last cluster or fragment, from its last
 *    megabytes;
 *  - [cutNear]: where a cluster or fragment that starts on a key frame
 *    begins, near a point in the file, and the time of its first frame.
 *
 * The header followed by the bytes from a cut is a valid file that starts in
 * the middle ([com.regolith.domain.smb.SplicedByteSource]), which a decoder
 * opens like any other. Pure: tested on real files cut down to a few KB.
 */
object UnindexedVideo {
    enum class Kind { MATROSKA, FRAGMENTED_MP4 }

    /**
     * What a cut needs to know about the file: its [kind], where its header
     * ends, how to read its clock, and which track is the picture.
     */
    data class Layout(
        val kind: Kind,
        val headerEnd: Long,
        /** Matroska: nanoseconds per tick of a cluster's timecode. MP4: ticks per second of the video track. */
        val timescale: Long,
        /** The video track's number (Matroska) or ID (MP4), or null when the header names none. */
        val videoTrack: Long?,
    )

    /**
     * Where to cut, and what to put back: the spliced file is the header, then
     * [prefix], then the file's own bytes from [offset] on, and it starts on a
     * key frame that comes at [timeMs] in the video.
     *
     * [prefix] also starts the clock again at zero. A decoder handed a file
     * whose first frame is at minute twenty waits, in real time, for minute
     * twenty to come round; this one's first frame is at 0:00. For Matroska it
     * is a cluster of its own (its ID, "size unknown", timecode 0) and the key
     * frame's block header with the block's own time zeroed, the file then
     * resuming at the frame's data: a key frame is often part way through its
     * cluster. For an MP4 it is a copy of the fragment's header with its
     * decode times zeroed, the file resuming at the fragment's data.
     */
    class Cut(val offset: Long, val timeMs: Long, val prefix: ByteArray)

    /**
     * The file's layout when it is one of these and has no index, or null:
     * another container, a file with an index (anything can seek in that
     * already), or a header that does not parse.
     */
    fun layout(source: SeekableByteSource): Layout? = runCatching {
        val start = read(source, 0, 16) ?: return null
        when {
            start.size >= 4 && uint(start, 0, 4) == EBML_HEADER -> matroskaLayout(source)
            start.size >= 8 && type(start, 4) == "ftyp" -> mp4Layout(source)
            else -> null
        }
    }.getOrNull()

    /**
     * How long it runs, as near as its last cluster or fragment says: the time
     * that one starts, which is short of the end by at most its own length
     * (a few seconds). Null when none is found in the last [MAX_SCAN] bytes.
     */
    fun runtimeMs(source: SeekableByteSource, layout: Layout): Long? = runCatching {
        var end = source.size
        while (end > layout.headerEnd && source.size - end < MAX_SCAN) {
            val from = maxOf(layout.headerEnd, end - WINDOW)
            val window = read(source, from, (end - from).toInt()) ?: return null
            candidates(window, layout.kind).reversed().forEach { index ->
                timeAt(source, layout, from + index)?.let { return it }
            }
            if (from == layout.headerEnd) break
            end = from + SIGNATURE_OVERLAP
        }
        null
    }.getOrNull()

    /**
     * The first cut at or after [fraction] of the way through the file's
     * frames (by bytes, which is close enough to time for a recording), or
     * else the last one before it. Null when there is none within
     * [MAX_SCAN] bytes either way.
     */
    fun cutNear(source: SeekableByteSource, layout: Layout, fraction: Double): Cut? = runCatching {
        val body = source.size - layout.headerEnd
        if (body <= 0) return null
        val target = layout.headerEnd + (body * fraction.coerceIn(0.0, 1.0)).toLong()
        // Forward first: the cut then lands at or after the point asked for.
        var from = target
        while (from < source.size && from - target < MAX_SCAN) {
            val window = read(source, from, minOf(WINDOW.toLong(), source.size - from).toInt()) ?: break
            for (index in candidates(window, layout.kind)) cutAt(source, layout, from + index)?.let { return it }
            from += WINDOW - SIGNATURE_OVERLAP
        }
        var end = target
        while (end > layout.headerEnd && target - end < MAX_SCAN) {
            val start = maxOf(layout.headerEnd, end - WINDOW)
            val window = read(source, start, (end - start).toInt()) ?: break
            for (index in candidates(window, layout.kind).reversed()) cutAt(source, layout, start + index)?.let { return it }
            if (start == layout.headerEnd) break
            end = start + SIGNATURE_OVERLAP
        }
        null
    }.getOrNull()

    /**
     * A cut at the key frame nearest [timeMs] it can find, in a file whose
     * clock runs to [runtimeMs]: for a moment's own frame, where [cutNear]'s
     * share of the file is not enough. The first try is where that time
     * would be at a steady bit rate; each next one is moved by how far the
     * last landed off, kept between the tries already too early and too
     * late, since a recording's bit rate is anything but steady. It stops
     * within [CLOSE_ENOUGH_MS] (a recorder's key frames are a few seconds
     * apart), or after a few tries, each a read round one cluster or
     * fragment, with the nearest it found. Null when even that is further
     * than [toleranceMs]: past the end of the file, or a clock that does not
     * start near zero.
     */
    fun cutAtTime(source: SeekableByteSource, layout: Layout, timeMs: Long, runtimeMs: Long, toleranceMs: Long): Cut? {
        if (runtimeMs <= 0 || timeMs < 0) return null
        val aim = minOf(toleranceMs, CLOSE_ENOUGH_MS)
        var early = 0.0
        var late = 1.0
        var fraction = (timeMs.toDouble() / runtimeMs).coerceIn(0.0, 1.0)
        var nearest: Cut? = null
        for (attempt in 0 until MAX_TIME_TRIES) {
            val cut = cutNear(source, layout, fraction) ?: break
            val off = cut.timeMs - timeMs
            if (nearest == null || abs(off) < abs(nearest.timeMs - timeMs)) nearest = cut
            if (abs(off) <= aim) return cut
            if (off > 0) late = fraction else early = fraction
            val next = if (cut.timeMs > 0) fraction * timeMs / cut.timeMs else (early + late) / 2
            fraction = if (next > early && next < late) next else (early + late) / 2
        }
        return nearest?.takeIf { abs(it.timeMs - timeMs) <= toleranceMs }
    }

    // --- Matroska (EBML: every element is an ID, a size, then that many bytes)

    private fun matroskaLayout(source: SeekableByteSource): Layout? {
        val ebml = element(source, 0) ?: return null
        val segment = element(source, ebml.end) ?: return null
        if (segment.id != SEGMENT) return null
        var timescale = DEFAULT_TIMECODE_SCALE
        var videoTrack: Long? = null
        var at = segment.dataStart
        while (at < source.size && at - segment.dataStart < MAX_HEADER) {
            val child = element(source, at) ?: return null
            when (child.id) {
                CLUSTER -> return Layout(Kind.MATROSKA, at, timescale, videoTrack)
                // An index: anything can seek in this file already.
                CUES -> return null
                SEEK_HEAD -> if (seeksToCues(source, child)) return null
                INFO -> children(source, child) { id, data, offset, size ->
                    if (id == TIMECODE_SCALE) timescale = uint(data, offset, size)
                }
                TRACKS -> videoTrack = videoTrackNumber(source, child)
            }
            // The header's own elements always say how long they are; one that
            // does not is a file this cannot walk.
            if (child.size == null) return null
            at = child.end
        }
        return null
    }

    private fun seeksToCues(source: SeekableByteSource, seekHead: Element): Boolean {
        var cues = false
        children(source, seekHead) { id, data, offset, size ->
            if (id == SEEK) {
                walk(data, offset, offset + size) { childId, childOffset, childSize ->
                    if (childId == SEEK_ID && uint(data, childOffset, childSize.toInt()) == CUES) cues = true
                }
            }
        }
        return cues
    }

    private fun videoTrackNumber(source: SeekableByteSource, tracks: Element): Long? {
        var video: Long? = null
        children(source, tracks) { id, data, offset, size ->
            if (id == TRACK_ENTRY && video == null) {
                var number: Long? = null
                var type: Long? = null
                walk(data, offset, offset + size) { childId, childOffset, childSize ->
                    when (childId) {
                        TRACK_NUMBER -> number = uint(data, childOffset, childSize.toInt())
                        TRACK_TYPE -> type = uint(data, childOffset, childSize.toInt())
                    }
                }
                if (type == TRACK_TYPE_VIDEO) video = number
            }
        }
        return video
    }

    /**
     * The first key frame of the picture's track in the cluster at [offset],
     * with its time, or null. The cluster's children are walked in order, read
     * a few bytes at a time: a checksum may come first (ffmpeg writes one),
     * then its timecode, then blocks of every track, the sound's often before
     * the picture's. A muxer that also carries sound may start a cluster part
     * way between key frames, so the walk goes on through the cluster (to
     * [MAX_CLUSTER_WALK]) for the first one.
     */
    private fun matroskaCut(source: SeekableByteSource, layout: Layout, offset: Long): Cut? {
        val cluster = element(source, offset) ?: return null
        if (cluster.id != CLUSTER) return null
        val limit = minOf(cluster.end, offset + MAX_CLUSTER_WALK, source.size)
        var ticks: Long? = null
        var at = cluster.dataStart
        while (at < limit) {
            val child = element(source, at) ?: return null
            val length = child.size ?: return null
            when (child.id) {
                TIMECODE -> ticks = clusterTicks(source, child) ?: return null
                SIMPLE_BLOCK -> {
                    val block = blockHeader(source, child.dataStart) ?: return null
                    if (layout.videoTrack == null || block.track == layout.videoTrack) {
                        if (block.flags and KEY_FRAME != 0) {
                            val time = ticks ?: return null
                            return rebasedBlock(source, at, child.dataStart, block, (time + block.relative) * layout.timescale / 1_000_000)
                        }
                    }
                }
                BLOCK_GROUP -> {
                    var block: BlockHeader? = null
                    var blockStart = 0L
                    var referenced = false
                    var inner = child.dataStart
                    while (inner < child.dataStart + length) {
                        val part = element(source, inner) ?: break
                        val partLength = part.size ?: break
                        when (part.id) {
                            BLOCK -> {
                                block = blockHeader(source, part.dataStart)
                                blockStart = part.dataStart
                            }
                            REFERENCE_BLOCK -> referenced = true
                        }
                        inner = part.dataStart + partLength
                    }
                    val header = block ?: return null
                    if ((layout.videoTrack == null || header.track == layout.videoTrack) && !referenced) {
                        val time = ticks ?: return null
                        return rebasedBlock(source, at, blockStart, header, (time + header.relative) * layout.timescale / 1_000_000)
                    }
                }
                // Walked out of a cluster of unknown size and into the next.
                CLUSTER -> return null
            }
            at = child.dataStart + length
        }
        return null
    }

    /** The time of the cluster at [offset] (its timecode, in ms), for the runtime. */
    private fun clusterTime(source: SeekableByteSource, layout: Layout, offset: Long): Long? {
        val cluster = element(source, offset) ?: return null
        if (cluster.id != CLUSTER) return null
        var at = cluster.dataStart
        repeat(4) {
            val child = element(source, at) ?: return null
            val length = child.size ?: return null
            if (child.id == TIMECODE) return clusterTicks(source, child)?.let { it * layout.timescale / 1_000_000 }
            at = child.dataStart + length
        }
        return null
    }

    private fun clusterTicks(source: SeekableByteSource, timecode: Element): Long? {
        val length = timecode.size ?: return null
        if (length !in 1..8) return null
        val data = read(source, timecode.dataStart, length.toInt()) ?: return null
        return if (data.size == length.toInt()) uint(data, 0, data.size) else null
    }

    /** A block's track (and the length of that field), its time within the cluster and its flags. */
    private class BlockHeader(val track: Long?, val trackLength: Int, val relative: Long, val flags: Int) {
        /** The track, the time and the flags: what comes before the frame's own data. */
        val length: Int get() = trackLength + 3
    }

    /** A block's first few bytes. */
    private fun blockHeader(source: SeekableByteSource, offset: Long): BlockHeader? {
        val data = read(source, offset, 12) ?: return null
        val track = vint(data, 0) ?: return null
        if (track.length + 3 > data.size) return null
        return BlockHeader(track.value, track.length, relative(data, track.length), data[track.length + 2].toInt() and 0xFF)
    }

    /**
     * A cut at a key frame part way through a cluster: [CLUSTER_AT_ZERO], then
     * the bytes from [elementAt] (the block, or the group holding it) to the
     * frame's data with the block's own time set to zero, and the file from
     * the frame's data on. The element's size still holds: nothing in it moved.
     */
    private fun rebasedBlock(source: SeekableByteSource, elementAt: Long, blockData: Long, block: BlockHeader, timeMs: Long): Cut? {
        val head = read(source, elementAt, (blockData - elementAt).toInt() + block.length) ?: return null
        val time = (blockData - elementAt).toInt() + block.trackLength
        if (time + 2 > head.size) return null
        head[time] = 0
        head[time + 1] = 0
        return Cut(elementAt + head.size, timeMs, CLUSTER_AT_ZERO + head)
    }

    /** A block's time within its cluster: a signed 16-bit count of ticks. */
    private fun relative(data: ByteArray, at: Int): Long =
        if (at + 2 <= data.size) ((data[at].toInt() shl 8) or (data[at + 1].toInt() and 0xFF)).toShort().toLong() else 0L

    private class Element(val id: Long, val dataStart: Long, val size: Long?) {
        /** Where the next element starts; past the end of the file for one of unknown size. */
        val end: Long get() = if (size == null) Long.MAX_VALUE else dataStart + size
    }

    private class VInt(val value: Long?, val length: Int)

    private fun element(source: SeekableByteSource, offset: Long): Element? {
        val head = read(source, offset, 12) ?: return null
        val id = idAt(head, 0) ?: return null
        val size = vint(head, id.length) ?: return null
        return Element(id.value!!, offset + id.length + size.length, size.value)
    }

    /** Each child of [parent], whose contents are read whole (they are small: a few KB). */
    private fun children(source: SeekableByteSource, parent: Element, each: (id: Long, data: ByteArray, offset: Int, size: Int) -> Unit) {
        val size = parent.size ?: return
        if (size > MAX_HEADER) return
        val data = read(source, parent.dataStart, size.toInt()) ?: return
        walk(data, 0, data.size) { id, offset, length -> each(id, data, offset, length.toInt()) }
    }

    private fun walk(data: ByteArray, from: Int, to: Int, each: (id: Long, offset: Int, size: Long) -> Unit) {
        var at = from
        while (at < to) {
            val id = idAt(data, at) ?: return
            val size = vint(data, at + id.length) ?: return
            val start = at + id.length + size.length
            val length = size.value ?: return
            if (start + length > to) return
            each(id.value!!, start, length)
            at = (start + length).toInt()
        }
    }

    /** An element ID: its length marker stays part of its value, the way the spec writes IDs. */
    private fun idAt(data: ByteArray, at: Int): VInt? {
        val first = data.getOrNull(at)?.toInt()?.and(0xFF) ?: return null
        val length = Integer.numberOfLeadingZeros(first) - 23
        if (first == 0 || length > 4 || at + length > data.size) return null
        return VInt(uint(data, at, length), length)
    }

    /** A size: the marker bit dropped, and all ones meaning "unknown" (null). */
    private fun vint(data: ByteArray, at: Int): VInt? {
        val first = data.getOrNull(at)?.toInt()?.and(0xFF) ?: return null
        val length = Integer.numberOfLeadingZeros(first) - 23
        if (first == 0 || length > 8 || at + length > data.size) return null
        var value = (first and (0xFF ushr length)).toLong()
        for (i in 1 until length) value = (value shl 8) or (data[at + i].toLong() and 0xFF)
        val unknown = value == (1L shl (7 * length)) - 1
        return VInt(if (unknown) null else value, length)
    }

    // --- Fragmented MP4 (boxes: a 32-bit size, a four-letter type, then the rest)

    private fun mp4Layout(source: SeekableByteSource): Layout? {
        var at = 0L
        var fragmented = false
        var timescale: Long? = null
        var videoTrack: Long? = null
        while (at < source.size && at < MAX_HEADER) {
            val head = read(source, at, 16) ?: return null
            val size = boxSize(head, 0, source.size - at) ?: return null
            when (type(head, 4)) {
                "moof" -> return if (fragmented && timescale != null) Layout(Kind.FRAGMENTED_MP4, at, timescale, videoTrack) else null
                // An index: anything can seek in this file already.
                "sidx" -> return null
                // A plain MP4 keeps its media before or after one big moov with every sample in it.
                "mdat" -> return null
                "moov" -> {
                    if (size > MAX_HEADER) return null
                    val moov = read(source, at, size.toInt()) ?: return null
                    boxes(moov, 8, moov.size) { type, offset, end ->
                        when (type) {
                            "mvex" -> fragmented = true
                            "trak" -> videoTrackOf(moov, offset, end)?.let { (id, scale) ->
                                if (videoTrack == null) {
                                    videoTrack = id
                                    timescale = scale
                                }
                            }
                        }
                    }
                }
            }
            at += size
        }
        return null
    }

    /** A video track's ID and timescale (ticks per second), or null for any other track. */
    private fun videoTrackOf(data: ByteArray, from: Int, to: Int): Pair<Long, Long>? {
        var id: Long? = null
        var scale: Long? = null
        var video = false
        boxes(data, from, to) { type, offset, end ->
            when (type) {
                "tkhd" -> id = uint(data, offset + if (data[offset].toInt() == 1) 20 else 12, 4)
                "mdia" -> boxes(data, offset, end) { inner, innerOffset, _ ->
                    when (inner) {
                        "hdlr" -> video = type(data, innerOffset + 8) == "vide"
                        "mdhd" -> scale = uint(data, innerOffset + if (data[innerOffset].toInt() == 1) 20 else 12, 4)
                    }
                }
            }
        }
        return if (video && id != null && scale != null && scale!! > 0) id!! to scale!! else null
    }

    /**
     * A fragment at [offset] with a picture track in it, with its first
     * frame's time, or null; null too when [needKey] and that frame is not a
     * key frame.
     */
    private fun mp4Cut(source: SeekableByteSource, layout: Layout, offset: Long, needKey: Boolean = true): Cut? {
        val head = read(source, offset, 16) ?: return null
        val size = boxSize(head, 0, source.size - offset) ?: return null
        if (type(head, 4) != "moof" || size < 24 || size > MAX_FRAGMENT_HEADER) return null
        if (uint(head, 8, 4) != 16L || type(head, 12) != "mfhd") return null
        val moof = read(source, offset, size.toInt()) ?: return null
        var time: Long? = null
        var key = true
        boxes(moof, 8, moof.size) { type, trafOffset, trafEnd ->
            if (type != "traf" || time != null) return@boxes
            var track: Long? = null
            var decodeTime: Long? = null
            var defaultFlags: Long? = null
            var firstFlags: Long? = null
            boxes(moof, trafOffset, trafEnd) { inner, offset2, _ ->
                when (inner) {
                    "tfhd" -> {
                        track = uint(moof, offset2 + 4, 4)
                        val flags = uint(moof, offset2 + 1, 3)
                        // Optional fields in order, each present when its flag is set.
                        var at = offset2 + 8
                        if (flags and 0x01L != 0L) at += 8 // base data offset
                        if (flags and 0x02L != 0L) at += 4 // sample description index
                        if (flags and 0x08L != 0L) at += 4 // default sample duration
                        if (flags and 0x10L != 0L) at += 4 // default sample size
                        if (flags and 0x20L != 0L) defaultFlags = uint(moof, at, 4)
                    }
                    "tfdt" -> decodeTime = uint(moof, offset2 + 4, if (moof[offset2].toInt() == 1) 8 else 4)
                    "trun" -> {
                        val flags = uint(moof, offset2 + 1, 3)
                        var at = offset2 + 8 // version and flags, sample count
                        if (flags and 0x01L != 0L) at += 4 // data offset
                        if (flags and 0x04L != 0L) firstFlags = uint(moof, at, 4)
                    }
                }
            }
            if (layout.videoTrack == null || track == layout.videoTrack) {
                val flags = firstFlags ?: defaultFlags
                key = flags == null || flags and NON_SYNC_SAMPLE == 0L
                time = decodeTime
            }
        }
        val ticks = time ?: return null
        if (!needKey) return Cut(offset, ticks * 1000 / layout.timescale, ByteArray(0))
        if (!key) return null
        return Cut(offset + size, ticks * 1000 / layout.timescale, rebasedFragmentHeader(moof, offset, layout.headerEnd))
    }

    /**
     * A copy of the fragment header [moof] (read from [offset]) for the spliced
     * file, where it starts at [newOffset]: every track's decode time set to
     * zero, and any absolute data offset moved to match. Offsets counted from
     * the fragment itself, the usual way, need nothing.
     */
    private fun rebasedFragmentHeader(moof: ByteArray, offset: Long, newOffset: Long): ByteArray {
        val copy = moof.copyOf()
        boxes(copy, 8, copy.size) { type, trafOffset, trafEnd ->
            if (type != "traf") return@boxes
            boxes(copy, trafOffset, trafEnd) { inner, at, _ ->
                when (inner) {
                    "tfdt" -> put(copy, at + 4, if (copy[at].toInt() == 1) 8 else 4, 0L)
                    "tfhd" -> if (uint(copy, at + 1, 3) and 0x01L != 0L) {
                        put(copy, at + 8, 8, newOffset + (uint(copy, at + 8, 8) - offset))
                    }
                }
            }
        }
        return copy
    }

    private fun put(data: ByteArray, at: Int, length: Int, value: Long) {
        for (i in 0 until length) data[at + i] = (value ushr (8 * (length - 1 - i))).toByte()
    }

    private fun boxes(data: ByteArray, from: Int, to: Int, each: (type: String, contentOffset: Int, end: Int) -> Unit) {
        var at = from
        while (at + 8 <= to) {
            val size = boxSize(data, at, (to - at).toLong()) ?: return
            if (size < 8 || at + size > to) return
            val header = if (uint(data, at, 4) == 1L) 16 else 8
            each(type(data, at + 4), at + header, (at + size).toInt())
            at += size.toInt()
        }
    }

    /** A box's whole size, header included: 1 means a 64-bit size follows, 0 "to the end of [remaining]". */
    private fun boxSize(data: ByteArray, at: Int, remaining: Long): Long? {
        if (at + 8 > data.size) return null
        return when (val size = uint(data, at, 4)) {
            0L -> remaining
            1L -> if (at + 16 <= data.size) uint(data, at + 8, 8) else null
            else -> size
        }?.takeIf { it >= 8 }
    }

    // --- shared

    private fun cutAt(source: SeekableByteSource, layout: Layout, offset: Long): Cut? = when (layout.kind) {
        Kind.MATROSKA -> matroskaCut(source, layout, offset)
        Kind.FRAGMENTED_MP4 -> mp4Cut(source, layout, offset)
    }

    private fun timeAt(source: SeekableByteSource, layout: Layout, offset: Long): Long? = when (layout.kind) {
        Kind.MATROSKA -> clusterTime(source, layout, offset)
        Kind.FRAGMENTED_MP4 -> mp4Cut(source, layout, offset, needKey = false)?.timeMs
    }

    /** Where a cluster's ID, or a `moof` box's start, might be in [window]: to be checked by [cutAt]. */
    private fun candidates(window: ByteArray, kind: Kind): List<Int> {
        val out = mutableListOf<Int>()
        when (kind) {
            Kind.MATROSKA -> for (i in 0..window.size - 4) {
                if (window[i] == 0x1F.toByte() && window[i + 1] == 0x43.toByte() && window[i + 2] == 0xB6.toByte() && window[i + 3] == 0x75.toByte()) out += i
            }
            Kind.FRAGMENTED_MP4 -> for (i in 4..window.size - 4) {
                if (window[i] == 'm'.code.toByte() && window[i + 1] == 'o'.code.toByte() && window[i + 2] == 'o'.code.toByte() && window[i + 3] == 'f'.code.toByte()) out += i - 4
            }
        }
        return out
    }

    /** Up to [length] bytes at [offset], fewer at the end of the file; null when none could be read. */
    private fun read(source: SeekableByteSource, offset: Long, length: Int): ByteArray? {
        if (offset < 0 || offset >= source.size || length <= 0) return null
        val want = minOf(length.toLong(), source.size - offset).toInt()
        val out = ByteArray(want)
        var done = 0
        while (done < want) {
            val n = source.readAt(offset + done, out, done, want - done)
            if (n <= 0) break
            done += n
        }
        return if (done == want) out else out.copyOf(done).takeIf { done > 0 }
    }

    private fun uint(data: ByteArray, at: Int, length: Int): Long {
        var value = 0L
        for (i in 0 until length) value = (value shl 8) or (data[at + i].toLong() and 0xFF)
        return value
    }

    private fun type(data: ByteArray, at: Int): String =
        if (at + 4 <= data.size) String(data, at, 4, Charsets.ISO_8859_1) else ""

    /** How much is read at a time when looking for clusters or fragments. */
    private const val WINDOW = 1 shl 20

    /** How far to look either way before giving up: a key frame every few seconds is the norm. */
    private const val MAX_SCAN = 16L shl 20

    /** Windows overlap by this much, so a signature on the seam is not missed. */
    private const val SIGNATURE_OVERLAP = 16

    /** How many cuts [cutAtTime] tries on its way to a time... */
    private const val MAX_TIME_TRIES = 8

    /** ...and how near it must land to stop trying. */
    private const val CLOSE_ENOUGH_MS = 1_000L

    /** How far into a cluster to walk for its first key frame before trying the next cluster. */
    private const val MAX_CLUSTER_WALK = 8L shl 20

    /** The most of a file's start taken to be its header (attachments such as fonts can be large). */
    private const val MAX_HEADER = 16L shl 20

    /** A fragment's own header (its `moof`) is small: a table of its samples. */
    private const val MAX_FRAGMENT_HEADER = 4L shl 20

    private const val EBML_HEADER = 0x1A45DFA3L
    private const val SEGMENT = 0x18538067L
    private const val SEEK_HEAD = 0x114D9B74L
    private const val SEEK = 0x4DBBL
    private const val SEEK_ID = 0x53ABL
    private const val INFO = 0x1549A966L
    private const val TIMECODE_SCALE = 0x2AD7B1L
    private const val TRACKS = 0x1654AE6BL
    private const val TRACK_ENTRY = 0xAEL
    private const val TRACK_NUMBER = 0xD7L
    private const val TRACK_TYPE = 0x83L
    private const val TRACK_TYPE_VIDEO = 1L
    private const val CUES = 0x1C53BB6BL
    private const val CLUSTER = 0x1F43B675L
    private const val TIMECODE = 0xE7L
    private const val SIMPLE_BLOCK = 0xA3L
    private const val BLOCK_GROUP = 0xA0L
    private const val BLOCK = 0xA1L
    private const val REFERENCE_BLOCK = 0xFBL
    private const val KEY_FRAME = 0x80

    /**
     * The start of a cluster at timecode 0, for a cut that begins at a key
     * frame part way through one: the cluster ID, a size of "unknown" (it
     * runs until the next cluster, which is how a live recording writes them
     * anyway), and a one-byte timecode of 0.
     */
    private val CLUSTER_AT_ZERO = byteArrayOf(
        0x1F, 0x43, 0xB6.toByte(), 0x75,
        0x01, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(),
        0xE7.toByte(), 0x81.toByte(), 0x00,
    )
    private const val DEFAULT_TIMECODE_SCALE = 1_000_000L

    /** `sample_is_non_sync_sample` in an MP4 sample's flags. */
    private const val NON_SYNC_SAMPLE = 0x00010000L
}
