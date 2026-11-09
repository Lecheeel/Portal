package com.system.location.service.maps

class RealPositionLifecycle(private val positioning: (Boolean) -> Unit) {
    private var resumed = false
    private var simulating = false
    private var destroyed = false
    private var running = false
    fun resume(simulationActive: Boolean) { if (!destroyed) { resumed = true; simulating = simulationActive; update() } }
    fun simulation(active: Boolean) { simulating = active; update() }
    fun pause() { resumed = false; update() }
    fun destroy() { destroyed = true; resumed = false; update() }
    fun acceptsFix() = running && !destroyed
    private fun update() {
        val next = resumed && !simulating && !destroyed
        if (running != next) { running = next; positioning(next) }
    }
}
