package com.system.location.service.hook.runtime

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

class ReflectionTest {
    private val frameworkField =
        HookRuntime::class.java.getDeclaredField("framework").apply { isAccessible = true }
    private var previousFramework: Any? = null

    @Before
    fun usePlainReflection() {
        previousFramework = frameworkField.get(HookRuntime)
        frameworkField.set(HookRuntime, null)
    }

    @After
    fun restoreFramework() {
        frameworkField.set(HookRuntime, previousFramework)
    }

    @Test
    fun inheritedPrivateFieldsAndMethodsRemainAccessible() {
        val child = Child()
        assertEquals("parent", Reflection.getObjectField(child, "secret"))
        Reflection.setObjectField(child, "secret", "changed")
        assertEquals("changed!", Reflection.callMethod(child, "privateMessage", "!"))
        assertEquals(
            Parent::class.java,
            Reflection.findField(Child::class.java, "secret").declaringClass,
        )
        assertEquals(
            Parent::class.java,
            Reflection.findMethodExact(Child::class.java, "privateMessage", String::class.java)
                .declaringClass,
        )
        assertNull(Reflection.findFieldIfExists(Child::class.java, "missing"))
        assertNull(Reflection.findMethodExactIfExists(Child::class.java, "missing"))
    }

    @Test
    fun publicMethodsOnNonPublicClassesCanBeInvoked() {
        assertEquals("hidden:value", Reflection.callMethod(Hidden(), "message", "value"))
        val method = Reflection.findMethodExact(Hidden::class.java, "message", String::class.java)
        assertEquals("hidden:direct", method.invoke(Hidden(), "direct"))
    }

    @Test
    fun nullFieldValueIsReturnedAndCanBeWritten() {
        val fields = Fields()
        assertNull(Reflection.getObjectField(fields, "nullable"))
        Reflection.setObjectField(fields, "nullable", "value")
        assertEquals("value", Reflection.getObjectField(fields, "nullable"))
        Reflection.setObjectField(fields, "nullable", null)
        assertNull(Reflection.getObjectField(fields, "nullable"))
        assertEquals(3, Reflection.getIntField(fields, "number"))
        Reflection.setIntField(fields, "number", 8)
        assertEquals(8, Reflection.getIntField(fields, "number"))
    }

    @Test
    fun staticFieldAccessPreservesNullAndPrimitiveValues() {
        assertNull(Reflection.getStaticObjectField(Fields::class.java, "staticNullable"))
        assertEquals(12, Reflection.getStaticIntField(Fields::class.java, "staticNumber"))
    }

    @Test
    fun nullChoosesMostSpecificReferenceAndNeverMatchesPrimitive() {
        val target = Overloads()
        assertEquals("string", Reflection.callMethod(target, "choose", null))
        assertThrows(NoSuchMethodException::class.java) {
            Reflection.callMethod(target, "onlyInt", null)
        }
        assertThrows(NoSuchMethodException::class.java) {
            Reflection.newInstance(PrimitiveConstructor::class.java, null)
        }
    }

    @Test
    fun mostSpecificReferenceWinsEvenWithLongerInheritanceDistance() {
        assertEquals("contract", Reflection.callMethod(Overloads(), "contract", ContractArgument()))
        assertEquals("string", Reflection.callMethod(Overloads(), "choose", "text"))
        assertEquals(
            "sequence",
            Reflection.callMethod(Overloads(), "choose", StringBuilder("text")),
        )
    }

    @Test
    fun boxingAndPrimitiveWideningUseInvocableMethods() {
        val target = Overloads()
        assertEquals(7, Reflection.callMethod(target, "onlyInt", 7))
        assertEquals("long:7", Reflection.callMethod(target, "widen", 7))
        assertEquals("long:65", Reflection.callMethod(target, "widen", 'A'))
        assertEquals(6.0, Reflection.callMethod(target, "onlyDouble", 6.toByte()))
        assertArrayEquals(
            arrayOf(Long::class.javaPrimitiveType),
            Reflection.findMethodBestMatch(
                    Overloads::class.java,
                    "widen",
                    Int::class.javaObjectType,
                )
                .parameterTypes,
        )
        assertThrows(NoSuchMethodException::class.java) {
            Reflection.callMethod(target, "onlyInt", 7L)
        }
        assertThrows(NoSuchMethodException::class.java) {
            Reflection.callMethod(target, "onlyInt", true)
        }
    }

    @Test
    fun staticAndInstanceLookupDoNotSelectTheOtherKind() {
        val target = StaticMethods()
        assertEquals("instance:3", Reflection.callMethod(target, "kind", 3))
        assertEquals(
            "static:text",
            Reflection.callStaticMethod(StaticMethods::class.java, "kind", "text"),
        )
        assertThrows(NoSuchMethodException::class.java) {
            Reflection.callMethod(target, "kind", "text")
        }
        assertThrows(NoSuchMethodException::class.java) {
            Reflection.callStaticMethod(StaticMethods::class.java, "kind", 3)
        }
    }

    @Test
    fun ambiguousUnrelatedReferenceOverloadsAreRejected() {
        assertThrows(NoSuchMethodException::class.java) {
            Reflection.callMethod(Overloads(), "ambiguous", BothContracts())
        }
        assertThrows(NoSuchMethodException::class.java) {
            Reflection.callMethod(Overloads(), "ambiguous", null)
        }
        assertThrows(NoSuchMethodException::class.java) {
            Reflection.newInstance(AmbiguousConstructor::class.java, BothContracts())
        }
    }

    @Test
    fun privateConstructorsUseReferenceSpecificityAndWidening() {
        val specific =
            Reflection.newInstance(ReferenceConstructor::class.java, null) as ReferenceConstructor
        assertEquals("string", specific.selected)
        val widened =
            Reflection.newInstance(PrimitiveConstructor::class.java, 5) as PrimitiveConstructor
        assertEquals(5L, widened.value)
    }

    @Test
    fun targetMethodAndConstructorExceptionsKeepTheirIdentity() {
        val failure = IllegalStateException("target failure")
        assertSame(
            failure,
            assertThrows(IllegalStateException::class.java) {
                Reflection.callMethod(ThrowingMethods(), "fail", failure)
            },
        )
        assertSame(
            failure,
            assertThrows(IllegalStateException::class.java) {
                Reflection.callStaticMethod(ThrowingMethods::class.java, "failStatic", failure)
            },
        )
        assertSame(
            failure,
            assertThrows(IllegalStateException::class.java) {
                Reflection.newInstance(ThrowingConstructor::class.java, failure)
            },
        )
    }

    @Test
    fun classLookupUsesTheSuppliedLoaderAndOnlyAbsenceReturnsNull() {
        assertSame(
            Hidden::class.java,
            Reflection.findClass(Hidden::class.java.name, Hidden::class.java.classLoader),
        )
        assertNull(Reflection.findClassIfExists("portal.missing.Absent", javaClass.classLoader))
        val failure = LinkageError("broken class")
        val broken =
            object : ClassLoader(javaClass.classLoader) {
                override fun loadClass(name: String, resolve: Boolean): Class<*> {
                    if (name == "portal.broken.Type") throw failure
                    return super.loadClass(name, resolve)
                }
            }
        assertSame(
            failure,
            assertThrows(LinkageError::class.java) {
                Reflection.findClassIfExists("portal.broken.Type", broken)
            },
        )
    }

    private open class Parent {
        private var secret = "parent"

        private fun privateMessage(suffix: String) = secret + suffix
    }

    private class Child : Parent()

    private class Hidden {
        fun message(value: String) = "hidden:$value"
    }

    private class Fields {
        private var nullable: String? = null
        private var number = 3

        companion object {
            @JvmField val staticNullable: String? = null
            @JvmField val staticNumber = 12
        }
    }

    private interface ParentContract

    private interface ChildContract : ParentContract

    private class ContractArgument : ChildContract

    private interface LeftContract

    private interface RightContract

    private class BothContracts : LeftContract, RightContract

    @Suppress("UNUSED_PARAMETER")
    private class Overloads {
        fun choose(value: Any?) = "object"

        fun choose(value: CharSequence?) = "sequence"

        fun choose(value: String?) = "string"

        fun contract(value: Any) = "object"

        fun contract(value: ParentContract) = "contract"

        fun onlyInt(value: Int) = value

        fun onlyDouble(value: Double) = value

        fun widen(value: Long) = "long:$value"

        fun widen(value: Double) = "double:$value"

        fun ambiguous(value: LeftContract?) = "left"

        fun ambiguous(value: RightContract?) = "right"
    }

    private class StaticMethods {
        fun kind(value: Number) = "instance:$value"

        companion object {
            @JvmStatic fun kind(value: String) = "static:$value"
        }
    }

    private class PrimitiveConstructor private constructor(val value: Long)

    private class ReferenceConstructor {
        val selected: String

        @Suppress("UNUSED_PARAMETER")
        private constructor(value: Any?) {
            selected = "object"
        }

        @Suppress("UNUSED_PARAMETER")
        private constructor(value: String?) {
            selected = "string"
        }
    }

    @Suppress("UNUSED_PARAMETER")
    private class AmbiguousConstructor {
        constructor(value: LeftContract)

        constructor(value: RightContract)
    }

    private class ThrowingMethods {
        fun fail(error: Throwable): Nothing = throw error

        companion object {
            @JvmStatic fun failStatic(error: Throwable): Nothing = throw error
        }
    }

    private class ThrowingConstructor(error: Throwable) {
        init {
            throw error
        }
    }
}
