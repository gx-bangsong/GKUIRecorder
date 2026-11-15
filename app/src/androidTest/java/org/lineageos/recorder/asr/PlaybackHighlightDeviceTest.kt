/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.recorder.asr

import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import android.widget.LinearLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.lineageos.recorder.PlaybackActivity
import org.lineageos.recorder.PlaybackSession
import org.lineageos.recorder.R
import org.lineageos.recorder.asr.transcription.SegmentLookup
import org.lineageos.recorder.asr.transcription.TranscriptSegment
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

/**
 * Reproduces what the playback highlight shows on an emulator. A real MediaStore recording is
 * played, a saved transcript with two sentences is shown, and the highlight is read at fixed
 * positions. The results and a screenshot in the silent gap are written to the app's external
 * files directory, which the emulator workflow copies out (see .github/workflows/emulator-test.yml).
 *
 * Expected: 500 ms is before the first sentence (none); 1500 ms is the first; 2500 ms is the gap
 * after it (still the first); 3500 ms is the second; 5000 ms is after the last one (still the second).
 */
@RunWith(AndroidJUnit4::class)
class PlaybackHighlightDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test
    fun highlightAcrossSentencesOnDevice() {
        val resolver = context.contentResolver
        val uri: Uri = insertWav(seconds = 6)
        try {
            val segments = listOf(
                TranscriptSegment(1_000L, 2_000L, "first sentence"),
                TranscriptSegment(3_000L, 4_500L, "second sentence"),
            )
            val repository = AsrRuntime.transcripts(context)
            val base = repository.start(uri.toString(), "probe-model", "v1", "auto")
            repository.complete(
                uri.toString(),
                base,
                segments,
            )
            mark("transcript saved")

            // Internal files: the debug build is debuggable, so the workflow reads them with run-as
            val evidence = File(context.filesDir, "highlight-evidence").apply { mkdirs() }
            val rows = mutableListOf<String>()
            val observed = mutableListOf<Int>()
            val positions = listOf(500L, 1_500L, 2_500L, 3_500L, 5_000L)

            val intent = Intent(context, PlaybackActivity::class.java)
                .putExtra(PlaybackActivity.EXTRA_URI, uri.toString())
                .putExtra(PlaybackActivity.EXTRA_SHOW_TRANSCRIPT, true)
            ActivityScenario.launch<PlaybackActivity>(intent).use { scenario ->
                mark("activity launched")
                waitUntil(scenario) { PlaybackSession.prepared && PlaybackSession.uri == uri }
                mark("player prepared")
                waitUntil(scenario) { segmentContainer(it).childCount == 2 }
                mark("segments shown")

                for (position in positions) {
                    scenario.onActivity { PlaybackSession.seekTo(position, play = false) }
                    Thread.sleep(1_500L)
                    var shown = -1
                    var reported = -1L
                    scenario.onActivity { activity ->
                        reported = PlaybackSession.positionMs()
                        shown = activeIndex(activity.findViewById<LinearLayout>(R.id.transcriptSegmentsContainer))
                    }
                    // What the previous rule (no sticky highlight) would show at the reported position
                    val legacy = SegmentLookup.indexAt(segments, reported)
                    observed += shown
                    mark("position $position shown=$shown")
                    rows += "seek=$position reported=$reported shown=$shown legacy=$legacy"
                    if (position == 2_500L) {
                        saveScreenshot(File(evidence, "gap-2500.png"))
                    }
                }
            }

            val expected = listOf(-1, 0, 0, 1, 1)
            // Logged twice: logcat, and the instrumentation status stream, which am instrument prints.
            rows.forEach { android.util.Log.i("GKUI_HL", it) }
            rows.forEach { row ->
                instrumentation.sendStatus(0, android.os.Bundle().apply { putString("GKUI_HL", row) })
            }
            File(evidence, "results.txt").writeText(rows.joinToString("\n") + "\n")
            assertEquals("highlight per position:\n" + rows.joinToString("\n"), expected, observed)
        } finally {
            resolver.delete(uri, null, null)
        }
    }

    /** Progress markers in logcat, so a hang shows the last step that ran. */
    private fun mark(step: String) {
        android.util.Log.i("GKUI_HL", "step $step")
        instrumentation.sendStatus(0, android.os.Bundle().apply { putString("GKUI_HL", "step $step") })
    }

    private fun segmentContainer(activity: PlaybackActivity): LinearLayout =
        activity.findViewById<LinearLayout>(R.id.transcriptSegmentsContainer)

    /** The highlighted sentence has a background; the others have none. */
    private fun activeIndex(container: LinearLayout): Int =
        (0 until container.childCount).firstOrNull { container.getChildAt(it).background != null } ?: -1

    /** Checks [condition] on the main thread, with the activity, until it holds or time runs out. */
    private fun waitUntil(
        scenario: ActivityScenario<PlaybackActivity>,
        timeoutMs: Long = 20_000L,
        condition: (PlaybackActivity) -> Boolean,
    ) {
        var ok = false
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!ok && System.currentTimeMillis() < deadline) {
            scenario.onActivity { activity -> ok = condition(activity) }
            if (!ok) Thread.sleep(100L)
        }
        assertEquals("timed out waiting for the playback state", true, ok)
    }

    private fun saveScreenshot(file: File) {
        val bitmap: Bitmap = instrumentation.uiAutomation.takeScreenshot()
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun insertWav(seconds: Int): Uri {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, "gkui_highlight_probe_${System.currentTimeMillis()}.wav")
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/wav")
            put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/GKUIHighlightProbe")
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw AssertionError("MediaStore insert failed")
        resolver.openOutputStream(uri)!!.use { it.write(wav(seconds)) }
        resolver.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
        return uri
    }

    /** A 16 kHz mono 16-bit PCM WAV with a quiet 440 Hz tone, so the player has real audio. */
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
