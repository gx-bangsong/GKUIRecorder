/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.recorder.asr

import org.junit.Assert.assertEquals
import org.junit.Test
import org.lineageos.recorder.asr.ThermalGovernor.Companion.STATUS_LIGHT
import org.lineageos.recorder.asr.ThermalGovernor.Companion.STATUS_MODERATE
import org.lineageos.recorder.asr.ThermalGovernor.Companion.STATUS_NONE
import org.lineageos.recorder.asr.ThermalGovernor.Companion.STATUS_SEVERE

class ThermalGovernorTest {
    private val stable = 30_000L

    @Test
    fun normalStatusRunsAtFullSpeed() {
        val g = ThermalGovernor(stable)
        assertEquals(ThermalAction.FULL, g.decide(STATUS_NONE, 0))
        assertEquals(ThermalAction.FULL, g.decide(STATUS_LIGHT, 1_000))
    }

    @Test
    fun moderateHeatReducesLoadButKeepsWorking() {
        val g = ThermalGovernor(stable)
        // Never PAUSED at moderate heat: work continues at a lower load
        assertEquals(ThermalAction.REDUCED, g.decide(STATUS_MODERATE, 0))
        assertEquals(ThermalAction.REDUCED, g.decide(STATUS_MODERATE, 10_000))
    }

    @Test
    fun severeHeatPausesImmediately() {
        val g = ThermalGovernor(stable)
        assertEquals(ThermalAction.PAUSED, g.decide(STATUS_SEVERE, 0))
    }

    @Test
    fun resumesOnlyAfterStableCooling() {
        val g = ThermalGovernor(stable)
        g.decide(STATUS_SEVERE, 0)
        assertEquals(ThermalAction.PAUSED, g.decide(STATUS_NONE, 5_000))
        assertEquals(ThermalAction.PAUSED, g.decide(STATUS_NONE, 34_000))
        // Cooled for 30 s: paused ends. The load stays reduced until it stays calm for another 30 s.
        assertEquals(ThermalAction.REDUCED, g.decide(STATUS_NONE, 35_000))
        assertEquals(ThermalAction.REDUCED, g.decide(STATUS_NONE, 60_000))
        assertEquals(ThermalAction.FULL, g.decide(STATUS_NONE, 65_000))
    }

    @Test
    fun flappingBetweenSevereAndCoolDoesNotResume() {
        val g = ThermalGovernor(stable)
        g.decide(STATUS_SEVERE, 0)
        // Cools for 10 s, heats again: the cooling window restarts
        assertEquals(ThermalAction.PAUSED, g.decide(STATUS_NONE, 10_000))
        assertEquals(ThermalAction.PAUSED, g.decide(STATUS_SEVERE, 20_000))
        assertEquals(ThermalAction.PAUSED, g.decide(STATUS_NONE, 25_000))
        // Still not enough calm time since the last heat spike
        assertEquals(ThermalAction.PAUSED, g.decide(STATUS_NONE, 40_000))
        assertEquals(ThermalAction.REDUCED, g.decide(STATUS_NONE, 56_000))
    }

    @Test
    fun reducedLoadDoesNotFlipBackToFullOnBriefDips() {
        val g = ThermalGovernor(stable)
        assertEquals(ThermalAction.REDUCED, g.decide(STATUS_MODERATE, 0))
        // Brief calm, then moderate again: stays reduced
        assertEquals(ThermalAction.REDUCED, g.decide(STATUS_NONE, 5_000))
        assertEquals(ThermalAction.REDUCED, g.decide(STATUS_MODERATE, 10_000))
        assertEquals(ThermalAction.REDUCED, g.decide(STATUS_NONE, 15_000))
        assertEquals(ThermalAction.REDUCED, g.decide(STATUS_NONE, 40_000))
        assertEquals(ThermalAction.FULL, g.decide(STATUS_NONE, 45_000))
    }

    @Test
    fun alternatingModerateAndNoneNeverReachesFullSpeed() {
        val g = ThermalGovernor(stable)
        var t = 0L
        repeat(100) { i ->
            val status = if (i % 2 == 0) STATUS_MODERATE else STATUS_NONE
            assertEquals(ThermalAction.REDUCED, g.decide(status, t))
            t += 5_000
        }
    }
}
