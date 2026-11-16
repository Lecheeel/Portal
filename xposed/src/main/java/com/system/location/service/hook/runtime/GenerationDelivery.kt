package com.system.location.service.hook.runtime

/** A queued producer must retain its submission generation, never adopt the successor's gate. */
internal class GenerationDelivery(private val gate: ReloadExecutionGate) {
    fun run(action: () -> Unit) {
        try { gate.enter { current -> if (current) action() } }
        catch (_: InterruptedException) { Thread.currentThread().interrupt() }
    }
}
