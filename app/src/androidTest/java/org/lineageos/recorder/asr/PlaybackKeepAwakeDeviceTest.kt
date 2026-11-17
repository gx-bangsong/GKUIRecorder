/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.recorder.asr

import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.View
import android.view.WindowManager
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.lineageos.recorder.PlaybackActivity
import org.lineageos.recorder.PlaybackSession
import org.lineageos.recorder.R
import org.lineageos.recorder.asr.transcription.TranscriptSegment
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

/**
 * Keep-awake on the transcript tab, on a real emulator. Checks that the transcript button is
 * shown in full and does not overlap its neighbours, that it and the bottom-bar button share one
 * state, and that the window is dimmed only while it is on. Two screenshots are saved to the app's
 * files directory; the emulator workflow returns them as Git blobs for a layout check.
 *
 * Results go through the instrumentation status stream with the key GKUI_KEEP.
 */
@RunWith(AndroidJUnit4::class)
class PlaybackKeepAwakeDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test
    fun keepAwakeOnTranscriptTab() {
        val uri = insertWav(seconds = 6)
        try {
            val repository = AsrRuntime.transcripts(context)
            val base = repository.start(uri.toString(), "probe-model", "v1", "auto")
            repository.complete(
                uri.toString(),
                base,
                listOf(
                    TranscriptSegment(1_000L, 2_000L, "first sentence"),
                    TranscriptSegment(3_000L, 4_500L, "second sentence"),
                ),
            )
            val intent = Intent(context, PlaybackActivity::class.java)
                .putExtra(PlaybackActivity.EXTRA_URI, uri.toString())
                .putExtra(PlaybackActivity.EXTRA_SHOW_TRANSCRIPT, true)
            ActivityScenario.launch<PlaybackActivity>(intent).use { scenario ->
                waitUntil(scenario) { PlaybackSession.prepared && PlaybackSession.uri == uri }
                onView(withId(R.id.transcriptKeepAwakeButton)).perform(scrollTo())
                instrumentation.waitForIdleSync()

                // Layout: the row must not clip or overlap.
                val problems = layoutProblems(
                    scenario,
                    listOf(
                        R.id.copyTranscriptButton,
                        R.id.exportTranscriptButton,
                        R.id.transcriptActionButton,
                        R.id.transcriptKeepAwakeButton,
                    ),
                )
                report("layout problems=${problems.size} ${problems.joinToString("; ")}")
                assertTrue("transcript row layout: $problems", problems.isEmpty())

                // Bottom bar: no clipping, nothing off screen, nothing overlapping, speed label on one line.
                val bottom = layoutProblems(
                    scenario,
                    listOf(
                        R.id.keepAwakeButton,
                        R.id.loopButton,
                        R.id.rewindButton,
                        R.id.playPauseImageView,
                        R.id.forwardButton,
                        R.id.speedButton,
                    ),
                )
                var speedLines = -1
                scenario.onActivity { activity ->
                    speedLines = activity.findViewById<android.widget.TextView>(R.id.speedButton).lineCount
                }
                report("bottom problems=${bottom.size} ${bottom.joinToString("; ")} speedLines=$speedLines")
                assertTrue("bottom bar layout: $bottom", bottom.isEmpty())
                assertEquals("speed label must stay on one line", 1, speedLines)

                // Off by default: no window flag, and the window keeps the user's brightness.
                val offFlag = keepFlagOn(scenario)
                val offBrightness = brightness(scenario)
                report("default flag=$offFlag brightness=$offBrightness")
                assertFalse("keep-awake must be off by default", offFlag)
                assertEquals(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE, offBrightness, 0.001f)
                saveScreenshot("layout-transcript-off")

                // On from the transcript button: flag set, window dimmed, both buttons checked.
                onView(withId(R.id.transcriptKeepAwakeButton)).perform(click())
                instrumentation.waitForIdleSync()
                val onFlag = keepFlagOn(scenario)
                val onBrightness = brightness(scenario)
                val checks = checkedStates(scenario)
                report("transcript on flag=$onFlag brightness=$onBrightness checked=$checks")
                assertTrue("keep-awake flag not set", onFlag)
                assertEquals(0.2f, onBrightness, 0.001f)
                assertEquals(listOf(true, true), checks)
                saveScreenshot("layout-transcript-on")

                // Highlight check: seek into the first sentence, so its active block is on screen.
                scenario.onActivity { PlaybackSession.seekTo(1_500L, play = false) }
                Thread.sleep(1_000L)
                saveScreenshot("layout-transcript-highlight")

                // Off from the bottom bar: the transcript button follows, and the dimming is undone.
                onView(withId(R.id.keepAwakeButton)).perform(click())
                instrumentation.waitForIdleSync()
                val offAgain = keepFlagOn(scenario)
                val restored = brightness(scenario)
                val checksAgain = checkedStates(scenario)
                report("bottom off flag=$offAgain brightness=$restored checked=$checksAgain")
                assertFalse("keep-awake flag still set", offAgain)
                assertEquals(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE, restored, 0.001f)
                assertEquals(listOf(false, false), checksAgain)
            }
        } finally {
            context.contentResolver.delete(uri, null, null)
        }
    }

    /** Problems in the transcript action row: clipped buttons, buttons outside the screen, overlaps. */
    private fun layoutProblems(scenario: ActivityScenario<PlaybackActivity>, ids: List<Int>): List<String> {
        val problems = mutableListOf<String>()
        scenario.onActivity { activity ->
            val decor = activity.window.decorView
            val views = ids.map { activity.findViewById<View>(it) }.filter { it.isShown }
            val rects = views.associateWith { view -> Rect().also { view.getGlobalVisibleRect(it) } }
            val names = views.associateWith { view -> activity.resources.getResourceEntryName(view.id) }
            for (view in views) {
                val rect = rects.getValue(view)
                if (rect.width() != view.width || rect.height() != view.height) {
                    problems += "clipped ${names.getValue(view)}"
                }
                if (rect.left < 0 || rect.right > decor.width) {
                    problems += "outside screen ${names.getValue(view)}"
                }
            }
            for (i in views.indices) {
                for (j in i + 1 until views.size) {
                    if (Rect.intersects(rects.getValue(views[i]), rects.getValue(views[j]))) {
                        problems += "overlap ${names.getValue(views[i])}/${names.getValue(views[j])}"
                    }
                }
            }
        }
        return problems
    }

    private fun keepFlagOn(scenario: ActivityScenario<PlaybackActivity>): Boolean {
        var on = false
        scenario.onActivity { activity ->
            on = (activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) != 0
        }
        return on
    }

    private fun brightness(scenario: ActivityScenario<PlaybackActivity>): Float {
        var value = 0f
        scenario.onActivity { activity -> value = activity.window.attributes.screenBrightness }
        return value
    }

    /** Checked state of the transcript button, then the bottom-bar button. */
    private fun checkedStates(scenario: ActivityScenario<PlaybackActivity>): List<Boolean> {
        var states = emptyList<Boolean>()
        scenario.onActivity { activity ->
            states = listOf(
                activity.findViewById<com.google.android.material.button.MaterialButton>(
                    R.id.transcriptKeepAwakeButton,
                ).isChecked,
                activity.findViewById<com.google.android.material.button.MaterialButton>(
                    R.id.keepAwakeButton,
                ).isChecked,
            )
        }
        return states
    }

    private fun report(row: String) {
        instrumentation.sendStatus(0, Bundle().apply { putString("GKUI_KEEP", row) })
    }

    private fun saveScreenshot(name: String) {
        instrumentation.waitForIdleSync()
        val bitmap: Bitmap = instrumentation.uiAutomation.takeScreenshot()
        File(context.filesDir, "$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    private fun waitUntil(
        scenario: ActivityScenario<PlaybackActivity>,
        timeoutMs: Long = 20_000L,
        condition: () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        var ok = false
        while (!ok && System.currentTimeMillis() < deadline) {
            scenario.onActivity { ok = condition() }
            if (!ok) Thread.sleep(100L)
        }
        assertEquals("timed out waiting for the playback state", true, ok)
    }

    private fun insertWav(seconds: Int): Uri {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, "gkui_keep_probe_${System.currentTimeMillis()}.wav")
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/wav")
            put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/GKUIKeepProbe")
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
