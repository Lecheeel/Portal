package com.system.location.service.ui.mock

import com.system.location.service.ui.CallbackScheduler

internal class StrokePreviewCoordinator<P, R>(
    private val frames: CallbackScheduler,
    private val convert: (P) -> R,
    private val render: (List<P>) -> Unit,
    private val clearPreview: () -> Unit,
    private val commit: (List<R>) -> Unit,
) {
    private val points = ArrayList<P>()
    private val converted = ArrayList<R>()
    private var pending: Runnable? = null
    private var generation = 0L

    fun cancel() {
        ++generation
        pending?.let(frames::remove)
        pending = null
        points.clear()
        converted.clear()
        clearPreview()
    }

    fun finish() {
        val snapshot = converted.toList()
        cancel()
        if (snapshot.size >= 2) commit(snapshot)
    }

    fun add(point: P) {
        if (points.size >= 10_000 || (points.isNotEmpty() && points.last() == point)) return
        points += point
        converted += convert(point)
        if (pending != null) return
        val version = generation
        val callback = Runnable {
            if (version != generation) return@Runnable
            pending = null
            if (points.size >= 2) {
                // Bound SDK copies per frame; retained full geometry is never simplified.
                val count = minOf(points.size, 512)
                val preview = List(count) { index ->
                    points[(index.toLong() * (points.size - 1) / (count - 1)).toInt()]
                }
                render(preview)
            }
        }
        pending = callback
        frames.post(0L, callback)
    }
}
