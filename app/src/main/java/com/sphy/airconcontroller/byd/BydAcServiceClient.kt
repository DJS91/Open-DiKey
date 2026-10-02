package com.sphy.airconcontroller.byd

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import dalvik.system.PathClassLoader
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Client for BYD's `com.byd.acservice` bound service — the path `com.byd.ac.BydAcManager`
 * uses. The service runs as the system uid, so it answers apps that lack the signature-level
 * `BYDAUTO_AC_*` permissions (DiLink 100F / Shark refuses `BYDAutoAcDevice` reads).
 *
 * [bind] blocks for the service connection, so call it off the main thread.
 */
class BydAcServiceClient(context: Context) {

    data class Read(val value: Any?, val raw: String, val error: String?) {
        val int: Int? get() = (value as? Number)?.toInt()
    }

    private val app = context.applicationContext
    private val loader: ClassLoader = File(AC_JAR).takeIf { it.canRead() }
        ?.let { PathClassLoader(it.path, app.classLoader) }
        ?: app.classLoader
    private var connection: ServiceConnection? = null
    private var conditioner: Any? = null

    var lastError: String? = null
        private set

    val isBound: Boolean get() = conditioner != null

    fun bind(timeoutMs: Long = BIND_TIMEOUT_MS): Boolean {
        if (conditioner != null) return true
        val latch = CountDownLatch(1)
        var root: IBinder? = null
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                root = binder
                latch.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName?) {
                conditioner = null
            }
            override fun onNullBinding(name: ComponentName?) {
                latch.countDown()
            }
        }
        val intent = Intent(ACTION).setPackage(PACKAGE)
        val ok = runCatching { app.bindService(intent, conn, Context.BIND_AUTO_CREATE) }
            .onFailure { lastError = "bindService: ${describe(it)}" }
            .getOrDefault(false)
        if (!ok) {
            if (lastError == null) lastError = "bindService returned false"
            runCatching { app.unbindService(conn) }
            return false
        }
        connection = conn
        latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        val binder = root ?: run {
            lastError = "no binder within ${timeoutMs}ms"
            unbind()
            return false
        }
        return runCatching {
            val service = asInterface(ROOT_INTERFACE, binder)
            val sub = service.javaClass.getMethod("getService", String::class.java)
                .invoke(service, CONDITIONER_SERVICE) as? IBinder
                ?: error("getService($CONDITIONER_SERVICE) returned null")
            conditioner = asInterface(CONDITIONER_INTERFACE, sub)
            true
        }.getOrElse {
            lastError = describe(it)
            unbind()
            false
        }
    }

    fun unbind() {
        conditioner = null
        connection?.let { runCatching { app.unbindService(it) } }
        connection = null
    }

    fun get(id: Int, area: Int): Read {
        val iface = conditioner ?: return Read(null, "—", "not bound")
        return runCatching {
            val pv = iface.javaClass.getMethod("getBydAutoAcValue", Int::class.java, Int::class.java)
                .invoke(iface, id, area)
            val value = pv?.let { it.javaClass.getMethod("getValue").invoke(it) }
            Read(value, pv?.toString() ?: "null", null)
        }.getOrElse { Read(null, "—", describe(it)) }
    }

    /** Returns null on success, otherwise the error. */
    fun set(id: Int, area: Int, value: Any): String? {
        val iface = conditioner ?: return "not bound"
        return runCatching {
            val pv = newPropertyValue(id, area, value)
            iface.javaClass.getMethod("setBydAutoAcValue", pv.javaClass).invoke(iface, pv)
            null
        }.getOrElse { describe(it) }
    }

    /** `IAcAirConditioner.changeTemp(PropertyValue, boolean)`; returns the service's String reply. */
    fun changeTemp(area: Int, value: Any, flag: Boolean): Result<String?> {
        val iface = conditioner ?: return Result.failure(IllegalStateException("not bound"))
        return runCatching {
            val pv = newPropertyValue(PROP_TEMPERATURE, area, value)
            iface.javaClass.getMethod("changeTemp", pv.javaClass, Boolean::class.java)
                .invoke(iface, pv, flag) as String?
        }.recoverCatching { throw unwrap(it) }
    }

    /** Zero-arg reads on `IAcAirConditioner` (isAcOnline, getAcType, …). */
    fun callInfo(name: String): Any? = conditioner?.let { iface ->
        runCatching { iface.javaClass.getMethod(name).invoke(iface) }.getOrNull()
    }

    fun propertyValueConstructors(): List<String> = runCatching {
        Class.forName(PROPERTY_VALUE, true, loader).declaredConstructors.map { c ->
            "PropertyValue(${c.parameterTypes.joinToString { it.simpleName }})"
        }
    }.getOrElse { listOf("err ${describe(it)}") }

    /**
     * The constructor signature isn't public, so try each (id, area, value) ordering against
     * every 2/3-arg constructor and keep the one whose getters read back what we passed.
     */
    private fun newPropertyValue(id: Int, area: Int, value: Any): Any {
        val cls = Class.forName(PROPERTY_VALUE, true, loader)
        val getId = cls.getMethod("getId")
        val getArea = cls.getMethod("getArea")
        val getValue = cls.getMethod("getValue")
        val wanted = listOf(id, area, value)
        val ctors = cls.declaredConstructors.sortedByDescending { it.parameterCount }
        for (ctor in ctors) {
            ctor.isAccessible = true
            val orders = when (ctor.parameterCount) {
                3 -> ORDERS_3
                2 -> ORDERS_2
                else -> continue
            }
            for (order in orders) {
                val args = order.map { wanted[it] }.toTypedArray()
                val pv = runCatching { ctor.newInstance(*args) }.getOrNull() ?: continue
                val matches = getId.invoke(pv) == id &&
                    getValue.invoke(pv) == value &&
                    (ctor.parameterCount == 2 || getArea.invoke(pv) == area)
                if (matches) return pv
            }
        }
        error("no PropertyValue constructor accepted (id, area, value): ${propertyValueConstructors()}")
    }

    private fun asInterface(descriptor: String, binder: IBinder): Any =
        Class.forName("$descriptor\$Stub", true, loader)
            .getMethod("asInterface", IBinder::class.java)
            .invoke(null, binder)!!

    companion object {
        const val PACKAGE = "com.byd.acservice"
        const val ACTION = "com.byd.ac.AC_SERVICE"
        private const val AC_JAR = "/system/framework/com.byd.ac.jar"
        private const val ROOT_INTERFACE = "com.byd.ac.IBydAcService"
        private const val CONDITIONER_INTERFACE = "com.byd.ac.IAcAirConditioner"
        private const val CONDITIONER_SERVICE = "AC_AIRCONDITIONER_SERVICE"
        private const val PROPERTY_VALUE = "com.byd.ac.PropertyValue"
        private const val BIND_TIMEOUT_MS = 4_000L

        // com.byd.ac.PropertyIds$AirConditioner
        const val PROP_POWER = 101
        const val PROP_TEMPERATURE = 102
        const val PROP_WIND_LEVEL = 103
        const val PROP_COMPRESSOR = 104
        const val PROP_FRONT_DEFROST = 107
        const val PROP_REAR_DEFROST = 108
        const val PROP_INTERNAL_CYCLE = 109
        const val PROP_TEMP_SYNC = 110
        const val PROP_WIND_MODE = 113
        const val PROP_CTRL_MODE = 114
        const val PROP_FRONT_POWER = 136

        // com.byd.ac.BydAcFeatures$AirConditioner
        const val AREA_NONE = 0
        const val AREA_DRIVER = 256
        const val AREA_PASSENGER = 272

        const val ERROR_STATUS = 65535

        private val ORDERS_3 = listOf(
            listOf(0, 1, 2), listOf(1, 0, 2), listOf(0, 2, 1),
            listOf(2, 0, 1), listOf(1, 2, 0), listOf(2, 1, 0),
        )
        private val ORDERS_2 = listOf(listOf(0, 2), listOf(2, 0))

        private fun unwrap(t: Throwable): Throwable = (t as? InvocationTargetException)?.cause ?: t

        private fun describe(t: Throwable): String = unwrap(t).let { "${it.javaClass.simpleName}: ${it.message}" }
    }
}
