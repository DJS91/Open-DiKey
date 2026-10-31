package com.sphy.airconcontroller.fridge

import java.io.File

/**
 * Rolling one-week temperature history, sampled at most once per [intervalMs].
 * Persisted as CSV lines: epochMs,left,right,leftTarget,rightTarget,C|F.
 */
class FridgeHistory(
    private val storage: Storage,
    private val intervalMs: Long = 60_000,
) {
    data class Sample(
        val time: Long,
        val left: Int,
        val right: Int,
        val leftTarget: Int,
        val rightTarget: Int,
        val celsius: Boolean,
    )

    interface Storage {
        fun readLines(): List<String>
        fun append(line: String)
        fun rewrite(lines: List<String>)
    }

    class FileStorage(private val file: File) : Storage {
        override fun readLines(): List<String> = if (file.exists()) file.readLines() else emptyList()
        override fun append(line: String) = file.appendText(line + "\n")
        override fun rewrite(lines: List<String>) {
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(lines.joinToString("") { it + "\n" })
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        }
    }

    private val items = ArrayList<Sample>()
    private var linesOnDisk = 0
    private var loaded = false
    /** Incremented whenever samples change, so views can skip redundant redraws. */
    var version = 0
        private set
    val samples: List<Sample> get() { ensureLoaded(); return items }

    /** Records [status] if the last sample is at least [intervalMs] old. Returns true if stored. */
    fun record(status: EvakoolProtocol.Status, now: Long): Boolean {
        ensureLoaded()
        val last = items.lastOrNull()
        if (last != null && now - last.time in 0 until intervalMs) return false
        val sample = Sample(now, status.left, status.right, status.leftTarget, status.rightTarget, status.celsius)
        items.add(sample)
        prune(now)
        try {
            storage.append(encode(sample))
            linesOnDisk++
            if (linesOnDisk > items.size + COMPACT_SLACK) compact()
        } catch (_: Exception) {
            // History is best-effort; live readings must keep working if storage fails.
        }
        version++
        return true
    }

    /** Samples newer than [now] - [rangeMs], oldest first. */
    fun since(rangeMs: Long, now: Long): List<Sample> {
        ensureLoaded()
        val from = now - rangeMs
        val start = items.indexOfFirst { it.time >= from }.let { if (it < 0) items.size else it }
        return items.subList(start, items.size).filter { it.time <= now }
    }

    fun clear() {
        items.clear()
        loaded = true
        try { storage.rewrite(emptyList()) } catch (_: Exception) {}
        linesOnDisk = 0
        version++
    }

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        val lines = try { storage.readLines() } catch (_: Exception) { emptyList() }
        linesOnDisk = lines.size
        lines.mapNotNullTo(items, ::decode)
        items.sortBy { it.time }
        val newest = items.lastOrNull()?.time ?: return
        prune(newest)
        if (linesOnDisk != items.size) compact()
    }

    private fun prune(now: Long) {
        val cutoff = now - RETENTION_MS
        val drop = items.indexOfFirst { it.time >= cutoff }.let { if (it < 0) items.size else it }
        if (drop > 0) items.subList(0, drop).clear()
    }

    private fun compact() {
        try {
            storage.rewrite(items.map(::encode))
            linesOnDisk = items.size
        } catch (_: Exception) {}
    }

    companion object {
        const val HOUR_MS = 3_600_000L
        const val DAY_MS = 24 * HOUR_MS
        const val WEEK_MS = 7 * DAY_MS
        const val RETENTION_MS = WEEK_MS
        /** Gaps longer than this are drawn as breaks in the graph line. */
        const val GAP_MS = 3 * 60_000L
        private const val COMPACT_SLACK = 1_500

        fun encode(s: Sample) =
            "${s.time},${s.left},${s.right},${s.leftTarget},${s.rightTarget},${if (s.celsius) "C" else "F"}"

        fun decode(line: String): Sample? {
            val p = line.trim().split(',')
            if (p.size != 6 || (p[5] != "C" && p[5] != "F")) return null
            return Sample(
                p[0].toLongOrNull() ?: return null,
                p[1].toIntOrNull() ?: return null,
                p[2].toIntOrNull() ?: return null,
                p[3].toIntOrNull() ?: return null,
                p[4].toIntOrNull() ?: return null,
                p[5] == "C",
            )
        }

        fun toCelsius(value: Int, celsius: Boolean): Float = if (celsius) value.toFloat() else (value - 32) * 5f / 9f
        fun toFahrenheit(value: Int, celsius: Boolean): Float = if (celsius) value * 9f / 5f + 32 else value.toFloat()
    }
}
