package com.system.location.service.hook.utils

import org.junit.Assert.*
import org.junit.Test

class LocationHookArgumentsTest {
    private data class Fix(val sequence: Int)

    @Test fun inactiveOrUnknownCallbacksPassThrough() {
        val callback = Any()
        assertFalse(LocationHookArguments.canIntercept(false, arrayOf(true, 1, 2, callback), 3))
        assertFalse(LocationHookArguments.canIntercept(true, emptyArray(), 3))
        assertFalse(LocationHookArguments.canIntercept(true, arrayOf(null), 0))
        assertTrue(LocationHookArguments.canIntercept(true, arrayOf(callback), 0))
    }

    @Test fun gnssBatchPreservesBooleanArgumentCardinalityAndInputArray() {
        val input = arrayOf(Fix(1), Fix(2))
        val args = arrayOf<Any?>(true, input)
        assertTrue(LocationHookArguments.rewriteBatch<Fix>(true, args, 1) { Fix(it.sequence + 10) })
        assertEquals(true, args[0])
        assertArrayEquals(arrayOf(Fix(11), Fix(12)), args[1] as Array<*>)
        assertArrayEquals(arrayOf(Fix(1), Fix(2)), input)
    }

    @Test fun disabledAndUnexpectedPayloadsAreNeverReplaced() {
        val batch = arrayOf(Fix(1))
        val args = arrayOf<Any?>(false, batch)
        assertFalse(LocationHookArguments.rewriteBatch<Fix>(false, args, 1) { error("disabled") })
        assertSame(batch, args[1])
        assertFalse(LocationHookArguments.rewriteBatch<Fix>(true, args, 0) { error("boolean is not a batch") })
        val unknown = arrayOf<Any?>(true, arrayOf("unexpected"))
        assertFalse(LocationHookArguments.rewriteBatch<Fix>(true, unknown, 1) { error("wrong payload type") })
    }
}
