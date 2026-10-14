package com.sphy.airconcontroller.apps

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.drawable.Drawable
import java.util.Locale

/**
 * Finds the head unit's CarPlay / Android Auto app. The car only lists these among its launchable
 * apps while a phone is connected, so "is it listed?" doubles as "is a phone projecting?" — see
 * [find]. Used by the home screen's projection shortcut.
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
     * The projection app currently listed as launchable, or null if none is. Disabled components are
     * not returned by the default query, so an app the car has switched off does not count.
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
