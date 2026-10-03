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

    // ---- blending the zones into one strip of light per edge

    private val scope = ColorBleed.zones(24, aspect = 2.35f) // 8 · 4 · 8 · 4
    private fun colorsBy(edgeColor: (Edge) -> Int) = IntArray(scope.size) { edgeColor(scope[it].edge) }

    @Test
    fun `zones of one colour make an even band - no pillars`() {
        val strips = BleedStrips(scope)
        strips.fill(IntArray(scope.size) { green })
        listOf(strips.top, strips.right, strips.bottom, strips.left).forEach { strip ->
            strip.forEach { assertEquals(green, it) }
        }
    }

    @Test
    fun `each zone's own colour leads at its centre`() {
        // Red and blue zones in turn all the way round. Seventeen samples
        // corner to corner put every odd one on one of the eight top centres.
        val red = 0xFFE02020.toInt()
        val blue = 0xFF2040E0.toInt()
        val strips = BleedStrips(scope, samples = 17)
        val colors = IntArray(scope.size) { if (it % 2 == 0) red else blue }
        strips.fill(colors)
        scope.indices.filter { scope[it].edge == Edge.TOP }.forEachIndexed { k, zone ->
            val own = colors[zone]
            val other = if (own == red) blue else red
            val c = strips.top[2 * k + 1]
            // At least four fifths of the way from the other colour to its own.
            assertTrue("zone $k", (r(c) - r(other)).toFloat() / (r(own) - r(other)) >= 0.8f)
            assertTrue("zone $k", (b(c) - b(other)).toFloat() / (b(own) - b(other)) >= 0.8f)
        }
    }

    @Test
    fun `colours ease from one zone to the next without overshooting`() {
        val red = 0xFFE02020.toInt()
        val strips = BleedStrips(scope, samples = 256)
        strips.fill(colorsBy { if (it == Edge.TOP) red else green }.also { c ->
            // Right half of the top green, left half red: one boundary in the middle.
            scope.indices.filter { scope[it].edge == Edge.TOP }.drop(4).forEach { c[it] = green }
        })
        val middle = strips.top.slice(64 until 192)
        middle.zipWithNext().forEach { (a, b) ->
            assertTrue("red only falls", r(b) <= r(a))
            assertTrue("green only rises", g(b) >= g(a))
        }
        middle.forEach { c ->
            assertTrue(r(c) in min(r(red), r(green))..max(r(red), r(green)))
            assertTrue(g(c) in min(g(red), g(green))..max(g(red), g(green)))
        }
    }

    @Test
    fun `the glow turns each corner without a seam`() {
        val red = 0xFFE02020.toInt()
        val blue = 0xFF2040E0.toInt()
        val strips = BleedStrips(scope)
        strips.fill(colorsBy { if (it == Edge.TOP || it == Edge.BOTTOM) red else blue })
        // Each pair of strips ends on exactly the same colour at the corner they share.
        assertEquals("top-left", strips.top.first(), strips.left.first())
        assertEquals("top-right", strips.top.last(), strips.right.first())
        assertEquals("bottom-right", strips.bottom.last(), strips.right.last())
        assertEquals("bottom-left", strips.bottom.first(), strips.left.last())
        // …and that colour is an even mix of the two edges meeting there: as
        // much of the red's red as of the blue's blue.
        val purple = strips.top.first()
        assertEquals(r(purple), b(purple))
        assertTrue(r(purple) in b(red) + 1 until r(red))
    }

    @Test
    fun `a lone bright zone spills into its neighbours rather than standing as a column`() {
        // One white zone along the top, every other zone black. With 33
        // samples corner to corner, sample j sits j/4 of a zone along.
        val strips = BleedStrips(scope, samples = 33)
        val white = scope.indices.filter { scope[it].edge == Edge.TOP }[3]
        strips.fill(IntArray(scope.size) { if (it == white) 0xFFFFFFFF.toInt() else black })
        val lit = { j: Int -> r(strips.top[j]) }
        assertTrue("its own centre is mostly its own colour", lit(14) > 200)
        assertTrue("halfway to the next zone it is half lit", lit(16) in 100..155)
        assertTrue("a little reaches the next zone's centre", lit(18) in 8..40)
        assertTrue("none is left two zones away", lit(22) < 3)
        // …and it spreads the same way on both sides.
        assertEquals(lit(12), lit(16))
        assertEquals(lit(10), lit(18))
    }
}
