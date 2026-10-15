package com.sphy.airconcontroller.apps

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.drawable.Drawable
import com.sphy.airconcontroller.adb.AdbPermissionManager
import java.util.Locale

/**
 * Finds the head unit's CarPlay / Android Auto app and tells whether a phone is projecting. The apps are
 * always installed and listed, so [find] only locates the app to launch; whether a session is active comes
 * from [isPhoneProjecting]. Used by the home screen's projection shortcut.
 */
object ProjectionApps {
    /** A projection app that is currently listed, ready to be launched. */
    data class Entry(
        val packageName: String,
        val label: String,
        val icon: Drawable?,
        val launchIntent: Intent,
    )

    /** BYD's CarPlay and Android Auto packages (from Run Diagnostics → OEM packages). Checked first. */
    val KNOWN_PACKAGES = listOf("com.ts.carplay.app", "com.ts.androidauto.app")

    private val NAME_HINTS = listOf("carplay", "android auto", "androidauto")

    /**
     * The projection app to launch, or null if none is installed and enabled. This does not say whether a
     * phone is connected: the car lists these apps all the time (see [isPhoneProjecting]).
     */
    fun find(context: Context): Entry? {
        val pm = context.packageManager
        val candidates = launcherActivities(pm, 0)
            .filter { it.activityInfo.packageName != context.packageName }
            .filter { matches(it.activityInfo.packageName, it.loadLabel(pm).toString()) }
        val best = candidates.minByOrNull { ri ->
            KNOWN_PACKAGES.indexOf(ri.activityInfo.packageName).let { if (it < 0) KNOWN_PACKAGES.size else it }
        } ?: return null
        val component = ComponentName(best.activityInfo.packageName, best.activityInfo.name)
        return Entry(
            packageName = best.activityInfo.packageName,
            label = best.loadLabel(pm).toString(),
            icon = best.loadIcon(pm),
            // NEW_TASK on the app's own launcher activity brings its existing task to the front.
            launchIntent = Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED),
        )
    }

    /**
     * Whether a phone is currently projecting (CarPlay / Android Auto), or null if that can't be told.
     *
     * The projection apps are always installed and running, so "is the app there" says nothing. Comparing
     * Run Diagnostics reports taken with and without CarPlay showed one difference: while a phone projects,
     * the car switches off the Bluetooth audio (A2DP sink) and hands-free (HFP client) profiles for that
     * paired phone, and switches them back on afterwards. So a bonded phone with both forbidden (0) means a
     * session is active. Read with `dumpsys` over local ADB, since the Bluetooth API for it is privileged.
     *
     * Caveat: a phone whose profiles you disabled by hand would also look "connected".
     */
    fun isPhoneProjecting(context: Context): Boolean? {
        val result = AdbPermissionManager.runShellCommandQuick(context, POLICY_COMMAND)
        // exit 1 with no output is grep's "no match" (no bonded phone), not a failure.
        if (result.exitCode != 0 && result.output.isNotBlank()) return null
        if (result.exitCode == -1) return null
        return projectingFromPolicyLines(result.output)
    }

    /** Pure parsing half of [isPhoneProjecting], split out so it can be unit tested. */
    fun projectingFromPolicyLines(output: String): Boolean =
        output.lineSequence().any { line ->
            val sink = POLICY_A2DP_SINK.find(line)?.groupValues?.get(1)?.toIntOrNull()
            val hfp = POLICY_HEADSET_CLIENT.find(line)?.groupValues?.get(1)?.toIntOrNull()
            sink == POLICY_FORBIDDEN && hfp == POLICY_FORBIDDEN
        }

    private const val POLICY_COMMAND = "dumpsys bluetooth_manager | grep -F 'profile connection policy'"
    private const val POLICY_FORBIDDEN = 0
    private val POLICY_A2DP_SINK = Regex("""A2DP_SINK=(-?\d+)""")
    private val POLICY_HEADSET_CLIENT = Regex("""HEADSET_CLIENT=(-?\d+)""")

    /** For Run Diagnostics: every matching launcher entry, including disabled ones, and whether it is listed. */
    fun report(context: Context): List<String> {
        val pm = context.packageManager
        val listed = launcherActivities(pm, 0)
            .map { it.activityInfo.packageName + "/" + it.activityInfo.name }
            .toSet()
        val all = launcherActivities(pm, PackageManager.MATCH_DISABLED_COMPONENTS)
            .filter { matches(it.activityInfo.packageName, it.loadLabel(pm).toString()) }
        if (all.isEmpty()) return listOf("No CarPlay / Android Auto launcher entries found at all")
        return all.map { ri ->
            val id = ri.activityInfo.packageName + "/" + ri.activityInfo.name
            "${ri.loadLabel(pm)} · $id · listed=${id in listed}"
        }
    }

    private fun launcherActivities(pm: PackageManager, flags: Int): List<ResolveInfo> {
        val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(query, flags)
    }

    private fun matches(packageName: String, label: String): Boolean {
        if (packageName in KNOWN_PACKAGES) return true
        val lower = label.lowercase(Locale.ROOT)
        return NAME_HINTS.any { lower.contains(it) }
    }
}
