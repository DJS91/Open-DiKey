package com.sphy.airconcontroller.boot

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Append-only debug log for [AdasAutoApplyCoordinator], viewable live from the Debug Hub.
 *
 * Cold-boot auto-apply attempts often run entirely before anyone opens the app — the process can
 * be spun up early by [KeepAliveNotificationListener] — and it's exactly that run that needs
 * explaining when the apply was inconsistent. Logcat alone isn't reliable for this after the
 * fact (it's rotated by the OS, and the process that ran the attempt may already be gone by the
 * time you go looking), so this buffers lines in memory and also mirrors them to a small capped
 * file under the app's private storage, so the history survives the frequent process restarts
 * described in [KeepAliveNotificationListener]. [text] is a hot [StateFlow] so a viewer collecting
 * it updates in real time while an attempt already in progress keeps appending to it.
 */
object AdasAutoApplyLog {
    private const val TAG = "AdasAutoApply"
    private const val FILE_NAME = "adas_auto_apply_log.txt"
    private const val MAX_LINES = 2000

    private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val lines = ArrayDeque<String>()
    @Volatile private var loadedFromDisk = false

    private val _text = MutableStateFlow("")

    /** Full buffered log text, oldest first; updates live as new lines are appended. */
    val text: StateFlow<String> = _text.asStateFlow()

    private fun file(context: Context): File =
        File(context.applicationContext.filesDir, FILE_NAME)

    /** Loads any lines persisted by a previous process, once per process lifetime. */
    private fun ensureLoaded(context: Context) {
        if (loadedFromDisk) return
        synchronized(lines) {
            if (loadedFromDisk) return
            runCatching { file(context).takeIf { it.exists() }?.readLines() }
                .getOrNull()
                ?.forEach { lines.addLast(it) }
            trimLocked()
            loadedFromDisk = true
            _text.value = lines.joinToString("\n")
        }
    }

    /** Appends a timestamped line to Logcat, the in-memory buffer, the on-disk mirror, and [text]. */
    fun append(context: Context, line: String) {
        ensureLoaded(context)
        Log.i(TAG, line)
        val stamped = "${timeFormat.format(Date())}  $line"
        val snapshot: List<String>
        synchronized(lines) {
            lines.addLast(stamped)
            trimLocked()
            snapshot = lines.toList()
        }
        _text.value = snapshot.joinToString("\n")
        // Rewrite (not append) so the on-disk file stays capped at MAX_LINES too.
        runCatching { file(context).writeText(_text.value) }
    }

    private fun trimLocked() {
        while (lines.size > MAX_LINES) lines.removeFirst()
    }

    /**
     * Ensures any lines persisted by a previous process are loaded, then returns [text] for live
     * collection. Call this (rather than reading [text] directly) before observing from a UI.
     */
    fun textFlow(context: Context): StateFlow<String> {
        ensureLoaded(context)
        return text
    }

    fun clear(context: Context) {
        synchronized(lines) { lines.clear() }
        loadedFromDisk = true
        _text.value = ""
        runCatching { file(context).delete() }
    }
}
