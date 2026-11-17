package com.jono.flick

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecognizerTest {
    private val g = Recognizer.Geometry(minStroke = 40f, minAfterTurn = 24f, still = 8f, dwellMs = 300)

    // Root: N = app "a", E = folder "More" { N = folder "Deeper" { W = app "deep" }, S = app "b" }
    private fun menu(): Slot.Folder {
        val deeper = Slot.Folder("Deeper").apply { slots[6] = Slot.App("p.deep", "deep") }
        val more = Slot.Folder("More").apply {
            slots[0] = deeper
            slots[4] = Slot.App("p.b", "b")
            slots[6] = Slot.App("p.c", "c")
        }
        return Slot.Folder("Root").apply {
            slots[0] = Slot.App("p.a", "a")
            slots[2] = more
        }
    }

    /** Feeds a polyline to the recognizer in small steps, 8 ms apart. */
    private fun stroke(r: Recognizer, vararg pts: Pair<Float, Float>): Recognizer.Result {
        var t = 0L
        r.down(pts[0].first, pts[0].second, t)
        for (k in 1 until pts.size) {
            val (x0, y0) = pts[k - 1]
            val (x1, y1) = pts[k]
            val steps = 10
            for (s in 1..steps) {
                t += 8
                r.move(x0 + (x1 - x0) * s / steps, y0 + (y1 - y0) * s / steps, t)
                r.tick(t)
            }
        }
        val last = pts.last()
        return r.up(last.first, last.second, t + 8)
    }

    @Test fun sectors() {
        assertEquals(0, Recognizer.sector(0f))
        assertEquals(0, Recognizer.sector(350f))
        assertEquals(2, Recognizer.sector(90f))
        assertEquals(4, Recognizer.sector(180f))
        assertEquals(6, Recognizer.sector(270f))
        assertEquals(7, Recognizer.sector(320f))
        assertEquals(90f, Recognizer.angle(Recognizer.Pt(0f, 0f), Recognizer.Pt(10f, 0f)), 0.01f)
        assertEquals(0f, Recognizer.angle(Recognizer.Pt(0f, 0f), Recognizer.Pt(0f, -10f)), 0.01f)
    }

    @Test fun straightFlickLaunches() {
        val r = Recognizer(menu(), g)
        val res = stroke(r, 0f to 0f, 0f to -100f)
        assertTrue(res is Recognizer.Result.Launch)
        assertEquals("p.a", (res as Recognizer.Result.Launch).app.pkg)
        assertEquals(false, r.shown)
    }

    @Test fun zigzagGoesThreeLevels() {
        val r = Recognizer(menu(), g)
        // E into More, N into Deeper, W to "deep".
        val res = stroke(r, 0f to 0f, 100f to 0f, 100f to -100f, 0f to -100f)
        assertTrue("got $res", res is Recognizer.Result.Launch)
        res as Recognizer.Result.Launch
        assertEquals("p.deep", res.app.pkg)
        assertEquals(listOf(2, 0, 6), res.path)
        assertEquals(listOf("More", "Deeper", "deep"), res.names)
    }

    @Test fun reversalZigzag() {
        val r = Recognizer(menu(), g)
        // E into More, then a right-angle turn S picks "b".
        val res = stroke(r, 0f to 0f, 90f to 0f, 90f to 90f)
        assertEquals("p.b", (res as Recognizer.Result.Launch).app.pkg)
    }

    @Test fun fullReversal() {
        val r = Recognizer(menu(), g)
        // E into More, then straight back W picks "c".
        val res = stroke(r, 0f to 0f, 100f to 0f, 0f to 0f)
        assertEquals("p.c", (res as Recognizer.Result.Launch).app.pkg)
    }

    @Test fun smallHookAtTheEndIsIgnored() {
        val r = Recognizer(menu(), g)
        val res = stroke(r, 0f to 0f, 0f to -100f, 10f to -95f)
        assertEquals("p.a", (res as Recognizer.Result.Launch).app.pkg)
    }

    @Test fun tapIsATap() {
        val r = Recognizer(menu(), g)
        r.down(5f, 5f, 0)
        assertTrue(r.up(7f, 6f, 50) is Recognizer.Result.Tap)
    }

    @Test fun holdShowsRingThenPick() {
        val r = Recognizer(menu(), g)
        r.down(0f, 0f, 0)
        r.tick(100)
        assertEquals(false, r.shown)
        assertTrue(r.tick(320))
        assertEquals(true, r.shown)
        // Wander a bit, then lift on N.
        r.move(30f, 30f, 400)
        r.move(-5f, -80f, 450)
        assertEquals(0, r.hover)
        val res = r.up(-5f, -80f, 460)
        assertEquals("p.a", (res as Recognizer.Result.Launch).app.pkg)
    }

    @Test fun holdOnFolderOpensIt() {
        val r = Recognizer(menu(), g)
        r.down(0f, 0f, 0)
        r.move(100f, 0f, 50)
        assertEquals(2, r.hover)
        assertTrue(r.tick(400))
        assertEquals("More", r.folder.name)
        assertEquals(true, r.shown)
        r.move(100f, 80f, 500)
        val res = r.up(100f, 80f, 510)
        assertEquals("p.b", (res as Recognizer.Result.Launch).app.pkg)
    }

    @Test fun liftOnFolderOpensItsRing() {
        val r = Recognizer(menu(), g)
        val res = stroke(r, 0f to 0f, 100f to 0f)
        assertTrue(res is Recognizer.Result.Open)
        assertEquals("More", (res as Recognizer.Result.Open).folder.name)
        assertEquals(listOf(2), res.path)
    }

    @Test fun emptyDirectionCancels() {
        val r = Recognizer(menu(), g)
        assertTrue(stroke(r, 0f to 0f, 0f to 100f) is Recognizer.Result.Cancel)
    }

    @Test fun tapOnShownRingItem() {
        val r = Recognizer(menu(), g, startShown = true)
        r.down(0f, -90f, 0, origin = Recognizer.Pt(0f, 0f))
        val res = r.up(0f, -90f, 60)
        assertEquals("p.a", (res as Recognizer.Result.Launch).app.pkg)
    }
}
