package com.sphy.airconcontroller.apps

/**
 * Decides *when* to start the automatic switch to CarPlay / Android Auto.
 *
 * It fires once each time both conditions (a phone is projecting, ADAS is applied) become true, and not
 * again until one of them has stopped holding. Without that, leaving CarPlay would drag you straight back
 * to it on the next check, and cancelling the countdown would just start another.
 *
 * Process-wide, so recreating the home screen (e.g. a theme change) can't re-trigger it.
 */
object ProjectionAutoSwitch {
    private var consumed = false

    /**
     * @param projecting whether a phone is projecting, or null if that couldn't be told this time.
     * @param adasApplied whether the ADAS profile is applied (or there is none to wait for).
     * @return true exactly when the countdown should start now.
     */
    @Synchronized
    fun shouldStart(projecting: Boolean?, adasApplied: Boolean): Boolean {
        // "Couldn't tell" says nothing, so neither start nor re-arm.
        if (projecting == null) return false
        if (!projecting || !adasApplied) {
            consumed = false
            return false
        }
        if (consumed) return false
        consumed = true
        return true
    }

    /** True while a started switch must be abandoned because a condition no longer holds. */
    fun shouldAbort(projecting: Boolean?, adasApplied: Boolean): Boolean =
        projecting == false || !adasApplied

    @Synchronized
    internal fun reset() {
        consumed = false
    }
}
