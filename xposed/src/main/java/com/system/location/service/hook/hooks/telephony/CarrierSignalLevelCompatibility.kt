package com.system.location.service.hook.hooks.telephony

import android.telephony.CellSignalStrengthNr
import com.system.location.service.hook.runtime.Reflection
import com.system.location.service.hook.utils.Logger

/** Some ROMs cache an additional level that their SystemUI reads instead of getLevel(). */
internal object CarrierSignalLevelCompatibility {
    private val setters by lazy {
        listOf("setMiuiLevel", "setOptimizedLevel").mapNotNull { name ->
            Reflection.findMethodExactIfExists(CellSignalStrengthNr::class.java, name, Int::class.javaPrimitiveType!!)
        }
    }

    fun initialize(signal: CellSignalStrengthNr) {
        // Use the already validated platform level. Never invent a full-strength UI value.
        setters.forEach { method ->
            runCatching { method.invoke(signal, signal.level) }.onFailure {
                Logger.warn("NR cached signal level ${method.name} unavailable: ${it.javaClass.simpleName}")
            }
        }
    }
}
