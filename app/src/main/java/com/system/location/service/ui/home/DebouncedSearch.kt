package com.system.location.service.ui.home

import com.system.location.service.ui.CallbackScheduler

internal class DebouncedSearch<T>(
    private val scheduler: CallbackScheduler,
    private val query: () -> String,
    private val viewActive: () -> Boolean,
    private val request: (String, (T) -> Unit) -> Unit,
    private val render: (T) -> Unit,
) {
    private var pending: Runnable? = null
    private var generation = 0L

    private var changingSelection = false
    private var destroyed = false

    fun destroy() {
        destroyed = true
        invalidate()
    }

    fun submit() = schedule(immediate = true)

    fun select(updateText: () -> Unit) {
        invalidate()
        changingSelection = true
        try { updateText() } finally { changingSelection = false }
    }

    fun textChanged() {
        if (!changingSelection) schedule(immediate = false)
    }

    private fun invalidate() {
        ++generation
        pending?.let(scheduler::remove)
        pending = null
    }

    private fun schedule(immediate: Boolean) {
        invalidate()
        if (destroyed) return
        val version = generation
        val text = query().trim()
        if (text.isEmpty()) return
        val callback = Runnable {
            if (!isCurrent(version, text)) return@Runnable
            pending = null
            request(text) { result ->
                if (isCurrent(version, text)) render(result)
            }
        }
        if (immediate) callback.run() else {
            pending = callback
            scheduler.post(300L, callback)
        }
    }

    private fun isCurrent(version: Long, text: String) =
        !destroyed && generation == version && viewActive() && query().trim() == text
}
