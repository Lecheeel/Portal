package com.system.location.service.hook.hooks

import com.system.location.service.hook.runtime.Reflection
import java.lang.reflect.Field

/** A platform LocationResult owns its list; callers can reuse the result after delivery. */
internal object LocationResultIsolation {
    fun copyWithLocations(result: Any, locations: List<*>): Any =
        Reflection.newInstance(result.javaClass, ArrayList(locations))

    fun <T> writeWithLocations(result: Any, field: Field,
        rewrite: (List<*>) -> List<*>?, write: () -> T): T = synchronized(result) {
        val original = field.get(result)
        val values = original as? List<*> ?: return@synchronized write()
        val replacement = rewrite(values) ?: return@synchronized write()
        field.set(result, ArrayList(replacement))
        try { write() } finally { field.set(result, original) }
    }
}
