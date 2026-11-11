/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.recorder.asr

/** What transcription does before the next audio chunk. */
enum class ThermalAction {
    /** Normal speed. */
    FULL,

    /** Moderate heat: a short pause between chunks lowers the load. Work continues. */
    REDUCED,

    /** Severe heat: stop at a chunk boundary, keep everything saved, and wait for cooling. */
    PAUSED,
}

/**
 * Maps the platform thermal status to a [ThermalAction], with hysteresis.
 *
 * The input is PowerManager's composite thermal status, not a temperature. The values below match
 * `PowerManager.THERMAL_STATUS_*`; they are repeated here so that this class has no Android
 * dependency and can be tested on the JVM.
 *
 * Entering REDUCED or PAUSED happens at once. Leaving them needs the status to stay below the
 * threshold for [stableMs] without a single interruption. This stops quick up-and-down changes from
 * starting and stopping the work again and again.
 */
class ThermalGovernor(private val stableMs: Long = STABLE_MS) {
    private var reduced = false
    private var paused = false
    private var coolingSince: Long? = null
    private var calmSince: Long? = null

    /** Decides the action for the status seen at [nowMs]. Call it once per chunk or poll. */
    fun decide(status: Int, nowMs: Long): ThermalAction {
        if (status >= STATUS_SEVERE) {
            paused = true
            reduced = true
            coolingSince = null
            calmSince = null
            return ThermalAction.PAUSED
        }

        if (paused) {
            val since = coolingSince ?: nowMs.also { coolingSince = it }
            if (nowMs - since < stableMs) {
                return ThermalAction.PAUSED
            }
            paused = false
            coolingSince = null
        }

        if (status >= STATUS_MODERATE) {
            reduced = true
            calmSince = null
        } else if (reduced) {
            val since = calmSince ?: nowMs.also { calmSince = it }
            if (nowMs - since >= stableMs) {
                reduced = false
                calmSince = null
            }
        }
        return if (reduced) ThermalAction.REDUCED else ThermalAction.FULL
    }

    companion object {
        const val STATUS_NONE = 0
        const val STATUS_LIGHT = 1
        const val STATUS_MODERATE = 2
        const val STATUS_SEVERE = 3

        /** How long the status must stay calm before leaving a heat state. */
        const val STABLE_MS = 30_000L
    }
}
