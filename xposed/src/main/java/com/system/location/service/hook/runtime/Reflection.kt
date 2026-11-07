package com.system.location.service.hook.runtime

import java.lang.reflect.Field
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/** Reflection only: hook registration and outcomes belong to libxposed's native chain. */
object Reflection {
    fun findClass(name: String, loader: ClassLoader?): Class<*> =
        Class.forName(name, false, loader ?: ClassLoader.getSystemClassLoader())

    fun findClassIfExists(name: String, loader: ClassLoader?): Class<*>? =
        try { findClass(name, loader) } catch (_: ClassNotFoundException) { null }

    fun findMethodExact(type: Class<*>, name: String, vararg parameters: Class<*>): Method =
        findMethodExactIfExists(type, name, *parameters)
            ?: throw NoSuchMethodException("${type.name}#$name(${parameters.joinToString { it.name }})")

    fun findMethodExactIfExists(type: Class<*>, name: String, vararg parameters: Class<*>): Method? =
        hierarchy(type).firstNotNullOfOrNull { parent ->
            try { parent.getDeclaredMethod(name, *parameters).apply { isAccessible = true } }
            catch (_: NoSuchMethodException) { null }
        }

    fun findMethodBestMatch(type: Class<*>, name: String, vararg args: Any?): Method =
        bestMethod(type, name, args.map { if (it is Class<*>) it else it?.javaClass })

    private fun bestMethod(type: Class<*>, name: String, args: List<Class<*>?>, static: Boolean? = null): Method {
        val methods = (hierarchy(type).flatMap { it.declaredMethods.asSequence() } + type.methods.asSequence())
            .filter { it.name == name && (static == null || Modifier.isStatic(it.modifiers) == static) }
            // Prefer a subclass declaration over the same signature inherited from its parent.
            .distinctBy { it.parameterTypes.toList() }
            .toList()
        return select(methods, args) { it.parameterTypes }.apply { isAccessible = true }
    }

    fun findField(type: Class<*>, name: String): Field = findFieldIfExists(type, name)
        ?: throw NoSuchFieldException("${type.name}#$name")

    fun findFieldIfExists(type: Class<*>, name: String): Field? =
        hierarchy(type).firstNotNullOfOrNull { parent ->
            try { parent.getDeclaredField(name).apply { isAccessible = true } }
            catch (_: NoSuchFieldException) { null }
        }

    fun getObjectField(instance: Any?, name: String): Any? =
        findField(requireNotNull(instance).javaClass, name).get(instance)

    fun getStaticObjectField(type: Class<*>, name: String): Any? = findField(type, name).get(null)

    fun setObjectField(instance: Any?, name: String, value: Any?) {
        findField(requireNotNull(instance).javaClass, name).set(instance, value)
    }

    fun getStaticIntField(type: Class<*>, name: String): Int = findField(type, name).getInt(null)
    fun getIntField(instance: Any?, name: String): Int = findField(requireNotNull(instance).javaClass, name).getInt(instance)
    fun setIntField(instance: Any?, name: String, value: Int) {
        findField(requireNotNull(instance).javaClass, name).setInt(instance, value)
    }

    fun callMethod(instance: Any?, name: String, vararg args: Any?): Any? {
        val target = requireNotNull(instance)
        return invoke(bestMethod(target.javaClass, name, args.map { it?.javaClass }, false), target, args)
    }

    fun callStaticMethod(type: Class<*>, name: String, vararg args: Any?): Any? =
        invoke(bestMethod(type, name, args.map { it?.javaClass }, true), null, args)

    private fun invoke(method: Method, receiver: Any?, args: Array<out Any?>): Any? = unwrap {
        // FULL is the default invoker type: ordinary reflected calls must retain other hooks.
        val framework = HookRuntime.attachedInterface()
        if (framework == null) method.invoke(receiver, *args)
        else framework.getInvoker(method).invoke(receiver, *args)
    }

    fun newInstance(type: Class<*>, vararg args: Any?): Any = unwrap {
        val constructor = select(type.declaredConstructors.toList(), args.map { it?.javaClass }) { it.parameterTypes }
        constructor.isAccessible = true
        HookRuntime.attachedInterface()?.getInvoker(constructor)?.newInstance(*args)
            ?: constructor.newInstance(*args)
    }

    private fun <T> unwrap(action: () -> T): T = try { action() }
        catch (error: InvocationTargetException) { throw error.targetException }

    private fun hierarchy(type: Class<*>): Sequence<Class<*>> = generateSequence(type) { it.superclass }

    private fun <T> select(candidates: List<T>, args: List<Class<*>?>, parameters: (T) -> Array<Class<*>>): T {
        val ranked = candidates.mapNotNull { candidate ->
            cost(parameters(candidate), args)?.let { candidate to it }
        }
        // An interface supertype may be further away than Object in the graph, but is still
        // more specific. Remove dominated signatures before applying conversion costs.
        val specific = ranked.filter { candidate ->
            ranked.none { other -> moreSpecific(parameters(other.first), parameters(candidate.first)) }
        }
        val best = specific.minOfOrNull { it.second }
            ?: throw NoSuchMethodException("No matching member for ${args.map { it?.name }}")
        val tied = specific.filter { it.second == best }.map { it.first }
        if (tied.size == 1) return tied.single()
        return tied.singleOrNull { candidate ->
            tied.all { other -> candidate == other || moreSpecific(parameters(candidate), parameters(other)) }
        } ?: throw NoSuchMethodException("Ambiguous member for ${args.map { it?.name }}")
    }

    private fun moreSpecific(left: Array<Class<*>>, right: Array<Class<*>>): Boolean =
        left.indices.all { right[it].isAssignableFrom(left[it]) } && !left.contentEquals(right)

    private fun cost(parameters: Array<Class<*>>, args: List<Class<*>?>): Int? {
        if (parameters.size != args.size) return null
        var total = 0
        for (index in parameters.indices) {
            val parameter = parameters[index]
            val argument = args[index]
            total += when {
                argument == null -> if (parameter.isPrimitive) return null else 0
                parameter == argument -> 0
                parameter.isPrimitive -> {
                    val primitive = unbox(argument)
                    if (primitive == parameter) 1 else {
                        val widening = widenings[primitive]?.indexOf(parameter) ?: -1
                        if (widening < 0) return null else 3 + widening
                    }
                }
                parameter.isAssignableFrom(argument) -> 10 + distance(argument, parameter)
                else -> return null
            }
        }
        return total
    }

    private fun distance(from: Class<*>, to: Class<*>): Int {
        var frontier = setOf(from)
        val seen = mutableSetOf<Class<*>>()
        var steps = 0
        while (frontier.isNotEmpty()) {
            if (to in frontier) return steps
            seen.addAll(frontier)
            frontier = frontier.flatMap { listOfNotNull(it.superclass) + it.interfaces }.filterNot { it in seen }.toSet()
            steps++
        }
        return steps
    }

    private fun unbox(type: Class<*>): Class<*> = wrappers[type] ?: type
    private val wrappers = mapOf(
        java.lang.Boolean::class.java to Boolean::class.javaPrimitiveType!!,
        java.lang.Byte::class.java to Byte::class.javaPrimitiveType!!,
        java.lang.Character::class.java to Char::class.javaPrimitiveType!!,
        java.lang.Short::class.java to Short::class.javaPrimitiveType!!,
        java.lang.Integer::class.java to Int::class.javaPrimitiveType!!,
        java.lang.Long::class.java to Long::class.javaPrimitiveType!!,
        java.lang.Float::class.java to Float::class.javaPrimitiveType!!,
        java.lang.Double::class.java to Double::class.javaPrimitiveType!!,
    )
    private val widenings = mapOf(
        Byte::class.javaPrimitiveType to listOf(Short::class.javaPrimitiveType, Int::class.javaPrimitiveType, Long::class.javaPrimitiveType, Float::class.javaPrimitiveType, Double::class.javaPrimitiveType),
        Short::class.javaPrimitiveType to listOf(Int::class.javaPrimitiveType, Long::class.javaPrimitiveType, Float::class.javaPrimitiveType, Double::class.javaPrimitiveType),
        Char::class.javaPrimitiveType to listOf(Int::class.javaPrimitiveType, Long::class.javaPrimitiveType, Float::class.javaPrimitiveType, Double::class.javaPrimitiveType),
        Int::class.javaPrimitiveType to listOf(Long::class.javaPrimitiveType, Float::class.javaPrimitiveType, Double::class.javaPrimitiveType),
        Long::class.javaPrimitiveType to listOf(Float::class.javaPrimitiveType, Double::class.javaPrimitiveType),
        Float::class.javaPrimitiveType to listOf(Double::class.javaPrimitiveType),
    )
}
