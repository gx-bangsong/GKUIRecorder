/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.recorder.asr

import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.lineageos.recorder.PlaybackActivity
import org.lineageos.recorder.PlaybackSession
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * Runs the loop on a real MediaPlayer on the emulator. Each result is sent through the
 * instrumentation status stream with the key GKUI_LOOP, which the workflow reads.
 *
 * Covers: loop off by default; loop on keeps playing and wraps at least twice; toggling the loop
 * while paused does not start playback or move the position; with loop off, the current round
 * ends and playback stops; turning the loop on after a stop does not start playback.
 */
@RunWith(AndroidJUnit4::class)
class PlaybackLoopDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test
    fun loopOnDevice() {
        val uri = insertWav(seconds = 2)
        try {
            val intent = Intent(context, PlaybackActivity::class.java)
                .putExtra(PlaybackActivity.EXTRA_URI, uri.toString())
            ActivityScenario.launch<PlaybackActivity>(intent).use { scenario ->
                waitUntil(scenario) { PlaybackSession.prepared && PlaybackSession.uri == uri }

                val defaultLooping = onMain(scenario) { PlaybackSession.looping }
                report("default looping=$defaultLooping")
                assertFalse("loop must be off by default", defaultLooping)

                // Loop on: keep playing, and count the wraps from the end back to the start.
                onMain(scenario) { PlaybackSession.seekTo(0, play = true) }
                waitUntil(scenario) { onMain(scenario) { PlaybackSession.isPlaying() } }
                onMain(scenario) { PlaybackSession.setLooping(true) }
                var wraps = 0
                var prev = -1L
                var allPlaying = true
                val end = System.currentTimeMillis() + 7_000L
                while (System.currentTimeMillis() < end) {
                    val (pos, playing) = onMain(scenario) {
                        Pair(PlaybackSession.positionMs(), PlaybackSession.isPlaying())
                    }
                    if (!playing) allPlaying = false
                    if (prev >= 0 && pos < prev - 1_000) wraps++
                    prev = pos
                    Thread.sleep(100L)
                }
                report("loop on: wraps=$wraps allPlaying=$allPlaying")
                assertTrue("expected at least 2 wraps, got $wraps", wraps >= 2)
                assertTrue("playback stopped while looping", allPlaying)

                // Pause, then toggle the loop: no autoplay, no position change.
                onMain(scenario) { PlaybackSession.pause() }
                waitUntil(scenario) { !onMain(scenario) { PlaybackSession.isPlaying() } }
                val paused = onMain(scenario) { PlaybackSession.positionMs() }
                onMain(scenario) { PlaybackSession.setLooping(false) }
                onMain(scenario) { PlaybackSession.setLooping(true) }
                Thread.sleep(1_000L)
                val playingAfterToggle = onMain(scenario) { PlaybackSession.isPlaying() }
                val afterToggle = onMain(scenario) { PlaybackSession.positionMs() }
                report("toggle while paused: from=$paused to=$afterToggle playing=$playingAfterToggle")
                assertFalse("toggling the loop must not start playback", playingAfterToggle)
                assertTrue("toggling moved $paused -> $afterToggle", abs(afterToggle - paused) < 300L)

                // Loop off: the current round finishes, then playback stops and does not restart.
                onMain(scenario) { PlaybackSession.setLooping(false) }
                onMain(scenario) { PlaybackSession.seekTo(1_500, play = true) }
                waitUntil(scenario) { onMain(scenario) { PlaybackSession.isPlaying() } }
                waitUntil(scenario, timeoutMs = 6_000L) { !onMain(scenario) { PlaybackSession.isPlaying() } }
                Thread.sleep(1_500L)
                val restarted = onMain(scenario) { PlaybackSession.isPlaying() }
                report("loop off: stopped at end, restarted=$restarted")
                assertFalse("playback restarted after the round ended", restarted)

                // Loop on after a stop: must stay stopped.
                onMain(scenario) { PlaybackSession.setLooping(true) }
                Thread.sleep(1_000L)
                val autoStarted = onMain(scenario) { PlaybackSession.isPlaying() }
                report("loop on after stop: autoStarted=$autoStarted")
                assertFalse("enabling the loop after a stop must not start playback", autoStarted)
            }
        } finally {
            context.contentResolver.delete(uri, null, null)
        }
    }

    private fun report(row: String) {
        instrumentation.sendStatus(0, Bundle().apply { putString("GKUI_LOOP", row) })
    }

    private fun <T> onMain(scenario: ActivityScenario<PlaybackActivity>, block: () -> T): T {
        var result: T? = null
        scenario.onActivity { result = block() }
        return result as T
    }

    private fun waitUntil(
        scenario: ActivityScenario<PlaybackActivity>,
        timeoutMs: Long = 20_000L,
        condition: () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        var ok = condition()
        while (!ok && System.currentTimeMillis() < deadline) {
            Thread.sleep(100L)
            ok = condition()
        }
        assertEquals("timed out waiting for the playback state", true, ok)
    }

    private fun insertWav(seconds: Int): Uri {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, "gkui_loop_probe_${System.currentTimeMillis()}.wav")
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/wav")
            put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/GKUILoopProbe")
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw AssertionError("MediaStore insert failed")
        resolver.openOutputStream(uri)!!.use { it.write(wav(seconds)) }
        resolver.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
        return uri
    }

    /** A 16 kHz mono 16-bit PCM WAV with a quiet 440 Hz tone. */
    private fun wav(seconds: Int): ByteArray {
        val rate = 16_000
        val samples = rate * seconds
        val buffer = ByteBuffer.allocate(44 + samples * 2).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray())
        buffer.putInt(36 + samples * 2)
        buffer.put("WAVE".toByteArray())
        buffer.put("fmt ".toByteArray())
        buffer.putInt(16)
        buffer.putShort(1)
        buffer.putShort(1)
        buffer.putInt(rate)
        buffer.putInt(rate * 2)
        buffer.putShort(2)
        buffer.putShort(16)
        buffer.put("data".toByteArray())
        buffer.putInt(samples * 2)
        for (i in 0 until samples) {
            buffer.putShort((sin(2 * PI * 440 * i / rate) * 3_000).toInt().toShort())
        }
        return buffer.array()
    }
}
