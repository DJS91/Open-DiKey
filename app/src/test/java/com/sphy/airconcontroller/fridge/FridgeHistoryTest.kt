package com.sphy.airconcontroller.fridge

import org.junit.Assert.*
import org.junit.Test

class FridgeHistoryTest {
    private class MemoryStorage(initial: List<String> = emptyList()) : FridgeHistory.Storage {
        val lines = initial.toMutableList()
        var rewrites = 0
        override fun readLines() = lines.toList()
        override fun append(line: String) { lines.add(line) }
        override fun rewrite(lines: List<String>) {
            rewrites++
            this.lines.clear()
            this.lines.addAll(lines)
        }
    }

    private fun status(left: Int, right: Int = -18, celsius: Boolean = true) =
        EvakoolProtocol.Status(true, celsius, left, right, 4, -20, "12.6")

    @Test fun samplesAtMostOncePerInterval() {
        val storage = MemoryStorage()
        val history = FridgeHistory(storage)
        assertTrue(history.record(status(5), 1_000_000))
        assertFalse(history.record(status(6), 1_030_000))
        assertTrue(history.record(status(7), 1_060_000))
        assertEquals(listOf(5, 7), history.samples.map { it.left })
        assertEquals("1000000,5,-18,4,-20,C", storage.lines.first())
    }

    @Test fun keepsOnlyOneWeekAndReloadsFromStorage() {
        val storage = MemoryStorage()
        val history = FridgeHistory(storage)
        val start = 10 * FridgeHistory.DAY_MS
        history.record(status(1), start)
        history.record(status(2), start + FridgeHistory.DAY_MS)
        history.record(status(3), start + FridgeHistory.WEEK_MS + 60_000)
        assertEquals(listOf(2, 3), history.samples.map { it.left })

        val reloaded = FridgeHistory(MemoryStorage(storage.lines + "garbage" + "1,2,3"))
        assertEquals(listOf(2, 3), reloaded.samples.map { it.left })
    }

    @Test fun rangeSelectionAndClear() {
        val storage = MemoryStorage()
        val history = FridgeHistory(storage)
        val now = 100 * FridgeHistory.DAY_MS
        history.record(status(1), now - 2 * FridgeHistory.DAY_MS)
        history.record(status(2), now - 2 * FridgeHistory.HOUR_MS)
        history.record(status(3), now - 10 * 60_000)
        assertEquals(listOf(3), history.since(FridgeHistory.HOUR_MS, now).map { it.left })
        assertEquals(listOf(2, 3), history.since(FridgeHistory.DAY_MS, now).map { it.left })
        assertEquals(3, history.since(FridgeHistory.WEEK_MS, now).size)
        val v = history.version
        history.clear()
        assertTrue(history.version > v)
        assertTrue(history.samples.isEmpty())
        assertTrue(storage.lines.isEmpty())
    }

    @Test fun storageFailureDoesNotBreakRecording() {
        val history = FridgeHistory(object : FridgeHistory.Storage {
            override fun readLines(): List<String> = throw java.io.IOException("no disk")
            override fun append(line: String) = throw java.io.IOException("no disk")
            override fun rewrite(lines: List<String>) = throw java.io.IOException("no disk")
        })
        assertTrue(history.record(status(4), 1_000))
        assertEquals(1, history.samples.size)
    }

    @Test fun encodesFahrenheitAndConverts() {
        val s = FridgeHistory.Sample(1, 39, 0, 37, -4, false)
        assertEquals(s, FridgeHistory.decode(FridgeHistory.encode(s)))
        assertEquals(0f, FridgeHistory.toCelsius(32, false), 0.01f)
        assertEquals(39.2f, FridgeHistory.toFahrenheit(4, true), 0.01f)
    }
}
