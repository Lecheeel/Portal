package com.system.location.service.ui.mock

import com.system.location.service.ui.CallbackScheduler
import org.junit.Assert.*
import org.junit.Test

class StrokePreviewCoordinatorTest {
    private class Frames : CallbackScheduler {
        val tasks = linkedSetOf<Runnable>()
        override fun post(delayMillis: Long, callback: Runnable) { tasks += callback }
        override fun remove(callback: Runnable) { tasks -= callback }
        fun frame() { tasks.toList().also { tasks.clear() }.forEach { it.run() } }
    }

    @Test fun `duplicate points and samples beyond safety limit do not reconvert or enter model`() {
        val frames = Frames()
        var conversions = 0
        val committed = mutableListOf<List<Int>>()
        val stroke = StrokePreviewCoordinator<Int, Int>(frames, { conversions++; it }, {}, {}, { committed += it })
        repeat(10_001) { stroke.add(it); stroke.add(it) }
        stroke.finish()
        assertEquals(10_000, conversions)
        assertEquals((0 until 10_000).toList(), committed.single())
    }

    @Test fun `single point never renders or commits a route`() {
        val frames = Frames()
        val previews = mutableListOf<List<Int>>()
        val committed = mutableListOf<List<Int>>()
        val stroke = StrokePreviewCoordinator<Int, Int>(frames, { it }, { previews += it }, {}, { committed += it })
        stroke.add(1)
        frames.frame()
        stroke.finish()
        assertTrue(previews.isEmpty())
        assertTrue(committed.isEmpty())
    }

    @Test fun `cancel removes frame callback and cannot redraw cancelled stroke`() {
        val frames = Frames()
        val previews = mutableListOf<List<Int>>()
        val committed = mutableListOf<List<String>>()
        var clears = 0
        val stroke = StrokePreviewCoordinator<Int, String>(frames, { "p$it" }, { previews += it }, { clears++ }, { committed += it })
        stroke.add(1)
        stroke.add(2)
        val stale = frames.tasks.single()
        stroke.cancel()
        assertTrue(frames.tasks.isEmpty())
        stroke.add(3)
        stroke.add(4)
        stale.run()
        assertTrue(previews.isEmpty())
        frames.frame()
        assertEquals(listOf(3, 4), previews.single())
        assertTrue(committed.isEmpty())
        assertEquals(1, clears)
    }

    @Test fun `finish cancels frame and clears preview while preserving committed snapshot`() {
        val frames = Frames()
        val committed = mutableListOf<List<String>>()
        var clears = 0
        val stroke = StrokePreviewCoordinator<Int, String>(frames, { "p$it" }, {}, { clears++ }, { committed += it })
        stroke.add(1)
        stroke.add(2)
        stroke.finish()
        assertTrue(frames.tasks.isEmpty())
        assertEquals(1, clears)
        stroke.add(3)
        assertEquals(listOf("p1", "p2"), committed.single())
        stroke.cancel()
    }

    @Test fun `long stroke preview is bounded while finish commits all points including endpoint`() {
        val frames = Frames()
        val previews = mutableListOf<List<Int>>()
        val committed = mutableListOf<List<String>>()
        val stroke = StrokePreviewCoordinator<Int, String>(frames, { "p$it" }, { previews += it }, {}, { committed += it })
        repeat(10_000) { stroke.add(it) }
        frames.frame()
        assertTrue(previews.single().size <= 512)
        assertEquals(0, previews.single().first())
        assertEquals(9_999, previews.single().last())
        stroke.finish()
        assertEquals((0 until 10_000).map { "p$it" }, committed.single())
    }

    @Test fun `many samples update preview once per frame and convert each point only once`() {
        val frames = Frames()
        var conversions = 0
        val previews = mutableListOf<List<Int>>()
        val stroke = StrokePreviewCoordinator<Int, String>(frames, { conversions++; "p$it" }, { previews += it }, {}, {})
        repeat(100) { stroke.add(it) }
        assertTrue(previews.isEmpty())
        assertEquals(1, frames.tasks.size)
        frames.frame()
        assertEquals(1, previews.size)
        assertEquals((0 until 100).toList(), previews.single())
        stroke.add(100)
        frames.frame()
        assertEquals(2, previews.size)
        assertEquals(101, conversions)
    }
}
