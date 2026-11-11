/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.recorder

import kotlin.math.abs
import kotlin.math.max

/** The parts of a media player that [PlaybackControl] uses. Implemented by MediaPlayer in [PlaybackSession]. */
interface PlayerPort {
    val isPlaying: Boolean

    /** The real player position, in milliseconds. */
    val positionMs: Long

    /** Starts an asynchronous seek. Its completion is reported to [PlaybackControl.onSeekComplete]. */
    fun seekTo(positionMs: Long)

    fun start()

    fun pause()

    fun setSpeed(speed: Float)
}

/**
 * Seek, play and pause intents for one open recording. Pure Kotlin, so the rules can be tested
 * without a device.
 *
 * - The last user action wins. A seek or a pause makes any earlier autoplay request stale.
 * - A seek's completion is accepted only when the player is at that seek's target. A completion
 *   from an earlier seek is ignored, so it cannot move the view back to an old position.
 * - The position shown while a seek is in flight is the newest target. After the seek completes it
 *   is the real player position.
 */
class PlaybackControl(
    private val port: PlayerPort,
    private val durationMs: () -> Long,
    private val onChanged: () -> Unit,
) {
    /** Target of the newest seek whose completion has not been accepted yet. */
    private var pendingMs: Long? = null

    /** Set only by the newest seek request. Cleared by any play or pause. */
    private var autoplayAfterSeek = false

    /** Position to show: the newest seek target while a seek is pending, otherwise the real position. */
    fun displayPositionMs(): Long = pendingMs ?: port.positionMs

    fun seekTo(positionMs: Long, play: Boolean) {
        val target = positionMs.coerceIn(0L, max(durationMs(), 0L))
        pendingMs = target
        autoplayAfterSeek = play
        port.seekTo(target)
        onChanged()
    }

    /** Called when the player reports that a seek finished. */
    fun onSeekComplete() {
        val target = pendingMs
        if (target == null) {
            onChanged()
            return
        }
        val atTarget = abs(port.positionMs - target) <= SEEK_TOLERANCE_MS
        // A seek to the very end may stop a little short of the target. Accept it there too.
        val atEnd = target >= durationMs() - SEEK_TOLERANCE_MS
        if (!atTarget && !atEnd) {
            // An earlier seek finished. The newest one reports its own completion.
            return
        }
        pendingMs = null
        if (autoplayAfterSeek) {
            autoplayAfterSeek = false
            port.start()
        }
        onChanged()
    }

    fun play() {
        autoplayAfterSeek = false
        port.start()
        onChanged()
    }

    fun pause() {
        autoplayAfterSeek = false
        port.pause()
        onChanged()
    }

    /**
     * Changing speed must not start playback. Some players start on a speed change, so the state is
     * restored when it was paused.
     */
    fun setSpeed(speed: Float) {
        val wasPlaying = port.isPlaying
        port.setSpeed(speed)
        if (!wasPlaying && port.isPlaying) {
            port.pause()
        }
        onChanged()
    }

    companion object {
        /** Allowed difference between a seek target and the position reported after it completes. */
        const val SEEK_TOLERANCE_MS = 500L
    }
}

/** Taps and small jitter are not scrolling. Only a movement beyond the touch slop counts. */
enum class TouchPhase { DOWN, MOVE, UP }

class ManualScrollDetector(private val touchSlop: Float) {
    private var downY: Float? = null
    private var browsing = false

    /** Returns true once per gesture: at the first movement beyond the slop. */
    fun onTouch(phase: TouchPhase, y: Float): Boolean = when (phase) {
        TouchPhase.DOWN -> {
            downY = y
            browsing = false
            false
        }
        TouchPhase.MOVE -> {
            val start = downY ?: y.also { downY = it }
            if (!browsing && abs(y - start) > touchSlop) {
                browsing = true
                true
            } else {
                false
            }
        }
        TouchPhase.UP -> {
            downY = null
            browsing = false
            false
        }
    }
}

/** Whether a queued automatic scroll may still run when it is finally executed. */
object FollowPolicy {
    fun mayAutoScroll(
        followEnabled: Boolean,
        transcriptVisible: Boolean,
        requestId: Long,
        latestRequestId: Long,
        targetIndex: Int,
        activeIndex: Int,
    ): Boolean = followEnabled &&
        transcriptVisible &&
        requestId == latestRequestId &&
        targetIndex >= 0 &&
        targetIndex == activeIndex
}
