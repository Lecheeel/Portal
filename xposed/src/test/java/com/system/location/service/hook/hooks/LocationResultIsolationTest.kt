package com.system.location.service.hook.hooks

import org.junit.Assert.*
import org.junit.Test

class LocationResultIsolationTest {
    private class Result private constructor(val mLocations: ArrayList<String>) {
        companion object { fun physical() = Result(arrayListOf("physical")) }
    }
    private val field = Result::class.java.getDeclaredField("mLocations").apply { isAccessible = true }

    @Test fun forwardingUsesAnIndependentResultAndList() {
        val original = Result.physical()
        val rewritten = arrayListOf("synthetic")
        val copy = LocationResultIsolation.copyWithLocations(original, rewritten) as Result
        rewritten.clear()
        assertNotSame(original, copy)
        assertEquals(listOf("physical"), original.mLocations)
        assertEquals(listOf("synthetic"), copy.mLocations)
    }

    @Test fun parcelFailureRestoresTheSameOriginalList() {
        val original = Result.physical()
        val list = original.mLocations
        val failure = IllegalStateException("parcel full")
        assertSame(failure, assertThrows(IllegalStateException::class.java) {
            LocationResultIsolation.writeWithLocations(original, field, { listOf("synthetic") }) {
                assertEquals(listOf("synthetic"), original.mLocations)
                throw failure
            }
        })
        assertSame(list, original.mLocations)
        assertEquals(listOf("physical"), original.mLocations)
    }

    @Test fun nestedWritesRestoreTheOuterViewAndThenThePhysicalView() {
        val original = Result.physical()
        val list = original.mLocations
        LocationResultIsolation.writeWithLocations(original, field, { listOf("outer") }) {
            LocationResultIsolation.writeWithLocations(original, field, { listOf("inner") }) {
                assertEquals(listOf("inner"), original.mLocations)
            }
            assertEquals(listOf("outer"), original.mLocations)
        }
        assertSame(list, original.mLocations)
    }
}
