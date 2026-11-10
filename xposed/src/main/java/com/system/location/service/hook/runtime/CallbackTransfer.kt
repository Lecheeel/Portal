package com.system.location.service.hook.runtime

import android.os.IBinder
import android.os.IInterface

/** Local metadata only. The wire row contains no listener proxy or module-defined object. */
internal data class CallbackSubscription(
    val listener: IInterface,
    val uid: Int,
    val packageName: String? = null,
    val providers: List<String> = emptyList(),
) {
    fun toBundle(): android.os.Bundle = android.os.Bundle().apply {
        putBinder("binder", listener.asBinder())
        putString("descriptor", listener.asBinder().interfaceDescriptor)
        putInt("uid", uid)
        putString("package", packageName)
        putStringArrayList("providers", ArrayList(providers))
    }
    fun encode(): Array<Any?> {
        val binder = listener.asBinder()
        return arrayOf(binder, binder.interfaceDescriptor, uid, packageName, providers.toTypedArray())
    }

    companion object {
        fun fromBundle(bundle: android.os.Bundle,
            resolve: (String, IBinder) -> IInterface = ::frameworkCallback): CallbackSubscription? {
            @Suppress("DEPRECATION")
            val binder = bundle.getBinder("binder") ?: return null
            val descriptor = bundle.getString("descriptor") ?: return null
            val providers = bundle.getStringArrayList("providers")?.toList().orEmpty()
            return decode(arrayOf(binder, descriptor, bundle.getInt("uid"),
                bundle.getString("package"), providers.toTypedArray()), resolve)
        }

        fun decode(row: Array<*>, resolve: (String, IBinder) -> IInterface = ::frameworkCallback): CallbackSubscription? {
            require(row.size == 5)
            val binder = row[0] as IBinder
            if (!binder.isBinderAlive) return null
            val descriptor = row[1] as String
            require(descriptor.startsWith("android.location.")) { "Unexpected callback interface" }
            require(binder.interfaceDescriptor == descriptor) { "Callback interface changed" }
            val uid = row[2] as Int
            val packageName = row[3] as String?
            val providers = (row[4] as Array<*>).map { it as String }
            val callback = try { resolve(descriptor, binder) }
            catch (dead: android.os.DeadObjectException) { return null }
            check(callback.asBinder() == binder)
            return CallbackSubscription(callback, uid, packageName, providers)
        }

        private fun frameworkCallback(descriptor: String, binder: IBinder): IInterface =
            Class.forName("${descriptor}\$Stub", false, ClassLoader.getSystemClassLoader())
                .getDeclaredMethod("asInterface", IBinder::class.java).invoke(null, binder) as IInterface
    }
}
