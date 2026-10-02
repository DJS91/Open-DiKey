package com.sphy.airconcontroller

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import com.sphy.airconcontroller.diagnostics.DiagnosticsRunner
import com.sphy.airconcontroller.storage.PublicDownloads
import com.sphy.airconcontroller.ui.OpenDiKeyActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One-tap diagnostics report for vehicles/head units the app does not work on yet. */
class DiagnosticsActivity : OpenDiKeyActivity() {
    private lateinit var statusText: TextView
    private lateinit var reportText: TextView
    private lateinit var actionButtons: List<Button>
    private val liveLines = ArrayDeque<String>()
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private var report: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_diagnostics)

        statusText = findViewById(R.id.diagStatusText)
        reportText = findViewById(R.id.diagReportText)
        val run = findViewById<Button>(R.id.diagRunButton)
        val writeTest = findViewById<Button>(R.id.diagWriteTestButton)
        actionButtons = listOf(run, writeTest)

        findViewById<android.widget.ImageButton>(R.id.diagBackButton).setOnClickListener { finish() }
        run.setOnClickListener { runDiagnostics(writeTest = false) }
        writeTest.setOnClickListener { confirmWriteTest() }
        findViewById<Button>(R.id.diagCopyButton).setOnClickListener { copyReport() }
        findViewById<Button>(R.id.diagShareButton).setOnClickListener { shareReport() }

        captureLiveDiKey()
    }

    private fun captureLiveDiKey() {
        val session = OpenDiKeyApp.from(this).dikey
        lifecycleScope.launch { session.status.collect { addLive("status  $it") } }
        lifecycleScope.launch { session.logs.collect { addLive("log     $it") } }
        lifecycleScope.launch { session.events.collect { addLive("event   $it") } }
    }

    private fun addLive(line: String) = synchronized(liveLines) {
        liveLines.addLast("${timeFormat.format(Date())} $line")
        while (liveLines.size > MAX_LIVE_LINES) liveLines.removeFirst()
    }

    private fun liveSnapshot(): List<String> = synchronized(liveLines) { liveLines.toList() }

    private fun confirmWriteTest() {
        AlertDialog.Builder(this)
            .setTitle(R.string.diag_write_test)
            .setMessage(R.string.diag_write_test_body)
            .setPositiveButton(R.string.diag_write_test_run) { _, _ -> runDiagnostics(writeTest = true) }
            .setNegativeButton(R.string.adas_auto_apply_cancel, null)
            .show()
    }

    private fun runDiagnostics(writeTest: Boolean) {
        actionButtons.forEach { it.isEnabled = false }
        lifecycleScope.launch {
            val runner = DiagnosticsRunner(this@DiagnosticsActivity, ::liveSnapshot)
            val progress: (String) -> Unit = { msg -> runOnUiThread { statusText.text = msg } }
            val text = withContext(Dispatchers.IO) {
                if (writeTest) runner.runClimateWriteTest(progress) else runner.run(progress)
            }
            report = text
            reportText.text = text
            val path = withContext(Dispatchers.IO) { persist(text) }
            copyToClipboard(text)
            statusText.text = getString(R.string.dump_saved, path)
            actionButtons.forEach { it.isEnabled = true }
        }
    }

    private fun persist(text: String): String {
        val file = File(getExternalFilesDir(null) ?: filesDir, REPORT_NAME)
        file.writeText(text)
        shareFile().writeText(text)
        val publicPath = PublicDownloads.saveText(this, REPORT_NAME, text)
        text.lineSequence().forEach { if (it.isNotEmpty()) Log.i(TAG, it.take(4000)) }
        return publicPath ?: file.absolutePath
    }

    private fun shareFile(): File =
        File(cacheDir, "diagnostics").apply { mkdirs() }.let { File(it, REPORT_NAME) }

    private fun copyToClipboard(text: String) {
        getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText("Open DiKey diagnostics", text))
    }

    private fun copyReport() {
        val text = report ?: return snack(R.string.diag_run_first)
        copyToClipboard(text)
        snack(R.string.diag_copied)
    }

    private fun shareReport() {
        if (report == null) return snack(R.string.diag_run_first)
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", shareFile())
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Open DiKey diagnostics")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(Intent.createChooser(send, getString(R.string.diag_share)))
        } catch (_: ActivityNotFoundException) {
            snack(R.string.diag_share_unavailable)
        }
    }

    private fun snack(res: Int) {
        Snackbar.make(reportText, res, Snackbar.LENGTH_LONG).show()
    }

    companion object {
        private const val TAG = "OpenDiKeyDiag"
        private const val REPORT_NAME = "open-dikey-diagnostics.txt"
        private const val MAX_LIVE_LINES = 400
    }
}
