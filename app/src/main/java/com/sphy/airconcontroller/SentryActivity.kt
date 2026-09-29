package com.sphy.airconcontroller

import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.lifecycle.lifecycleScope
import com.sphy.airconcontroller.adb.AdbPermissionManager
import com.sphy.airconcontroller.ui.OpenDiKeyActivity
import com.sphy.airconcontroller.update.AppUpdater
import com.sphy.airconcontroller.update.CompanionAppInstaller
import com.sphy.airconcontroller.update.CompanionApps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Recording apps page: Overdrive on the left, Strike on the right. Install when missing, open when present. */
class SentryActivity : OpenDiKeyActivity() {
    private lateinit var panels: List<CompanionPanel>

    private val installLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            panels.forEach {
                it.refresh()
                it.watchForPackageChange()
            }
        }

    private val packageChangeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val pkg = intent?.data?.schemeSpecificPart ?: return
            panels.filter { it.installer.packageName == pkg }.forEach { it.refresh() }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sentry)

        panels = listOf(
            CompanionPanel(
                root = findViewById(R.id.overdrivePanel),
                installer = CompanionApps.overdrive,
                fallbackIcon = R.drawable.ic_sentry,
                badge = R.string.overdrive_badge,
                badgeColor = R.color.tyre_ok,
                badgeBackground = R.color.accent_green_soft,
                installTitle = R.string.overdrive_install_title,
                installedTitle = R.string.overdrive_title,
                installBody = R.string.overdrive_install_body,
                installedBody = R.string.overdrive_installed_body,
                caveatsTitle = R.string.overdrive_caveats_title,
                caveats = R.string.overdrive_caveats,
                openButtonText = R.string.overdrive_open_button,
                openFailed = R.string.overdrive_open_failed,
            ),
            CompanionPanel(
                root = findViewById(R.id.strikePanel),
                installer = CompanionApps.strike,
                fallbackIcon = R.drawable.ic_strike,
                badge = R.string.strike_badge,
                badgeColor = R.color.accent_orange,
                badgeBackground = R.color.accent_orange_soft,
                installTitle = R.string.strike_install_title,
                installedTitle = R.string.strike_title,
                installBody = R.string.strike_install_body,
                installedBody = R.string.strike_installed_body,
                openButtonText = R.string.strike_open_button,
                openFailed = R.string.strike_open_failed,
            ),
        )

        findViewById<android.widget.ImageButton>(R.id.sentryBackButton).setOnClickListener {
            finish()
        }
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addDataScheme("package")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(packageChangeReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(packageChangeReceiver, filter)
        }
        panels.forEach { it.refresh() }
    }

    override fun onResume() {
        super.onResume()
        panels.forEach { it.refresh() }
    }

    override fun onStop() {
        panels.forEach { it.cancelWatch() }
        try {
            unregisterReceiver(packageChangeReceiver)
        } catch (_: IllegalArgumentException) {
            // Already unregistered.
        }
        super.onStop()
    }

    private suspend fun ensureInstallAllowed(
        onBlocked: (String) -> Unit,
        onWarning: (String) -> Unit,
    ): Boolean {
        if (AppUpdater.canInstallPackages(this)) return true

        val pkg = packageName
        AdbPermissionManager.runShellBatch(
            this,
            listOf(
                "appops set $pkg REQUEST_INSTALL_PACKAGES allow",
                "cmd appops set $pkg REQUEST_INSTALL_PACKAGES allow",
            ),
        )
        if (AppUpdater.canInstallPackages(this)) return true

        val opened = withContext(Dispatchers.Main) {
            AppUpdater.openInstallPermissionSettings(this@SentryActivity)
        }
        withContext(Dispatchers.Main) {
            if (opened) {
                onBlocked(getString(R.string.settings_updates_need_permission))
            } else {
                onWarning(getString(R.string.settings_updates_install_anyway))
            }
        }
        return !opened
    }

    private inner class CompanionPanel(
        root: View,
        val installer: CompanionAppInstaller,
        @DrawableRes private val fallbackIcon: Int,
        @StringRes badge: Int,
        @ColorRes badgeColor: Int,
        @ColorRes badgeBackground: Int,
        @StringRes private val installTitle: Int,
        @StringRes private val installedTitle: Int,
        @StringRes private val installBody: Int,
        @StringRes private val installedBody: Int,
        @StringRes caveatsTitle: Int? = null,
        @StringRes caveats: Int? = null,
        @StringRes openButtonText: Int,
        @StringRes private val openFailed: Int,
    ) {
        private val icon: ImageView = root.findViewById(R.id.companionIcon)
        private val headline: TextView = root.findViewById(R.id.companionHeadline)
        private val body: TextView = root.findViewById(R.id.companionBody)
        private val statusText: TextView = root.findViewById(R.id.companionStatus)
        private val progress: ProgressBar = root.findViewById(R.id.companionProgress)
        private val openButton: Button = root.findViewById(R.id.companionOpenButton)
        private val installButton: Button = root.findViewById(R.id.companionInstallButton)
        private var busy = false
        private var watchJob: Job? = null

        init {
            root.findViewById<TextView>(R.id.companionBadge).apply {
                setText(badge)
                setTextColor(getColor(badgeColor))
                backgroundTintList = ColorStateList.valueOf(getColor(badgeBackground))
            }
            val caveatsTitleView = root.findViewById<TextView>(R.id.companionCaveatsTitle)
            val caveatsView = root.findViewById<TextView>(R.id.companionCaveats)
            if (caveatsTitle != null && caveats != null) {
                caveatsTitleView.setText(caveatsTitle)
                caveatsView.setText(caveats)
            } else {
                caveatsTitleView.visibility = View.GONE
                caveatsView.visibility = View.GONE
            }
            openButton.setText(openButtonText)
            openButton.setOnClickListener { open() }
            installButton.setOnClickListener { downloadAndInstall() }
        }

        fun refresh() {
            val installed = installer.isInstalled(this@SentryActivity)
            openButton.visibility = if (installed) View.VISIBLE else View.GONE
            val appIcon = if (installed) installer.appIcon(this@SentryActivity) else null
            if (appIcon != null) icon.setImageDrawable(appIcon) else icon.setImageResource(fallbackIcon)
            if (installed) {
                headline.setText(installedTitle)
                body.setText(installedBody)
                installButton.setText(R.string.sentry_reinstall_button)
                if (!busy) {
                    val version = installer.installedVersionName(this@SentryActivity)
                    statusText.text = if (version != null) {
                        getString(R.string.companion_installed_status, version)
                    } else {
                        getString(R.string.sentry_installed_status)
                    }
                }
            } else {
                headline.setText(installTitle)
                body.setText(installBody)
                installButton.setText(R.string.sentry_install_button)
                if (!busy) {
                    statusText.setText(R.string.sentry_install_idle)
                }
            }
        }

        /** PackageManager can lag briefly after the installer returns. */
        fun watchForPackageChange() {
            watchJob?.cancel()
            watchJob = lifecycleScope.launch {
                repeat(8) {
                    delay(750)
                    refresh()
                    if (installer.isInstalled(this@SentryActivity)) return@launch
                }
            }
        }

        fun cancelWatch() {
            watchJob?.cancel()
            watchJob = null
        }

        private fun open() {
            val launch = installer.launchIntent(this@SentryActivity)
            if (launch == null) {
                Toast.makeText(this@SentryActivity, openFailed, Toast.LENGTH_SHORT).show()
                refresh()
                return
            }
            try {
                startActivity(launch)
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(this@SentryActivity, openFailed, Toast.LENGTH_SHORT).show()
                refresh()
            }
        }

        private fun downloadAndInstall() {
            if (busy) return
            setBusy(true, getString(R.string.settings_updates_checking))
            progress.visibility = View.VISIBLE
            progress.isIndeterminate = true
            progress.progress = 0

            lifecycleScope.launch {
                try {
                    val allowed = ensureInstallAllowed(
                        onBlocked = { setBusy(false, it) },
                        onWarning = { statusText.text = it },
                    )
                    if (!allowed) return@launch
                    val release = installer.fetchLatestRelease()
                    withContext(Dispatchers.Main) {
                        statusText.text = getString(
                            R.string.sentry_install_found,
                            release.versionName,
                        )
                    }
                    val dest = installer.cacheFile(this@SentryActivity)
                    AppUpdater.downloadApk(release.apkUrl, dest) { downloaded, total ->
                        lifecycleScope.launch(Dispatchers.Main) {
                            if (total > 0L) {
                                progress.isIndeterminate = false
                                val pct = ((downloaded * 100L) / total).toInt().coerceIn(0, 100)
                                progress.progress = pct
                                statusText.text = getString(R.string.settings_updates_downloading, pct)
                            } else {
                                progress.isIndeterminate = true
                                statusText.text = getString(
                                    R.string.settings_updates_downloading_bytes,
                                    downloaded / 1024L,
                                )
                            }
                        }
                    }
                    withContext(Dispatchers.Main) {
                        setBusy(false, getString(R.string.settings_updates_installing))
                        try {
                            installLauncher.launch(
                                AppUpdater.installApkIntent(this@SentryActivity, dest),
                            )
                            watchForPackageChange()
                        } catch (e: ActivityNotFoundException) {
                            setBusy(
                                false,
                                getString(
                                    R.string.settings_updates_failed,
                                    e.message ?: e.javaClass.simpleName,
                                ),
                            )
                        }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        setBusy(
                            false,
                            getString(
                                R.string.settings_updates_failed,
                                e.message ?: e.javaClass.simpleName,
                            ),
                        )
                        Toast.makeText(
                            this@SentryActivity,
                            R.string.settings_updates_signature_hint,
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                }
            }
        }

        private fun setBusy(value: Boolean, status: String) {
            busy = value
            installButton.isEnabled = !value
            openButton.isEnabled = !value
            statusText.text = status
            if (!value) progress.visibility = View.GONE
        }
    }
}
