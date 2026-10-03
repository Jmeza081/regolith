package com.regolith.domain.playback

import com.regolith.domain.playback.BleedZone.Edge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min

class ColorBleedTest {

    // ---- a tiny sample to read from: 20×10, painted in blocks

    private val w = 20
    private val h = 10
    private fun sample(fill: Int) = IntArray(w * h) { fill }
    private fun IntArray.paint(x0: Int, y0: Int, x1: Int, y1: Int, color: Int) = apply {
        for (y in y0 until y1) for (x in x0 until x1) this[y * w + x] = color
    }
    private fun zone(left: Float, top: Float, right: Float, bottom: Float) = BleedZone(Edge.TOP, left, top, right, bottom)
    private val whole = zone(0f, 0f, 1f, 1f)

    private fun r(c: Int) = c shr 16 and 0xFF
    private fun g(c: Int) = c shr 8 and 0xFF
    private fun b(c: Int) = c and 0xFF
    private fun saturation(c: Int): Float {
        val mx = max(r(c), max(g(c), b(c)))
        val mn = min(r(c), min(g(c), b(c)))
        return if (mx == 0) 0f else (mx - mn) / mx.toFloat()
    }

    private val green = 0xFF2E9E3A.toInt()
    private val bark = 0xFF2A2116.toInt()
    private val grey = 0xFF808080.toInt()
    private val snow = 0xFFF2F4F7.toInt()
    private val black = 0xFF000000.toInt()

    // ---- where the lights go

    @Test
    fun `a scope film gets twice as many lights along the top as down a side`() {
        val zones = ColorBleed.zones(24, aspect = 2.35f)
        assertEquals(24, zones.size)
        assertEquals(8, zones.count { it.edge == Edge.TOP })
        assertEquals(4, zones.count { it.edge == Edge.RIGHT })
        assertEquals(8, zones.count { it.edge == Edge.BOTTOM })
        assertEquals(4, zones.count { it.edge == Edge.LEFT })
    }

    @Test
    fun `a portrait clip turns that round`() {
        val zones = ColorBleed.zones(24, aspect = 9f / 16f)
        assertTrue(zones.count { it.edge == Edge.LEFT } > zones.count { it.edge == Edge.TOP })
    }

    @Test
    fun `the lights run the way a strip does - top, right, bottom, left`() {
        val zones = ColorBleed.zones(12, aspect = 2.35f)
        val runs = zones.map { it.edge }.fold(mutableListOf<Edge>()) { acc, e -> if (acc.lastOrNull() != e) acc.add(e); acc }
        assertEquals(listOf(Edge.TOP, Edge.RIGHT, Edge.BOTTOM, Edge.LEFT), runs)
        // Top goes left to right; bottom comes back right to left.
        val top = zones.filter { it.edge == Edge.TOP }
        val bottom = zones.filter { it.edge == Edge.BOTTOM }
        assertEquals(0f, top.first().left, 0f)
        assertEquals(1f, bottom.first().right, 0f)
    }

    @Test
    fun `each edge is covered end to end with no gaps`() {
        val top = ColorBleed.zones(24, aspect = 16f / 9f).filter { it.edge == Edge.TOP }
        assertEquals(0f, top.first().left, 1e-6f)
        top.zipWithNext().forEach { (a, b) -> assertEquals(a.right, b.left, 1e-6f) }
        assertEquals(1f, top.last().right, 1e-6f)
    }

    @Test
    fun `bands read a fifth into the top and bottom and an eighth into the sides`() {
        val zones = ColorBleed.zones(24, aspect = 2.35f)
        zones.filter { it.edge == Edge.TOP }.forEach { assertEquals(ColorBleed.EDGE_DEPTH, it.bottom, 0f) }
        zones.filter { it.edge == Edge.LEFT }.forEach { assertEquals(ColorBleed.SIDE_DEPTH, it.right, 0f) }
    }

    // ---- what colour each one shines

    @Test
    fun `a forest lights green, where the average would be olive`() {
        // Two thirds dark bark, one third leaves: the leaves are what you see.
        val px = sample(bark).paint(0, 0, 7, 10, green)
        val c = ColorBleed.dominant(px, w, h, whole)
        assertTrue("green channel leads", g(c) > r(c) && g(c) > b(c))
        assertEquals(g(green).toFloat(), g(c).toFloat(), 2f)
    }

    @Test
    fun `snow lights white rather than a faint tint`() {
        val px = sample(snow).paint(0, 0, 3, 10, 0xFF8FA7C4.toInt())
        val c = ColorBleed.dominant(px, w, h, whole)
        assertTrue(saturation(c) < 0.1f)
        assertTrue(r(c) > 200)
    }

    @Test
    fun `a mostly black band goes dark`() {
        val px = sample(black).paint(0, 0, 4, 10, grey)
        assertEquals(ColorBleed.BLACK, ColorBleed.dominant(px, w, h, whole))
    }

    @Test
    fun `a vivid patch beats a larger grey one`() {
        val red = 0xFFD8341E.toInt()
        val px = sample(grey).paint(0, 0, 8, 10, red)
        val c = ColorBleed.dominant(px, w, h, whole)
        assertTrue(r(c) > 180 && g(c) < 90)
    }

    @Test
    fun `a hue split across two buckets still wins`() {
        // Oranges at 38° and 42°, either side of the 40° bucket line. Each half
        // alone weighs less than the blue beside it; together they weigh more.
        val px = sample(grey)
            .paint(0, 0, 4, 10, 0xFFE09410.toInt())
            .paint(4, 0, 8, 10, 0xFFE0A210.toInt())
            .paint(8, 0, 14, 10, 0xFF2F5BD0.toInt())
        val c = ColorBleed.dominant(px, w, h, whole)
        assertTrue("orange wins", r(c) > b(c))
    }

    @Test
    fun `each zone reads only its own band`() {
        // Left half red, right half blue.
        val px = sample(0xFF2040E0.toInt()).paint(0, 0, 10, 10, 0xFFE03020.toInt())
        val left = ColorBleed.dominant(px, w, h, zone(0f, 0f, 0.5f, 1f))
        val right = ColorBleed.dominant(px, w, h, zone(0.5f, 0f, 1f, 1f))
        assertTrue(r(left) > b(left))
        assertTrue(b(right) > r(right))
    }

    @Test
    fun `colors gives one lamp colour per zone`() {
        val zones = ColorBleed.zones(12, aspect = 2f)
        val out = ColorBleed.colors(sample(green), w, h, zones)
        assertEquals(zones.size, out.size)
        out.forEach { assertEquals(0xFF, it ushr 24) }
    }

    // ---- as light

    @Test
    fun `lamp pushes saturation and lifts the middle, and keeps the hue`() {
        val dull = 0xFF7A5A3C.toInt()
        val lit = ColorBleed.lamp(dull)
        assertTrue(saturation(lit) > saturation(dull))
        assertTrue(max(r(lit), max(g(lit), b(lit))) > r(dull))
        assertTrue("still red-orange", r(lit) > g(lit) && g(lit) > b(lit))
    }

    @Test
    fun `lamp leaves black black`() {
        assertEquals(ColorBleed.BLACK, ColorBleed.lamp(ColorBleed.BLACK))
    }

    @Test
    fun `no vividness means no extra saturation`() {
        val c = 0xFF336699.toInt()
        assertEquals(saturation(c), saturation(ColorBleed.lamp(c, vividness = 0f)), 0.02f)
    }

    // ---- easing

    @Test
    fun `easing covers about two thirds of the way per response time`() {
        val current = ColorBleed.seed(intArrayOf(black))
        ColorBleed.ease(current, intArrayOf(0xFFFFFFFF.toInt()), dtMs = 350f, responseMs = 350f)
        assertEquals(0.632f, current[0], 0.01f)
    }

    @Test
    fun `easing says when it has settled`() {
        val target = intArrayOf(green)
        val current = ColorBleed.seed(intArrayOf(black))
        assertTrue(ColorBleed.ease(current, target, 16f, 350f))
        repeat(400) { ColorBleed.ease(current, target, 16f, 350f) }
        assertFalse(ColorBleed.ease(current, target, 16f, 350f))
        assertEquals(green, ColorBleed.colorAt(current, 0))
    }

    @Test
    fun `seed starts exactly on the target`() {
        val c = 0xFF123456.toInt()
        assertEquals(c, ColorBleed.colorAt(ColorBleed.seed(intArrayOf(c)), 0))
    }
}
