/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.recorder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A player that moves only when told to, like a MediaPlayer with an asynchronous seek. */
private class FakePort : PlayerPort {
    override var isPlaying = false
    override var positionMs = 0L
    var seeks = mutableListOf<Long>()
    var starts = 0
    var pauses = 0
    var speedChangesStartPlayback = false
    override var looping = false

    override fun seekTo(positionMs: Long) {
        seeks += positionMs
    }

    override fun start() {
        starts++
        isPlaying = true
    }

    override fun pause() {
        pauses++
        isPlaying = false
    }

    override fun setSpeed(speed: Float) {
        if (speedChangesStartPlayback) isPlaying = true
    }

    /** Completes a seek as the real player does: the position is the target. */
    fun finishSeek(target: Long) {
        positionMs = target
    }
}

class LoopTest {
    private val port = FakePort()
    private var changes = 0

    private fun control(initialLooping: Boolean = false) =
        PlaybackControl(port, durationMs = { 10_000L }, onChanged = { changes++ }, initialLooping = initialLooping)

    @Test
    fun defaultsToOffAndNothingIsApplied() {
        val c = control()
        assertFalse(c.looping)
        c.onPrepared()
        assertFalse(port.looping)
    }

    @Test
    fun choiceBeforePreparedIsAppliedOnPrepare() {
        val c = control()
        c.setLooping(true)
        // Not prepared yet: the player is not touched
        assertFalse(port.looping)
        assertTrue(c.looping)
        c.onPrepared()
        assertTrue(port.looping)
    }

    @Test
    fun togglingWhilePausedKeepsPositionAndDoesNotPlay() {
        val c = control()
        c.onPrepared()
        port.positionMs = 4_200L
        c.setLooping(true)
        assertTrue(port.looping)
        assertEquals(4_200L, port.positionMs)
        assertFalse(port.isPlaying)
        assertEquals(0, port.starts)
        assertEquals(0, port.pauses)
        assertTrue(changes >= 1)
    }

    @Test
    fun togglingWhilePlayingKeepsPlaying() {
        val c = control()
        c.onPrepared()
        c.play()
        port.positionMs = 7_000L
        c.setLooping(true)
        c.setLooping(false)
        assertTrue(port.isPlaying)
        assertEquals(7_000L, port.positionMs)
        assertFalse(port.looping)
        assertEquals(1, port.starts)
        assertEquals(0, port.pauses)
    }

    @Test
    fun loopingAfterPauseDoesNotRestartPlayback() {
        val c = control(initialLooping = true)
        c.onPrepared()
        assertTrue(port.looping)
        assertFalse(port.isPlaying)
        assertEquals(0, port.starts)
    }

    @Test
    fun sameValueIsANoOp() {
        val c = control()
        c.onPrepared()
        val before = changes
        c.setLooping(false)
        assertEquals(before, changes)
    }

    @Test
    fun lateSeekCompletionAfterStopCannotStartPlayback() {
        // No seek is pending (the recording was stopped): a late completion must not start playback
        val c = control()
        c.onPrepared()
        c.onSeekComplete()
        assertEquals(0, port.starts)
        assertFalse(port.isPlaying)
    }
}

class PlaybackControlTest {
    private val port = FakePort()
    private var changes = 0
    private val control = PlaybackControl(port, durationMs = { 10_000L }, onChanged = { changes++ })

    @Test
    fun tapSeeksAndPlaysAfterCompletion() {
        control.seekTo(4_200L, play = true)
        assertEquals(listOf(4_200L), port.seeks)
        // Playback must not start before the seek completes
        assertEquals(0, port.starts)
        assertEquals(4_200L, control.displayPositionMs())
        port.finishSeek(4_200L)
        control.onSeekComplete()
        assertEquals(1, port.starts)
        assertTrue(port.isPlaying)
        // After completion the displayed position is the real one
        assertEquals(4_200L, control.displayPositionMs())
    }

    @Test
    fun seekWithoutPlayDoesNotStart() {
        control.seekTo(1_000L, play = false)
        port.finishSeek(1_000L)
        control.onSeekComplete()
        assertEquals(0, port.starts)
        assertFalse(port.isPlaying)
    }

    @Test
    fun staleCompletionFromAnEarlierSeekIsIgnored() {
        control.seekTo(2_000L, play = false)
        control.seekTo(8_000L, play = true)
        // The first seek finishes late; the view must not move back to 2 s, and playback must not start
        port.positionMs = 2_000L
        control.onSeekComplete()
        assertEquals(0, port.starts)
        assertEquals(8_000L, control.displayPositionMs())
        // The newest seek completes and starts playback
        port.finishSeek(8_000L)
        control.onSeekComplete()
        assertEquals(1, port.starts)
    }

    @Test
    fun pauseAfterTapCancelsAutoplay() {
        control.seekTo(3_000L, play = true)
        control.pause()
        port.finishSeek(3_000L)
        control.onSeekComplete()
        assertEquals(0, port.starts)
    }

    @Test
    fun seekToEndIsAcceptedWhenPlayerStopsShort() {
        control.seekTo(10_000L, play = false)
        port.positionMs = 9_700L
        control.onSeekComplete()
        // Accepted: the pending target is cleared, so the view shows the real position
        assertEquals(9_700L, control.displayPositionMs())
    }

    @Test
    fun seekTargetIsClampedToDuration() {
        control.seekTo(99_999L, play = false)
        assertEquals(listOf(10_000L), port.seeks)
        control.seekTo(-5L, play = false)
        assertEquals(0L, port.seeks.last())
    }

    @Test
    fun speedChangeKeepsPausedState() {
        port.speedChangesStartPlayback = true
        control.setSpeed(1.5f)
        assertFalse(port.isPlaying)
        assertEquals(1, port.pauses)
    }

    @Test
    fun speedChangeKeepsPlayingState() {
        port.isPlaying = true
        port.speedChangesStartPlayback = false
        control.setSpeed(0.75f)
        assertTrue(port.isPlaying)
        assertEquals(0, port.pauses)
    }

    @Test
    fun everyIntentNotifiesOnce() {
        control.play()
        control.pause()
        control.setSpeed(1f)
        assertEquals(3, changes)
    }

    @Test
    fun manualScrollIsDetectedOnceBeyondSlop() {
        val d = ManualScrollDetector(touchSlop = 10f)
        assertFalse(d.onTouch(TouchPhase.DOWN, 100f))
        // Small jitter is a tap, not scrolling
        assertFalse(d.onTouch(TouchPhase.MOVE, 105f))
        assertTrue(d.onTouch(TouchPhase.MOVE, 130f))
        // Only once per gesture
        assertFalse(d.onTouch(TouchPhase.MOVE, 160f))
        d.onTouch(TouchPhase.UP, 160f)
        assertFalse(d.onTouch(TouchPhase.DOWN, 50f))
        assertTrue(d.onTouch(TouchPhase.MOVE, 80f))
    }

    @Test
    fun tapIsNeverScrolling() {
        val d = ManualScrollDetector(touchSlop = 10f)
        d.onTouch(TouchPhase.DOWN, 0f)
        assertFalse(d.onTouch(TouchPhase.UP, 0f))
    }

    @Test
    fun followOnlyForTheNewestAutoScrollOfTheActiveSegment() {
        assertTrue(FollowPolicy.mayAutoScroll(true, true, requestId = 3, latestRequestId = 3, targetIndex = 2, activeIndex = 2))
        // Follow turned off, panel hidden, stale request, or the active segment has moved on
        assertFalse(FollowPolicy.mayAutoScroll(false, true, 3, 3, 2, 2))
        assertFalse(FollowPolicy.mayAutoScroll(true, false, 3, 3, 2, 2))
        assertFalse(FollowPolicy.mayAutoScroll(true, true, 2, 3, 2, 2))
        assertFalse(FollowPolicy.mayAutoScroll(true, true, 3, 3, 2, 3))
        assertFalse(FollowPolicy.mayAutoScroll(true, true, 3, 3, -1, -1))
    }
}
