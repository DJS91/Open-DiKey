package com.sphy.airconcontroller.apps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ProjectionAutoSwitchTest {
    @Before
    fun setUp() = ProjectionAutoSwitch.reset()

    @Test
    fun startsOnlyWhenBothConditionsHold() {
        assertFalse(ProjectionAutoSwitch.shouldStart(projecting = true, adasApplied = false))
        assertFalse(ProjectionAutoSwitch.shouldStart(projecting = false, adasApplied = true))
        assertFalse(ProjectionAutoSwitch.shouldStart(projecting = false, adasApplied = false))
        assertTrue(ProjectionAutoSwitch.shouldStart(projecting = true, adasApplied = true))
    }

    @Test
    fun startsOnceNotOnEveryCheck() {
        assertTrue(ProjectionAutoSwitch.shouldStart(true, true))
        // Still connected and applied on the following checks: don't start again (e.g. after the user leaves CarPlay).
        repeat(5) { assertFalse(ProjectionAutoSwitch.shouldStart(true, true)) }
    }

    @Test
    fun rearmsAfterThePhoneDisconnects() {
        assertTrue(ProjectionAutoSwitch.shouldStart(true, true))
        assertFalse(ProjectionAutoSwitch.shouldStart(false, true))
        assertTrue(ProjectionAutoSwitch.shouldStart(true, true))
    }

    @Test
    fun rearmsAfterAdasStopsBeingApplied() {
        assertTrue(ProjectionAutoSwitch.shouldStart(true, true))
        assertFalse(ProjectionAutoSwitch.shouldStart(true, false))
        assertTrue(ProjectionAutoSwitch.shouldStart(true, true))
    }

    @Test
    fun anUnknownReadingNeitherStartsNorRearms() {
        assertTrue(ProjectionAutoSwitch.shouldStart(true, true))
        assertFalse(ProjectionAutoSwitch.shouldStart(null, true))
        // The unknown reading must not have re-armed it.
        assertFalse(ProjectionAutoSwitch.shouldStart(true, true))
    }

    @Test
    fun anUnknownReadingDoesNotStartEvenIfAdasIsApplied() {
        assertFalse(ProjectionAutoSwitch.shouldStart(null, true))
    }

    @Test
    fun abortsOnlyWhenAConditionDefinitelyStoppedHolding() {
        assertFalse(ProjectionAutoSwitch.shouldAbort(projecting = true, adasApplied = true))
        assertFalse(ProjectionAutoSwitch.shouldAbort(projecting = null, adasApplied = true))
        assertTrue(ProjectionAutoSwitch.shouldAbort(projecting = false, adasApplied = true))
        assertTrue(ProjectionAutoSwitch.shouldAbort(projecting = true, adasApplied = false))
    }

    @Test
    fun countdownTextBuildsUpToThreeTwoOne() {
        val total = 3
        val frames = (total downTo 1).map { second -> (total downTo second).joinToString("...") + "..." }
        assertEquals(listOf("3...", "3...2...", "3...2...1..."), frames)
    }
}
