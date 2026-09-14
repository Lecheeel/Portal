package com.system.location.service.backend.mock

import android.location.Location

/** Only changes the outgoing object. Android may mark it mock again after submission. */
class MockFlagExperiment {
    data class Outcome(val invoked: Boolean, val detail: String)
    private data class Attempt(val name: String, val clear: ((Location) -> Unit)?, val failure: String?)

    // Resolve once, not on every 50 ms publication. No hidden-API exemption is requested.
    private val attempts by lazy {
        listOf("setMock", "setIsFromMockProvider").map { name ->
            try {
                val method = Location::class.java.getDeclaredMethod(name, Boolean::class.javaPrimitiveType)
                method.isAccessible = true
                Attempt(name, { location -> method.invoke(location, false) }, null)
            } catch (error: Exception) {
                Attempt(name, null, error.javaClass.simpleName)
            }
        } + try {
            val field = Location::class.java.getDeclaredField("mIsFromMockProvider")
            field.isAccessible = true
            Attempt("mIsFromMockProvider", { location -> field.setBoolean(location, false) }, null)
        } catch (error: Exception) {
            Attempt("mIsFromMockProvider", null, error.javaClass.simpleName)
        }
    }

    fun clear(location: Location): Outcome {
        val before = location.isMock
        val failures = mutableListOf<String>()
        for (attempt in attempts) {
            val action = attempt.clear
            if (action == null) {
                failures += "${attempt.name}: ${attempt.failure}"
                continue
            }
            try {
                action(location)
                if (!location.isMock) return Outcome(true,
                    "${attempt.name} 调用完成；提交前 isMock=$before → false；仅本地对象，不代表系统或高德收到的标记")
                failures += "${attempt.name}: 标记仍为 true"
            } catch (error: Exception) {
                failures += "${attempt.name}: ${error.javaClass.simpleName}"
            }
        }
        return Outcome(false, "反射清除不可用；提交前 isMock=$before → ${location.isMock}；" + failures.joinToString("; "))
    }
}
