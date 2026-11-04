package com.system.location.service.ui.home

import com.system.location.service.ui.CallbackScheduler
import org.junit.Assert.*
import org.junit.Test

class DebouncedSearchTest {
    private class Clock : CallbackScheduler {
        var now = 0L
        val tasks = linkedMapOf<Runnable, Long>()
        override fun post(delayMillis: Long, callback: Runnable) { tasks[callback] = now + delayMillis }
        override fun remove(callback: Runnable) { tasks.remove(callback) }
        fun advance(millis: Long) {
            now += millis
            tasks.filterValues { it <= now }.keys.toList().forEach { tasks.remove(it); it.run() }
        }
    }

    @Test fun `clear removes queued task and invalidates already dispatched reply`() {
        val clock = Clock()
        var query = "beijing"
        val callbacks = mutableListOf<(String) -> Unit>()
        val rendered = mutableListOf<String>()
        val search = DebouncedSearch<String>(clock, { query }, { true }, { _, reply -> callbacks += reply }, { rendered += it })
        search.textChanged()
        clock.advance(300)
        search.textChanged()
        query = ""
        search.textChanged()
        callbacks.single()("stale")
        clock.advance(300)
        assertTrue(rendered.isEmpty())
        assertEquals(1, callbacks.size)
        assertTrue(clock.tasks.isEmpty())
    }

    @Test fun `completion checks live query and view before rendering`() {
        val clock = Clock()
        var query = "beijing"
        var active = true
        lateinit var reply: (String) -> Unit
        val rendered = mutableListOf<String>()
        val search = DebouncedSearch<String>(clock, { query }, { active }, { _, callback -> reply = callback }, { rendered += it })
        search.submit()
        query = "changed without watcher"
        reply("wrong query")
        query = "beijing"
        active = false
        reply("inactive view")
        assertTrue(rendered.isEmpty())
    }

    @Test fun `selection with unchanged name still invalidates old result`() {
        val clock = Clock()
        lateinit var reply: (String) -> Unit
        val rendered = mutableListOf<String>()
        val search = DebouncedSearch<String>(clock, { "beijing" }, { true }, { _, callback -> reply = callback }, { rendered += it })
        search.submit()
        search.select {}
        reply("stale")
        assertTrue(rendered.isEmpty())
    }

    @Test fun `destroy cancels pending work and rejects callbacks even if view becomes active again`() {
        val clock = Clock()
        val callbacks = mutableListOf<(String) -> Unit>()
        val rendered = mutableListOf<String>()
        var active = true
        val search = DebouncedSearch<String>(clock, { "beijing" }, { active }, { _, reply -> callbacks += reply }, { rendered += it })
        search.textChanged()
        clock.advance(300)
        search.textChanged()
        search.destroy()
        active = false
        active = true
        callbacks.single()("old view")
        clock.advance(300)
        search.textChanged()
        search.submit()
        clock.advance(300)
        assertTrue(rendered.isEmpty())
        assertEquals(1, callbacks.size)
        assertTrue(clock.tasks.isEmpty())
    }

    @Test fun `explicit search runs immediately once and cancels queued typing search`() {
        val clock = Clock()
        val requested = mutableListOf<String>()
        val search = DebouncedSearch<String>(clock, { "beijing" }, { true }, { text, _ -> requested += text }, {})
        search.textChanged()
        search.submit()
        assertEquals(listOf("beijing"), requested)
        clock.advance(300)
        assertEquals(listOf("beijing"), requested)
    }

    @Test fun `selection invalidates replies and suppresses programmatic text query`() {
        val clock = Clock()
        var query = "beijing"
        val callbacks = mutableListOf<(String) -> Unit>()
        val rendered = mutableListOf<String>()
        val search = DebouncedSearch<String>(clock, { query }, { true }, { _, reply -> callbacks += reply }, { rendered += it })
        search.textChanged()
        clock.advance(300)
        search.select {
            query = "Beijing station"
            search.textChanged()
        }
        callbacks.single()("stale")
        clock.advance(300)
        assertTrue(rendered.isEmpty())
        assertEquals(1, callbacks.size)
    }

    @Test fun `old callback cannot render after same query is requested again`() {
        val clock = Clock()
        var query = "beijing"
        val callbacks = mutableListOf<(String) -> Unit>()
        val rendered = mutableListOf<String>()
        val search = DebouncedSearch<String>(clock, { query }, { true }, { _, reply -> callbacks += reply }, { rendered += it })
        search.textChanged()
        clock.advance(300)
        query = "shanghai"
        search.textChanged()
        query = "beijing"
        search.textChanged()
        clock.advance(300)
        callbacks[1]("new")
        callbacks[0]("old")
        assertEquals(listOf("new"), rendered)
    }

    @Test fun `typing waits for quiet period and requests only latest query`() {
        val clock = Clock()
        var query = "b"
        val requested = mutableListOf<String>()
        val search = DebouncedSearch<String>(clock, { query }, { true }, { text, _ -> requested += text }, {})
        search.textChanged()
        clock.advance(200)
        query = "beijing"
        search.textChanged()
        clock.advance(299)
        assertTrue(requested.isEmpty())
        clock.advance(1)
        assertEquals(listOf("beijing"), requested)
    }
}
