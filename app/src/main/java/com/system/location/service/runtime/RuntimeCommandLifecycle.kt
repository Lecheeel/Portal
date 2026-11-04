package com.system.location.service.runtime

import com.system.location.service.core.runtime.RuntimeState
import com.system.location.service.core.runtime.RuntimePhase
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Serialized by the process command mutex; Android effects are supplied by the facade. */
internal class RuntimeCommandLifecycle(
    private val state: () -> RuntimeState,
    private val clearInterrupted: () -> Unit,
    private val finishService: suspend () -> Unit,
) {
    suspend fun <T> execute(action: suspend () -> T): T = try {
        action()
    } finally {
        withContext(NonCancellable) {
            val current = state()
            if (!current.isActive) {
                try {
                    if (current.phase == RuntimePhase.STOPPED) clearInterrupted()
                } finally {
                    finishService()
                }
            }
        }
    }
}
