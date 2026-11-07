package com.system.location.service.runtime

import android.util.Log
import io.github.libxposed.service.HookedTarget
import io.github.libxposed.service.HotReloadResult
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Module-app side libxposed service: exposes running targets and requests hot reload. */
object XposedServiceBridge : XposedServiceHelper.OnServiceListener {
    private const val TAG = "XposedServiceBridge"

    @Volatile private var service: XposedService? = null
    @Volatile var lastResult: String = "service not bound"
        private set

    fun register() {
        runCatching { XposedServiceHelper.registerListener(this) }
            .onFailure { Log.e(TAG, "register listener failed", it) }
    }

    fun bound(): Boolean = service != null

    override fun onServiceBind(service: XposedService) {
        this.service = service
        lastResult = "service bound: ${runCatching { service.getFrameworkName() }.getOrDefault("unknown")}"
        Log.i(TAG, lastResult)
    }

    override fun onServiceDied(service: XposedService) {
        if (this.service === service) this.service = null
        lastResult = "service died"
        Log.i(TAG, lastResult)
    }

    fun runningTargets(): List<HookedTarget> =
        service?.getRunningTargets() ?: emptyList()

    fun hotReloadSystemServer(): String {
        val current = service ?: return "service not bound".also { lastResult = it }
        val targets = runCatching { current.getRunningTargets() }
            .getOrElse { error ->
                return "getRunningTargets failed: ${error.javaClass.simpleName}: ${error.message}"
                    .also { lastResult = it }
            }
        val target = targets.firstOrNull { it.processName == "system_server" || it.processName == "system" }
            ?: return "system_server target not running".also { lastResult = it }
        val outcome = AtomicReference<HotReloadResult?>(null)
        val latch = CountDownLatch(1)
        return runCatching {
            current.hotReloadModule(target, null, object : XposedService.HotReloadCallback {
                override fun onHotReloadResult(target: HookedTarget, result: HotReloadResult) {
                    this@XposedServiceBridge.lastResult =
                        "hot reload ${result.status}${result.message?.let { ": $it" } ?: ""}"
                    outcome.set(result)
                    latch.countDown()
                }
            })
            val completed = latch.await(40, TimeUnit.SECONDS)
            val answer = outcome.get()
            val message = when {
                !completed -> "hot reload timed out"
                answer == null -> "hot reload returned no result"
                else -> "hot reload ${answer.status}${answer.message?.let { ": $it" } ?: ""}"
            }
            lastResult = message
            message
        }.getOrElse { error ->
            val message = "hot reload failed: ${error.javaClass.simpleName}: ${error.message}"
            lastResult = message
            message
        }
    }
}
