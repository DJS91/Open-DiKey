package com.sphy.airconcontroller.diagnostics

import android.Manifest
import android.app.ActivityManager
import android.bluetooth.BluetoothManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.core.content.pm.PackageInfoCompat
import com.sphy.airconcontroller.OpenDiKeyApp
import com.sphy.airconcontroller.adb.AdbPermissionManager
import com.sphy.airconcontroller.boot.DiKeyListenService
import com.sphy.airconcontroller.byd.BydAcController
import com.sphy.airconcontroller.byd.BydPermissionContext
import com.sphy.airconcontroller.byd.BydVehicleInfoController
import com.sphy.airconcontroller.byd.Dilink5SdkInjector
import com.sphy.airconcontroller.usb.UsbHostSerial
import dalvik.system.PathClassLoader
import kotlinx.coroutines.delay
import java.io.File
import java.io.InputStream
import java.lang.reflect.InvocationTargetException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

/**
 * Read-only walk through every stage Open DiKey depends on (ADB → permissions →
 * hidden API → OEM SDK → device binding → DiKey transport), producing a plain-text
 * report with a PASS/WARN/FAIL summary for comparing unsupported vehicles.
 * Must be called off the main thread.
 */
class DiagnosticsRunner(
    context: Context,
    private val liveLog: () -> List<String>,
) {
    enum class Status { PASS, WARN, FAIL, INFO }

    private data class Check(val status: Status, val name: String, val detail: String)

    private class Step(val title: String, val block: suspend () -> Unit)

    private val app = context.applicationContext
    private val pkg = app.packageName
    private val checks = mutableListOf<Check>()
    private val body = StringBuilder()
    private var adbResults: Map<String, AdbPermissionManager.ShellResult> = emptyMap()

    suspend fun run(onProgress: (String) -> Unit): String {
        checks.clear()
        body.setLength(0)
        val sections = listOf(
            Step("Head unit") { headUnit() },
            Step("Local ADB") { localAdb() },
            Step("App permissions") { permissions() },
            Step("Hidden-API access") { hiddenApi() },
            Step("OEM SDK packages") { oemPackages() },
            Step("SDK class scan") { sdkClassScan() },
            Step("SDK classes and injection") { sdkClasses() },
            Step("BYD device binding") { deviceBinding() },
            Step("Climate controller") { climate() },
            Step("AC service probe") { acServiceProbe() },
            Step("Vehicle info") { vehicleInfo() },
            Step("DiKey connection") { dikeyConnection() },
            Step("USB devices") { usbDevices() },
            Step("USB bridge query") { usbBridgeQuery() },
            Step("Bluetooth") { bluetooth() },
            Step("Background running") { background() },
            Step("ADB shell probes") { adbProbes() },
            Step("Live DiKey log") { liveLogSection() },
            Step("Recent app logcat") { logcat() },
        )
        sections.forEachIndexed { index, step ->
            onProgress("Running ${index + 1}/${sections.size}: ${step.title}…")
            section("${index + 1}. ${step.title}")
            val start = SystemClock.elapsedRealtime()
            try {
                step.block()
            } catch (t: Throwable) {
                val c = unwrap(t)
                line("!! section crashed: ${c.javaClass.name}: ${c.message}")
                check(Status.FAIL, step.title, "section crashed: ${c.javaClass.simpleName}: ${c.message}")
            }
            line("(took ${SystemClock.elapsedRealtime() - start} ms)")
        }
        return buildReport("Open DiKey diagnostics")
    }

    /**
     * Writes to the car: nudges driver temperature and fan speed, reads back, then restores.
     * Tells us whether SET paths work when GET/binding already do.
     */
    suspend fun runClimateWriteTest(onProgress: (String) -> Unit): String {
        checks.clear()
        body.setLength(0)
        section("Climate write test")
        onProgress("Binding climate…")
        val ac = BydAcController(app)
        if (!ac.bind()) {
            line("bind failed: ${ac.lastBindError}")
            check(Status.FAIL, "AC bind", ac.lastBindError ?: "unknown")
            return buildReport("Open DiKey climate write test")
        }
        val before = ac.snapshot()
        line("Before:")
        line(before.toDisplayString().prependIndent("  "))
        if (before.powerOn != true) {
            line("Note: climate reports OFF/unknown. Writes may be ignored until the car is READY and climate is on.")
        }

        onProgress("Testing driver temperature…")
        val origTemp = before.driverTempC
        if (origTemp == null) {
            check(Status.WARN, "Driver temp write", "driver temp not readable; skipped")
        } else {
            val target = if (origTemp >= BydAcController.TEMP_MAX) origTemp - 1 else origTemp + 1
            val set = ac.setDriverTemp(target)
            delay(WRITE_SETTLE_MS)
            val after = ac.snapshot().driverTempC
            line("setDriverTemp($target): ${fmtResult(set)} → readback $after")
            val restore = ac.setDriverTemp(origTemp)
            delay(WRITE_SETTLE_MS)
            line("restore setDriverTemp($origTemp): ${fmtResult(restore)} → readback ${ac.snapshot().driverTempC}")
            check(
                when {
                    after == target -> Status.PASS
                    set.success -> Status.WARN
                    else -> Status.FAIL
                },
                "Driver temp write",
                "call=${set.method} ${set.detail}; wanted $target, read $after",
            )
        }

        onProgress("Testing fan speed…")
        val origFan = before.fanLevel
        if (origFan == null) {
            check(Status.WARN, "Fan write", "fan level not readable; skipped")
        } else {
            val target = if (origFan >= 7) origFan - 1 else origFan + 1
            val set = ac.setFanLevel(target)
            delay(WRITE_SETTLE_MS)
            val after = ac.snapshot().fanLevel
            line("setFanLevel($target): ${fmtResult(set)} → readback $after")
            val restore = ac.setFanLevel(origFan)
            delay(WRITE_SETTLE_MS)
            line("restore setFanLevel($origFan): ${fmtResult(restore)}")
            check(
                if (set.success) Status.PASS else Status.FAIL,
                "Fan write",
                "call=${set.method} ${set.detail}; wanted $target, read $after",
            )
        }

        line("After:")
        line(ac.snapshot().toDisplayString().prependIndent("  "))
        return buildReport("Open DiKey climate write test")
    }

    // region sections

    private fun headUnit() {
        val pi = app.packageManager.getPackageInfo(pkg, 0)
        kv("App version", "${pi.versionName} (${PackageInfoCompat.getLongVersionCode(pi)})")
        kv("Manufacturer", Build.MANUFACTURER)
        kv("Brand", Build.BRAND)
        kv("Model", Build.MODEL)
        kv("Product", Build.PRODUCT)
        kv("Device", Build.DEVICE)
        kv("Board", Build.BOARD)
        kv("Hardware", Build.HARDWARE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            kv("SoC", "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}")
        }
        kv("Display ID", Build.DISPLAY)
        kv("Build ID", Build.ID)
        kv("Incremental", Build.VERSION.INCREMENTAL)
        kv("Fingerprint", Build.FINGERPRINT)
        kv("Android", "${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        kv("Security patch", Build.VERSION.SECURITY_PATCH)
        kv("Uptime", "${SystemClock.elapsedRealtime() / 60_000} min")
        val dm = app.resources.displayMetrics
        kv("Display", "${dm.widthPixels}x${dm.heightPixels} @ ${dm.densityDpi}dpi")
        check(Status.INFO, "Head unit", "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} · ${Build.DISPLAY}")
    }

    private suspend fun localAdb() {
        val portOpen = AdbPermissionManager.isPortOpen()
        kv("Port 5555 reachable", portOpen)
        if (!portOpen) {
            check(Status.FAIL, "Local ADB", "port 5555 not reachable — enable USB/wireless debugging in Developer Options")
            return
        }
        val results = AdbPermissionManager.runShellBatch(
            app,
            ADB_COMMANDS.map { it.replace("\$pkg", pkg) },
            perCommandTimeoutMs = ADB_TIMEOUT_MS,
        )
        if (results.isEmpty()) {
            check(Status.FAIL, "Local ADB", "port open but not authorized — accept the 'Allow USB debugging' prompt")
            return
        }
        adbResults = ADB_COMMANDS.zip(results).toMap()
        val id = adbResults[ADB_ID]?.output.orEmpty()
        kv("Shell identity", id)
        check(Status.PASS, "Local ADB", "authorized · ${id.substringBefore(' ')}")
    }

    private fun permissions() {
        val pm = app.packageManager
        val info = pm.getPackageInfo(pkg, PackageManager.GET_PERMISSIONS)
        val requested = info.requestedPermissions.orEmpty()
        val interesting = requested.filter {
            it.contains("BYDAUTO") || it in CORE_PERMISSIONS
        }
        var bydRequested = 0
        var bydDefined = 0
        var bydGranted = 0
        for (perm in interesting) {
            val defined = runCatching { pm.getPermissionInfo(perm, 0) }.getOrNull()
            val granted = pm.checkPermission(perm, pkg) == PackageManager.PERMISSION_GRANTED
            val where = defined?.let { "${it.packageName}/${protection(it)}" } ?: "NOT DEFINED ON CAR"
            line("  ${if (granted) "✓" else "✗"} $perm · $where")
            if (perm.contains("BYDAUTO")) {
                bydRequested++
                if (defined != null) bydDefined++
                if (granted) bydGranted++
            }
        }

        val coreMissing = CORE_PERMISSIONS.filter {
            pm.checkPermission(it, pkg) != PackageManager.PERMISSION_GRANTED
        }
        check(
            if (coreMissing.isEmpty()) Status.PASS else Status.FAIL,
            "ADB-granted permissions",
            if (coreMissing.isEmpty()) "WRITE_SECURE_SETTINGS + READ_LOGS granted" else "missing ${coreMissing.joinToString()}",
        )
        check(
            when {
                bydDefined == 0 -> Status.FAIL
                bydDefined < bydRequested -> Status.WARN
                else -> Status.PASS
            },
            "BYDAUTO permissions defined",
            "$bydDefined/$bydRequested defined on this car, $bydGranted granted",
        )

        line()
        line("BYD permissions defined on this car but not requested by the app:")
        val ours = requested.toSet()
        val extra = definedBydPermissions(pm).filter { it.first !in ours }
        if (extra.isEmpty()) line("  (none)") else extra.forEach { (name, owner) -> line("  $name · $owner") }
    }

    private fun hiddenApi() {
        kv("User consent", AdbPermissionManager.hasHiddenApiConsent(app))
        kv("Prompted", AdbPermissionManager.hasBeenPromptedForHiddenApi(app))
        val policy = globalSetting("hidden_api_policy") ?: adbResults[ADB_HIDDEN_POLICY]?.output
        val exemptions = globalSetting("hidden_api_blacklist_exemptions") ?: adbResults[ADB_HIDDEN_EXEMPT]?.output
        kv("hidden_api_policy", policy)
        kv("hidden_api_blacklist_exemptions", exemptions)

        val reflect = runCatching {
            Class.forName("dalvik.system.BaseDexClassLoader")
                .getDeclaredField("pathList")
                .apply { isAccessible = true }
                .get(app.classLoader)
        }
        kv("BaseDexClassLoader.pathList reflection", reflect.fold({ "OK" }, { "${unwrap(it).javaClass.simpleName}: ${unwrap(it).message}" }))
        check(
            if (reflect.isSuccess) Status.PASS else Status.FAIL,
            "Hidden-API reflection",
            if (reflect.isSuccess) "classloader injection possible" else "blocked — exemption not active (reboot resets it; reopen app / accept consent)",
        )
    }

    private fun oemPackages() {
        val pm = app.packageManager
        for (name in OEM_PACKAGES) {
            val info = runCatching { pm.getPackageInfo(name, 0) }.getOrNull()
            if (info == null) {
                line("$name: NOT INSTALLED")
                check(Status.WARN, "OEM package $name", "not installed")
                continue
            }
            val ai = info.applicationInfo
            val apks = listOfNotNull(ai?.sourceDir) + ai?.splitSourceDirs.orEmpty()
            line("$name: ${info.versionName} (${PackageInfoCompat.getLongVersionCode(info)})")
            apks.forEach { path ->
                val dex = runCatching {
                    ZipFile(path).use { zip ->
                        zip.entries().asSequence().filter { it.name.matches(DEX_ENTRY) }.map { "${it.name}=${it.size / 1024}KB" }.toList()
                    }
                }.getOrElse { listOf("unreadable: ${it.message}") }
                line("  $path")
                line("    dex: ${dex.ifEmpty { listOf("none (odex/vdex only?)") }.joinToString()}")
            }
            check(Status.PASS, "OEM package $name", "${info.versionName}, ${apks.size} apk(s)")
        }

        line()
        line("Other BYD / DiLink packages:")
        byd(pm).filter { it.packageName !in OEM_PACKAGES }.forEach {
            line("  ${it.packageName} ${it.versionName} (${PackageInfoCompat.getLongVersionCode(it)})")
        }
    }

    private fun sdkClassScan() {
        line("Searching BYD package dex files for the SDK classes the app needs…")
        val pm = app.packageManager
        val needles = SCAN_CLASSES.map { "L${it.replace('.', '/')};".toByteArray(Charsets.US_ASCII) }
        val hits = mutableMapOf<String, MutableSet<String>>()
        for (info in byd(pm)) {
            val ai = info.applicationInfo ?: continue
            val apks = listOfNotNull(ai.sourceDir) + ai.splitSourceDirs.orEmpty()
            val found = mutableSetOf<Int>()
            for (path in apks) {
                runCatching {
                    ZipFile(path).use { zip ->
                        zip.entries().asSequence().filter { it.name.matches(DEX_ENTRY) }.forEach { entry ->
                            zip.getInputStream(entry).use { scanStream(it, needles, found) }
                        }
                    }
                }
            }
            if (found.isNotEmpty()) {
                val names = found.sorted().map { SCAN_CLASSES[it].substringAfterLast('.') }
                line("  ${info.packageName}: ${names.joinToString()}")
                names.forEach { hits.getOrPut(it) { mutableSetOf() }.add(info.packageName) }
            }
        }
        if (hits.isEmpty()) line("  (no BYD package references the SDK classes)")

        val acHolders = hits["BYDAutoAcDevice"].orEmpty()
        val injected = acHolders.intersect(OEM_PACKAGES.toSet())
        check(
            when {
                acHolders.isEmpty() -> Status.WARN
                injected.isEmpty() -> Status.FAIL
                else -> Status.PASS
            },
            "AC SDK class location",
            when {
                acHolders.isEmpty() -> "no BYD APK references BYDAutoAcDevice (may be in framework — see next section)"
                injected.isEmpty() -> "only in ${acHolders.joinToString()} — NOT in the packages the app injects (${OEM_PACKAGES.joinToString()})"
                else -> "found in ${acHolders.joinToString()}"
            },
        )
    }

    private fun sdkClasses() {
        val boot = Any::class.java.classLoader
        val bootHas = DEVICE_CLASSES.associate { (_, cls) ->
            cls to runCatching { Class.forName(cls, false, boot) }.isSuccess
        }
        val loadableBefore = Dilink5SdkInjector.isLoadable(app)
        val ensured = Dilink5SdkInjector.ensure(app)
        kv("Loadable before inject", loadableBefore)
        kv("Dilink5SdkInjector.ensure", ensured)
        kv("DiPilot has SpeedAdjust (CarSettings SDK)", Dilink5SdkInjector.diPilotHasSpeedAdjust(app))
        line()
        var loadable = 0
        for ((label, cls) in DEVICE_CLASSES) {
            val appHas = runCatching { Class.forName(cls, false, app.classLoader) }.isSuccess
            if (appHas) loadable++
            line("  ${if (appHas) "✓" else "✗"} $label · framework=${bootHas[cls]} · app=$appHas")
        }
        check(
            when {
                loadable == 0 -> Status.FAIL
                loadable < DEVICE_CLASSES.size -> Status.WARN
                else -> Status.PASS
            },
            "SDK classes loadable",
            "$loadable/${DEVICE_CLASSES.size} (ensure=$ensured)",
        )
    }

    private fun deviceBinding() {
        val permCtx = BydPermissionContext(app)
        var bound = 0
        for ((label, cls) in DEVICE_CLASSES) {
            val clazz = runCatching { Class.forName(cls, false, app.classLoader) }.getOrNull()
            if (clazz == null) {
                line("  ✗ $label · class missing")
                continue
            }
            val result = runCatching {
                clazz.getMethod("getInstance", Context::class.java).invoke(null, permCtx)
            }
            val inst = result.getOrNull()
            if (inst != null) {
                bound++
                val methods = inst.javaClass.methods.count { it.declaringClass.name.contains("bydauto") }
                line("  ✓ $label · ${inst.javaClass.name} · $methods SDK methods")
            } else {
                val err = result.exceptionOrNull()?.let { "${unwrap(it).javaClass.simpleName}: ${unwrap(it).message}" } ?: "getInstance returned null"
                line("  ✗ $label · $err")
                if (label == "AC") check(Status.FAIL, "AC device bind", err)
            }
            if (label == "AC" && inst != null) {
                check(Status.PASS, "AC device bind", inst.javaClass.name)
                acGetters(inst)
            }
        }
        check(
            if (bound > 0) Status.PASS else Status.FAIL,
            "BYD devices bound",
            "$bound/${DEVICE_CLASSES.size}",
        )
    }

    private fun acGetters(device: Any) {
        line()
        line("  AC read-only getters:")
        val getters = device.javaClass.methods
            .filter { m ->
                m.parameterCount == 0 && m.name.startsWith("get") &&
                    m.declaringClass.name.contains("bydauto") &&
                    (m.returnType == Int::class.javaPrimitiveType || m.returnType == Boolean::class.javaPrimitiveType)
            }
            .sortedBy { it.name }
            .take(MAX_GETTERS)
        var live = 0
        for (m in getters) {
            val value = runCatching { m.invoke(device) }.fold({ it.toString() }, { "err ${unwrap(it).javaClass.simpleName}" })
            if (m.name !in DEVICE_CONSTANT_GETTERS && value.toIntOrNull()?.let { it !in SENTINELS } == true) live++
            line("    ${m.name} = $value")
        }
        for ((zone, zoneName) in listOf(1 to "driver", 2 to "passenger", 4 to "outside")) {
            val v = runCatching {
                device.javaClass.getMethod("getTemprature", Int::class.javaPrimitiveType).invoke(device, zone)
            }.fold({ it.toString() }, { "err ${unwrap(it).javaClass.simpleName}" })
            line("    getTemprature($zone /* $zoneName */) = $v")
        }
        check(
            if (live > 0) Status.PASS else Status.WARN,
            "AC getters return data",
            "$live/${getters.size} return non-sentinel values",
        )
    }

    /**
     * Read-only look at the standalone `byd_airconditioning` binder service and its client
     * jar. Some head units (DiLink 100F) refuse BYDAutoAcDevice reads but may expose AC here.
     */
    private fun acServiceProbe() {
        val binderResult = runCatching {
            Class.forName("android.os.ServiceManager")
                .getMethod("getService", String::class.java)
                .invoke(null, AC_SERVICE) as? IBinder
        }
        val binder = binderResult.getOrNull()
        kv(
            "ServiceManager.getService($AC_SERVICE)",
            binder?.let { "${it.javaClass.name} alive=${it.isBinderAlive}" }
                ?: binderResult.exceptionOrNull()?.let { "err ${unwrap(it).javaClass.simpleName}: ${unwrap(it).message}" }
                ?: "null",
        )
        val descriptor = runCatching { binder?.interfaceDescriptor }.getOrNull()
        kv("Interface descriptor", descriptor)

        val jar = File(AC_JAR)
        kv("$AC_JAR readable", jar.canRead())
        val classNames = if (jar.canRead()) acJarClasses(jar) else emptyList()
        val loader = if (jar.canRead()) PathClassLoader(jar.path, app.classLoader) else app.classLoader
        line("Classes in jar (${classNames.size}):")
        var printed = 0
        for (name in classNames) {
            val cls = runCatching { Class.forName(name, false, loader) }
            line("  ${if (cls.isSuccess) "✓" else "✗"} $name")
            val c = cls.getOrNull() ?: continue
            for (m in c.declaredMethods.sortedBy { it.name }) {
                if (printed >= MAX_AC_JAR_METHODS) break
                line("      ${m.name}(${m.parameterTypes.joinToString { it.simpleName }}):${m.returnType.simpleName}")
                printed++
            }
        }
        if (printed >= MAX_AC_JAR_METHODS) line("  … method list truncated at $MAX_AC_JAR_METHODS")

        if (binder != null && descriptor != null) {
            line()
            line("Service read-only calls via $descriptor:")
            callAidlReads(loader, binder, descriptor)
        }
        check(
            if (binder == null) Status.INFO else Status.PASS,
            "AC service ($AC_SERVICE)",
            if (binder == null) "not visible to apps (expected; app service is the client path)" else "reachable · $descriptor",
        )

        line()
        line("Client-library constants:")
        val stringConstants = dumpAcConstants(loader)

        line()
        line("Bound app service ($AC_APP_PACKAGE · $AC_APP_ACTION):")
        val serviceNames = (stringConstants + AC_SUBSERVICE_GUESSES).distinct()
        val (live, total, bound) = probeAcAppService(loader, serviceNames)
        check(
            when {
                !bound -> Status.FAIL
                live > 0 -> Status.PASS
                else -> Status.WARN
            },
            "AC app service",
            if (!bound) "could not bind $AC_APP_PACKAGE" else "$live/$total read calls answered",
        )
    }

    /** Prints static fields of the client library's constant holders; returns the String values. */
    private fun dumpAcConstants(loader: ClassLoader): List<String> {
        val strings = mutableListOf<String>()
        for (name in AC_CONSTANT_CLASSES) {
            val cls = runCatching { Class.forName(name, true, loader) }.getOrNull() ?: continue
            line("  $name")
            cls.declaredConstructors.forEach { c ->
                line("    <init>(${c.parameterTypes.joinToString { it.simpleName }})")
            }
            cls.declaredFields
                .filter { java.lang.reflect.Modifier.isStatic(it.modifiers) && !it.isSynthetic }
                .sortedBy { it.name }
                .forEach { f ->
                    val v = runCatching { f.isAccessible = true; f.get(null) }.getOrNull()
                    if (v is String) strings += v
                    if (v == null || v is String || v is Number || v is Boolean) line("    ${f.name} = $v")
                }
        }
        return strings
    }

    private data class AcAppProbe(val live: Int, val total: Int, val bound: Boolean)

    /** Binds the same service `BydAcManager.connect()` uses and reads each sub-interface. */
    private fun probeAcAppService(loader: ClassLoader, serviceNames: List<String>): AcAppProbe {
        val latch = CountDownLatch(1)
        var service: IBinder? = null
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                service = binder
                latch.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName?) {}
            override fun onNullBinding(name: ComponentName?) {
                latch.countDown()
            }
        }
        val intent = Intent(AC_APP_ACTION).setPackage(AC_APP_PACKAGE)
        val bindResult = runCatching { app.bindService(intent, conn, Context.BIND_AUTO_CREATE) }
        kv("  bindService", bindResult.fold({ it.toString() }, { "err ${unwrap(it).javaClass.simpleName}: ${unwrap(it).message}" }))
        if (bindResult.getOrNull() != true) {
            runCatching { app.unbindService(conn) }
            return AcAppProbe(0, 0, false)
        }
        try {
            latch.await(AC_BIND_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            val root = service
            if (root == null) {
                line("  no binder within ${AC_BIND_TIMEOUT_MS}ms")
                return AcAppProbe(0, 0, false)
            }
            val rootDescriptor = runCatching { root.interfaceDescriptor }.getOrNull() ?: AC_ROOT_INTERFACE
            line("  connected · $rootDescriptor")
            var (live, total) = callAidlReads(loader, root, rootDescriptor)

            val rootIface = asAidlInterface(loader, rootDescriptor, root).getOrNull()
            val getService = rootIface?.javaClass?.methods?.firstOrNull {
                it.name == "getService" && it.parameterTypes.contentEquals(arrayOf(String::class.java))
            }
            if (getService == null) {
                line("  getService(String) unavailable on $rootDescriptor")
                return AcAppProbe(live, total, true)
            }
            for (name in serviceNames) {
                val sub = runCatching { getService.invoke(rootIface, name) as? IBinder }
                val subBinder = sub.getOrNull()
                val subDescriptor = runCatching { subBinder?.interfaceDescriptor }.getOrNull()
                val shown = when {
                    sub.isFailure -> "err ${unwrap(sub.exceptionOrNull()!!).javaClass.simpleName}: ${unwrap(sub.exceptionOrNull()!!).message}"
                    subBinder == null -> null
                    else -> subDescriptor ?: "binder (no descriptor)"
                }
                if (shown == null) continue
                line()
                line("  getService(\"$name\") = $shown")
                if (subBinder != null && subDescriptor != null) {
                    val (l, t) = callAidlReads(loader, subBinder, subDescriptor)
                    live += l
                    total += t
                }
            }
            return AcAppProbe(live, total, true)
        } finally {
            runCatching { app.unbindService(conn) }
        }
    }

    private fun asAidlInterface(loader: ClassLoader, descriptor: String, binder: IBinder) = runCatching {
        Class.forName("$descriptor\$Stub", true, loader)
            .getMethod("asInterface", IBinder::class.java)
            .invoke(null, binder)!!
    }

    /** Calls zero-arg get/is/has methods on an AIDL proxy; returns (answered, attempted). */
    private fun callAidlReads(loader: ClassLoader, binder: IBinder, descriptor: String): Pair<Int, Int> {
        val proxy = asAidlInterface(loader, descriptor, binder)
        val iface = proxy.getOrNull()
        if (iface == null) {
            line("    asInterface failed: ${proxy.exceptionOrNull()?.let { "${unwrap(it).javaClass.simpleName}: ${unwrap(it).message}" }}")
            return 0 to 0
        }
        val getters = iface.javaClass.methods
            .filter { m ->
                m.parameterCount == 0 && READ_PREFIXES.any { m.name.startsWith(it) } &&
                    m.declaringClass.name.startsWith(descriptor) && m.name != "getInterfaceDescriptor"
            }
            .sortedBy { it.name }
            .take(MAX_GETTERS)
        var live = 0
        for (m in getters) {
            val value = runCatching { m.invoke(iface) }
                .fold({ fmtValue(it) }, { "err ${unwrap(it).javaClass.simpleName}: ${unwrap(it).message}" })
            if (!value.startsWith("err ")) live++
            line("    ${m.name} = $value")
        }
        if (getters.isEmpty()) line("    (no zero-arg get/is/has methods)")
        return live to getters.size
    }

    private fun fmtValue(v: Any?): String = when (v) {
        is IntArray -> v.contentToString()
        is Array<*> -> v.contentToString()
        else -> v.toString()
    }

    private fun acJarClasses(jar: File): List<String> = runCatching {
        ZipFile(jar).use { zip ->
            zip.entries().asSequence()
                .filter { DEX_ENTRY.matches(it.name) }
                .flatMap { entry ->
                    val text = zip.getInputStream(entry).use { String(it.readBytes(), Charsets.ISO_8859_1) }
                    AC_CLASS_DESCRIPTOR.findAll(text).map { it.groupValues[1].replace('/', '.') }
                }
                .distinct()
                .sorted()
                .toList()
        }
    }.getOrDefault(emptyList())

    private fun climate() {
        val ac = BydAcController(app)
        val bound = ac.bind()
        kv("BydAcController.bind", bound)
        ac.lastBindError?.let { kv("Bind error", it) }
        line(ac.snapshot().toDisplayString())
        line()
        line(ac.dumpMethods())
    }

    private fun vehicleInfo() {
        val info = BydVehicleInfoController(app)
        line(info.snapshot(includeImu = false).toDisplayString())
    }

    private fun dikeyConnection() {
        val session = OpenDiKeyApp.from(app).dikey
        kv("Status", session.status.value)
        kv("DiKey ready", session.isConnected)
        kv("USB bridge open", session.isUsbOpen)
        kv("USB bridge sees DiKey", session.usbBridge.isDikeyReady)
        kv("Bluetooth permissions", session.hasBluetoothPermissions())
        kv("Selected BLE", session.selectedBle?.let { "${it.displayName} ${it.address}" })
        kv("Last saved BLE address", session.settings.dikeyLastAddress)
        check(
            when {
                session.isConnected -> Status.PASS
                session.isUsbOpen -> Status.WARN
                else -> Status.FAIL
            },
            "DiKey connection",
            when {
                session.isConnected -> "ready"
                session.isUsbOpen -> "USB bridge open but DiKey not ready: ${session.status.value}"
                else -> session.status.value
            },
        )
    }

    private fun usbDevices() {
        val usb = app.getSystemService(Context.USB_SERVICE) as UsbManager
        kv("USB host feature", app.packageManager.hasSystemFeature(PackageManager.FEATURE_USB_HOST))
        val devices = usb.deviceList.values.toList()
        kv("Attached devices", devices.size)
        var probe: String? = null
        for (d in devices) {
            val likely = UsbHostSerial.isLikelyProbe(d)
            if (likely && probe == null) probe = UsbHostSerial.label(d)
            line("  ${if (likely) "★" else "·"} ${UsbHostSerial.label(d)}")
            line("      mfg=${d.manufacturerName} path=${d.deviceName} class=${d.deviceClass}/${d.deviceSubclass}/${d.deviceProtocol} permission=${usb.hasPermission(d)}")
            for (i in 0 until d.interfaceCount) {
                val intf = d.getInterface(i)
                val eps = (0 until intf.endpointCount).joinToString { e ->
                    val ep = intf.getEndpoint(e)
                    val type = when (ep.type) {
                        UsbConstants.USB_ENDPOINT_XFER_BULK -> "bulk"
                        UsbConstants.USB_ENDPOINT_XFER_INT -> "int"
                        UsbConstants.USB_ENDPOINT_XFER_CONTROL -> "ctrl"
                        else -> "iso"
                    }
                    "$type-${if (ep.direction == UsbConstants.USB_DIR_IN) "in" else "out"}"
                }
                line("      if$i class=${intf.interfaceClass} [$eps]")
            }
        }
        check(
            if (probe != null) Status.PASS else Status.FAIL,
            "USB bridge attached",
            probe ?: "no ESP32 bridge visible (${devices.size} USB device(s)) — try the USB-C port / another cable",
        )
    }

    private suspend fun usbBridgeQuery() {
        val session = OpenDiKeyApp.from(app).dikey
        if (!session.isUsbOpen) {
            line("USB bridge not open — skipped")
            return
        }
        val before = liveLog().size
        val sent = listOf("ID", "PING", "STATUS").map { cmd -> cmd to session.usbBridge.serial.writeLine(cmd) }
        sent.forEach { (cmd, ok) -> line("  → $cmd (${if (ok) "sent" else "WRITE FAILED"})") }
        delay(BRIDGE_REPLY_MS)
        val replies = liveLog().drop(before)
        if (replies.isEmpty()) line("  (no replies in ${BRIDGE_REPLY_MS} ms)") else replies.forEach { line("  ← $it") }
        check(
            if (replies.isNotEmpty()) Status.PASS else Status.FAIL,
            "USB bridge responds",
            if (replies.isNotEmpty()) "${replies.size} line(s)" else "no reply to ID/PING/STATUS — reflash bridge firmware?",
        )
    }

    private fun bluetooth() {
        val manager = app.getSystemService(BluetoothManager::class.java)
        val adapter = manager?.adapter
        kv("Adapter", adapter != null)
        kv("Enabled", runCatching { adapter?.isEnabled }.getOrNull())
        kv("BLE supported", app.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE))
        val canConnect = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(app, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        if (canConnect && adapter != null) {
            val bonded = runCatching { adapter.bondedDevices.orEmpty() }.getOrDefault(emptySet())
            line("Bonded devices:")
            if (bonded.isEmpty()) line("  (none)")
            bonded.forEach { d -> line("  ${runCatching { d.name }.getOrNull()} ${d.address} type=${d.type}") }
        } else {
            line("Bonded devices: BLUETOOTH_CONNECT not granted")
        }
        val session = OpenDiKeyApp.from(app).dikey
        kv("Scan state", session.bleScanState.value)
        val seen = session.bleDevices.value
        line("Seen by scanner (${seen.size}):")
        seen.take(MAX_BLE).forEach { d ->
            line("  ${d.displayName} ${d.address} rssi=${d.rssi} ${d.transport} connectable=${d.connectable}")
        }
        val dikey = seen.any { it.name?.contains("DiKey", ignoreCase = true) == true }
        check(
            if (dikey) Status.PASS else Status.INFO,
            "DiKey seen over BLE",
            if (dikey) "yes" else "no (normal when the USB bridge owns the DiKey)",
        )
    }

    private fun background() {
        val am = app.getSystemService(ActivityManager::class.java)
        @Suppress("DEPRECATION")
        val running = am?.getRunningServices(Int.MAX_VALUE).orEmpty()
            .any { it.service.className == DiKeyListenService::class.java.name }
        kv("Listener service running", running)
        val pm = app.getSystemService(PowerManager::class.java)
        kv("Ignoring battery optimizations", pm?.isIgnoringBatteryOptimizations(pkg))
        kv("Overlay permission", Settings.canDrawOverlays(app))
        check(
            if (running) Status.PASS else Status.WARN,
            "Background listener",
            if (running) "running" else "not running",
        )
    }

    private fun adbProbes() {
        if (adbResults.isEmpty()) {
            line("ADB not available — skipped")
            return
        }
        for ((cmd, result) in adbResults) {
            line("$ ${cmd.replace("\$pkg", pkg)}")
            line(result.output.ifBlank { "(no output)" }.prependIndent("  "))
            if (result.exitCode != 0) line("  [exit ${result.exitCode}]")
        }
    }

    private fun liveLogSection() {
        val lines = liveLog()
        if (lines.isEmpty()) line("(nothing captured — press DiKey buttons while this screen is open, then run again)")
        lines.forEach { line(it) }
    }

    private fun logcat() {
        val proc = ProcessBuilder(
            "logcat", "-d", "-v", "time", "-t", LOGCAT_LINES.toString(), "--pid", Process.myPid().toString(),
        ).redirectErrorStream(true).start()
        val out = StringBuffer()
        val reader = Thread {
            runCatching {
                proc.inputStream.bufferedReader().forEachLine { out.append(it).append('\n') }
            }
        }.apply { isDaemon = true; start() }
        // Android 13+ holds logcat open while the "access all device logs" consent prompt is pending.
        if (!proc.waitFor(LOGCAT_TIMEOUT_S, TimeUnit.SECONDS)) {
            proc.destroyForcibly()
            reader.join(500)
            line("(logcat timed out after ${LOGCAT_TIMEOUT_S}s — check for a log-access prompt on screen)")
        } else {
            reader.join(1_000)
        }
        line(out.toString().trim().ifBlank { "(empty)" })
    }

    // endregion

    // region helpers

    private fun buildReport(title: String): String = buildString {
        appendLine(title)
        appendLine("Generated ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
        appendLine()
        appendLine("== SUMMARY ==")
        checks.forEach { appendLine("[${it.status}] ${it.name} — ${it.detail}") }
        appendLine()
        append(body)
    }

    private fun section(title: String) {
        body.appendLine()
        body.appendLine("== $title ==")
    }

    private fun line(text: String = "") {
        body.appendLine(text)
    }

    private fun kv(key: String, value: Any?) = line("$key: ${value ?: "—"}")

    private fun check(status: Status, name: String, detail: String) {
        checks += Check(status, name, detail)
    }

    private fun globalSetting(key: String): String? =
        runCatching { Settings.Global.getString(app.contentResolver, key) }.getOrNull()

    private fun byd(pm: PackageManager): List<PackageInfo> =
        pm.getInstalledPackages(0)
            .filter { p -> BYD_PACKAGE_HINTS.any { p.packageName.contains(it, ignoreCase = true) } }
            .sortedBy { it.packageName }

    private fun definedBydPermissions(pm: PackageManager): List<Pair<String, String>> =
        pm.getInstalledPackages(PackageManager.GET_PERMISSIONS)
            .flatMap { p -> p.permissions.orEmpty().map { it.name to p.packageName } }
            .filter { it.first.contains("BYD", ignoreCase = true) }
            .distinctBy { it.first }
            .sortedBy { it.first }

    private fun protection(info: PermissionInfo): String = when (info.protection) {
        PermissionInfo.PROTECTION_NORMAL -> "normal"
        PermissionInfo.PROTECTION_DANGEROUS -> "dangerous"
        PermissionInfo.PROTECTION_SIGNATURE -> "signature"
        else -> "level=${info.protection}"
    }

    private fun fmtResult(r: BydAcController.CommandResult) =
        "${if (r.success) "ok" else "FAIL"} via ${r.method} (${r.detail})"

    private fun unwrap(t: Throwable): Throwable = (t as? InvocationTargetException)?.cause ?: t

    private fun scanStream(input: InputStream, needles: List<ByteArray>, found: MutableSet<Int>) {
        val maxLen = needles.maxOf { it.size }
        val buf = ByteArray(SCAN_CHUNK + maxLen)
        var carry = 0
        while (found.size < needles.size) {
            val n = input.read(buf, carry, buf.size - carry)
            if (n <= 0) break
            val total = carry + n
            needles.forEachIndexed { i, needle ->
                if (i !in found && indexOf(buf, total, needle) >= 0) found += i
            }
            carry = minOf(maxLen - 1, total)
            System.arraycopy(buf, total - carry, buf, 0, carry)
        }
    }

    private fun indexOf(hay: ByteArray, len: Int, needle: ByteArray): Int {
        val first = needle[0]
        var i = 0
        while (i <= len - needle.size) {
            if (hay[i] == first) {
                var j = 1
                while (j < needle.size && hay[i + j] == needle[j]) j++
                if (j == needle.size) return i
            }
            i++
        }
        return -1
    }

    // endregion

    companion object {
        private const val ADB_TIMEOUT_MS = 8_000L
        private const val WRITE_SETTLE_MS = 1_200L
        private const val BRIDGE_REPLY_MS = 2_500L
        private const val LOGCAT_LINES = 600
        private const val LOGCAT_TIMEOUT_S = 5L
        private const val MAX_GETTERS = 120
        private const val MAX_BLE = 40
        private const val SCAN_CHUNK = 64 * 1024
        private val DEX_ENTRY = Regex("classes\\d*\\.dex")

        private val CORE_PERMISSIONS = listOf(
            "android.permission.WRITE_SECURE_SETTINGS",
            "android.permission.READ_LOGS",
        )

        private val OEM_PACKAGES = listOf("com.byd.carsettings", "com.byd.data.collect")
        private val BYD_PACKAGE_HINTS = listOf("byd", "dilink", "com.ts.")

        private val DEVICE_CLASSES = listOf(
            "AC" to "android.hardware.bydauto.ac.BYDAutoAcDevice",
            "Bodywork" to "android.hardware.bydauto.bodywork.BYDAutoBodyworkDevice",
            "Setting" to "android.hardware.bydauto.setting.BYDAutoSettingDevice",
            "Seat" to "android.hardware.bydauto.seat.BYDAutoSeatDevice",
            "Sensor" to "android.hardware.bydauto.sensor.BYDAutoSensorDevice",
            "Light" to "android.hardware.bydauto.light.BYDAutoLightDevice",
            "Statistic" to "android.hardware.bydauto.statistic.BYDAutoStatisticDevice",
            "Tyre" to "android.hardware.bydauto.tyre.BYDAutoTyreDevice",
            "Speed" to "android.hardware.bydauto.speed.BYDAutoSpeedDevice",
            "Gearbox" to "android.hardware.bydauto.gearbox.BYDAutoGearboxDevice",
            "Engine" to "android.hardware.bydauto.engine.BYDAutoEngineDevice",
            "Charging" to "android.hardware.bydauto.charging.BYDAutoChargingDevice",
            "Energy" to "android.hardware.bydauto.energy.BYDAutoEnergyDevice",
            "Instrument" to "android.hardware.bydauto.instrument.BYDAutoInstrumentDevice",
            "VehicleHealth" to "android.hardware.bydauto.vehiclehealth.BYDAutoVehicleHealthDevice",
            "CollectData" to "android.hardware.bydauto.collectdata.BYDAutoCollectDataDevice",
            "DiPilot" to "android.hardware.bydauto.dipilot.BYDAutoDiPilotDevice",
            "ADAS" to "android.hardware.bydauto.adas.BYDAutoADASDevice",
            "DMS" to "android.hardware.bydauto.dms.BYDAutoDmsDevice",
        )

        private val SCAN_CLASSES = listOf(
            "android.hardware.bydauto.ac.BYDAutoAcDevice",
            "android.hardware.bydauto.dipilot.BYDAutoDiPilotDevice",
            "android.hardware.bydauto.setting.BYDAutoSettingDevice",
            "android.hardware.bydauto.bodywork.BYDAutoBodyworkDevice",
        )

        private val SENTINELS = setOf(-1, -2147482645, -2147482646, -2147482647, -2147482648, 65535)
        private val DEVICE_CONSTANT_GETTERS = setOf("getType", "getDevicetype")

        private const val AC_SERVICE = "byd_airconditioning"
        private const val AC_JAR = "/system/framework/com.byd.ac.jar"
        private const val MAX_AC_JAR_METHODS = 400
        private val AC_CLASS_DESCRIPTOR = Regex("L(com/byd/ac/[A-Za-z0-9_/$]+);")
        private val READ_PREFIXES = listOf("get", "is", "has")
        private const val AC_APP_PACKAGE = "com.byd.acservice"
        private const val AC_APP_ACTION = "com.byd.ac.AC_SERVICE"
        private const val AC_ROOT_INTERFACE = "com.byd.ac.IBydAcService"
        private const val AC_BIND_TIMEOUT_MS = 4_000L
        private val AC_CONSTANT_CLASSES = listOf(
            "com.byd.ac.BydAcManager",
            "com.byd.ac.BydAcFeatures",
            "com.byd.ac.BydAcFeatures\$AirConditioner",
            "com.byd.ac.BydAcFeatures\$Seat",
            "com.byd.ac.PropertyIds",
            "com.byd.ac.PropertyIds\$AirConditioner",
            "com.byd.ac.PropertyIds\$AirOutlet",
            "com.byd.ac.PropertyIds\$AcSetting",
            "com.byd.ac.PropertyIds\$CarDialog",
        )
        private val AC_SUBSERVICE_GUESSES = listOf(
            "AirConditioner", "air_conditioner", "IAcAirConditioner", "AcAirConditioner",
            "AirClean", "AcSetting", "Fragrance", "SeatVentilationHeating",
        )

        private const val ADB_ID = "id"
        private const val ADB_HIDDEN_POLICY = "settings get global hidden_api_policy"
        private const val ADB_HIDDEN_EXEMPT = "settings get global hidden_api_blacklist_exemptions"
        private val ADB_COMMANDS = listOf(
            ADB_ID,
            ADB_HIDDEN_POLICY,
            ADB_HIDDEN_EXEMPT,
            "getprop | grep -iE 'byd|dilink|ro\\.product\\.|ro\\.build\\.(display|version|date)|ro\\.board|ro\\.hardware|vehicle' | head -n 150",
            "service list | grep -iE 'byd|auto|vehicle|car' | head -n 80",
            "ls -la /system/framework | grep -iE 'byd|auto'",
            "pm list libraries | grep -iE 'byd|auto'",
            "dumpsys package \$pkg | grep -iE 'BYDAUTO|SECURE_SETTINGS|READ_LOGS' | head -n 120",
            "appops get \$pkg",
            "dumpsys deviceidle whitelist | grep -i \$pkg",
            "dumpsys usb | grep -iE 'port|mode|role|connected|host' | head -n 60",
            "service check byd_airconditioning",
            "dumpsys byd_airconditioning 2>&1 | head -n 80",
            "dumpsys package com.byd.airconditioning | grep -iE 'userId|sharedUser|permission|Service' | head -n 80",
            "dumpsys package com.byd.acservice | grep -iE -A3 'Service Resolver|singleUser|exported|BIND_' | head -n 40",
            "dumpsys activity services com.byd.acservice | head -n 60",
        )
    }
}
