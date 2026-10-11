package com.sphy.airconcontroller

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.snackbar.Snackbar
import com.sphy.airconcontroller.boot.AdasAutoApplyLog
import com.sphy.airconcontroller.ui.OpenDiKeyActivity
import kotlinx.coroutines.launch
import java.io.File

/**
 * Shows the persisted [AdasAutoApplyLog] (target vs. actual ADAS values for every boot auto-apply
 * attempt) live, so a cold-boot auto-apply that's still retrying in the background can be watched
 * from inside the app as it happens, not just reconstructed afterwards from a static dump.
 */
class AdasAutoApplyLogActivity : OpenDiKeyActivity() {
    private lateinit var logText: TextView
    private lateinit var logScroll: ScrollView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_adas_auto_apply_log)

        logText = findViewById(R.id.adasLogText)
        logScroll = findViewById(R.id.adasLogScroll)

        findViewById<android.widget.ImageButton>(R.id.adasLogBackButton).setOnClickListener { finish() }
        findViewById<Button>(R.id.adasLogCopyButton).setOnClickListener { copyLog() }
        findViewById<Button>(R.id.adasLogShareButton).setOnClickListener { shareLog() }
        findViewById<Button>(R.id.adasLogClearButton).setOnClickListener { clearLog() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                AdasAutoApplyLog.textFlow(this@AdasAutoApplyLogActivity).collect { text ->
                    logText.text = text.ifEmpty { getString(R.string.adas_log_empty) }
                    logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
                }
            }
        }
    }

    private fun currentText(): String = AdasAutoApplyLog.text.value

    private fun copyLog() {
        getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText("Open DiKey ADAS auto-apply log", currentText()))
        Snackbar.make(logText, R.string.adas_log_copied, Snackbar.LENGTH_LONG).show()
    }

    private fun shareLog() {
        val file = File(cacheDir, "adas_auto_apply_log").apply { mkdirs() }
            .let { File(it, "adas-auto-apply-log.txt") }
        file.writeText(currentText())
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Open DiKey ADAS auto-apply log")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(Intent.createChooser(send, getString(R.string.diag_share)))
        } catch (_: ActivityNotFoundException) {
            Snackbar.make(logText, R.string.diag_share_unavailable, Snackbar.LENGTH_LONG).show()
        }
    }

    private fun clearLog() {
        AdasAutoApplyLog.clear(this)
        Snackbar.make(logText, R.string.adas_log_cleared, Snackbar.LENGTH_LONG).show()
    }
}
