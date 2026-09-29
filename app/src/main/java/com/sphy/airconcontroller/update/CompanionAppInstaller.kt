package com.sphy.airconcontroller.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Installs / launches a third-party companion app distributed as an APK on GitHub releases.
 *
 * When [releaseTag] is set, only that release is used (e.g. a rolling channel tag like
 * `braveheart`); otherwise the newest non-draft release with an APK asset wins.
 */
class CompanionAppInstaller(
    val packageName: String,
    private val repo: String,
    private val cacheName: String,
    private val apkNameHints: List<String>,
    private val releaseTag: String? = null,
) {
    fun isInstalled(context: Context): Boolean {
        val pm = context.packageManager
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(packageName, 0)
            }
            return true
        } catch (_: PackageManager.NameNotFoundException) {
            // Fall through — some DiLink builds report NameNotFound until launchers refresh.
        }
        return pm.getLaunchIntentForPackage(packageName) != null
    }

    fun installedVersionName(context: Context): String? =
        try {
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    packageName,
                    PackageManager.PackageInfoFlags.of(0),
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(packageName, 0)
            }
            info.versionName
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }

    fun appIcon(context: Context): Drawable? =
        try {
            context.packageManager.getApplicationIcon(packageName)
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }

    fun launchIntent(context: Context): Intent? =
        context.packageManager.getLaunchIntentForPackage(packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    fun cacheFile(context: Context): File =
        File(File(context.cacheDir, "updates"), cacheName)

    suspend fun fetchLatestRelease(): AppUpdater.LatestRelease = withContext(Dispatchers.IO) {
        val endpoint = if (releaseTag != null) {
            "https://api.github.com/repos/$repo/releases/tags/$releaseTag"
        } else {
            "https://api.github.com/repos/$repo/releases?per_page=15"
        }
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 15_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        }
        try {
            if (connection.responseCode != 200) {
                val err = connection.errorStream?.bufferedReader()?.readText().orEmpty()
                error("GitHub API HTTP ${connection.responseCode}: ${err.take(200)}")
            }
            val text = connection.inputStream.bufferedReader().readText()
            val releases = if (releaseTag != null) {
                listOf(JSONObject(text))
            } else {
                val array = JSONArray(text)
                (0 until array.length()).map { array.getJSONObject(it) }
            }
            for (json in releases) {
                if (json.optBoolean("draft", false)) continue
                val (apkName, apkUrl) = pickApkAsset(json.getJSONArray("assets")) ?: continue
                return@withContext AppUpdater.LatestRelease(
                    tag = json.getString("tag_name"),
                    versionName = versionFrom(apkName) ?: AppUpdater.normalizeVersion(
                        json.getString("tag_name"),
                    ),
                    apkUrl = apkUrl,
                    publishedAt = json.optString("published_at").ifBlank { null },
                )
            }
            error("No APK assets found in $repo releases")
        } finally {
            connection.disconnect()
        }
    }

    /** Prefer an asset whose name matches [apkNameHints]; fall back to any `.apk`. */
    private fun pickApkAsset(assets: JSONArray): Pair<String, String>? {
        var fallback: Pair<String, String>? = null
        for (i in 0 until assets.length()) {
            val asset = assets.getJSONObject(i)
            val name = asset.getString("name")
            if (!name.endsWith(".apk", ignoreCase = true)) continue
            val entry = name to asset.getString("browser_download_url")
            val lower = name.lowercase()
            if (apkNameHints.any { it in lower }) return entry
            if (fallback == null) fallback = entry
        }
        return fallback
    }

    /** Rolling tags (e.g. `braveheart`) carry no version; the asset name usually does. */
    private fun versionFrom(apkName: String): String? =
        VERSION_IN_NAME.find(apkName)?.groupValues?.get(1)

    private companion object {
        const val USER_AGENT = "OpenDiKey-Companion"
        val VERSION_IN_NAME = Regex("""[vV](\d+(?:\.\d+)+)""")
    }
}

object CompanionApps {
    /** Strike — [https://github.com/sp-hy/Strike/releases]. */
    val strike = CompanionAppInstaller(
        packageName = "com.strike",
        repo = "sp-hy/Strike",
        cacheName = "sentry.apk",
        apkNameHints = listOf("strike", "sentry", "release", "arm64"),
    )

    /** Overdrive, Braveheart channel — [https://github.com/yash-srivastava/Overdrive-release/releases]. */
    val overdrive = CompanionAppInstaller(
        packageName = "com.overdrive.app",
        repo = "yash-srivastava/Overdrive-release",
        cacheName = "overdrive.apk",
        apkNameHints = listOf("braveheart"),
        releaseTag = "braveheart",
    )
}
