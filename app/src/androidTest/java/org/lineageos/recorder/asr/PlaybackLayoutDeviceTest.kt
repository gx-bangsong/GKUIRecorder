/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.recorder.asr

import android.content.ContentValues
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.provider.MediaStore
import android.view.View
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.CoordinatesProvider
import androidx.test.espresso.action.GeneralClickAction
import androidx.test.espresso.action.GeneralSwipeAction
import androidx.test.espresso.action.Press
import androidx.test.espresso.action.Swipe
import androidx.test.espresso.action.Tap
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.button.MaterialButton
import com.google.android.material.slider.Slider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.lineageos.recorder.PlaybackActivity
import org.lineageos.recorder.PlaybackSession
import org.lineageos.recorder.R
import org.lineageos.recorder.asr.transcription.SpeechRange
import org.lineageos.recorder.asr.transcription.TranscriptSegment
import org.lineageos.recorder.ui.SpeechRangeView
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Device checks for the playback screen. NOT RUN until the emulator workflow runs them.
 *
 * Alignment is checked against the slider's own thumb, not against the overlay or the view width.
 * The thumb centre is measured in a screenshot with the overlay hidden, at value 0 and at the end
 * of the slider. Each band edge is then measured in two screenshots, with the overlay hidden and
 * shown, at the same position, and must be within [TOLERANCE_PX] of the thumb-based position.
 * The same checks run in left-to-right and right-to-left layouts.
 */
@RunWith(AndroidJUnit4::class)
class PlaybackLayoutDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val density: Float get() = context.resources.displayMetrics.density

    // ---- layout ---------------------------------------------------------------------------------

    @Test
    fun bottomControlsFitAndDoNotOverlapOnDevice() {
        val uri = insertWav(seconds = 6)
        try {
            ActivityScenario.launch<PlaybackActivity>(intentFor(uri)).use { scenario ->
                waitUntil(scenario) { PlaybackSession.prepared && PlaybackSession.uri == uri }
                scenario.onActivity { checkControls(it, "portrait audio") }
                scenario.onActivity { it.findViewById<MaterialButton>(R.id.tabTranscriptButton).performClick() }
                instrumentation.waitForIdleSync()
                scenario.onActivity { checkControls(it, "portrait transcript") }

                scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
                instrumentation.waitForIdleSync()
                waitUntil(scenario) { PlaybackSession.prepared && PlaybackSession.uri == uri }
                scenario.onActivity { checkControls(it, "landscape transcript") }
                scenario.onActivity { it.findViewById<MaterialButton>(R.id.tabAudioButton).performClick() }
                instrumentation.waitForIdleSync()
                scenario.onActivity { checkControls(it, "landscape audio") }
            }
        } finally {
            context.contentResolver.delete(uri, null, null)
        }
    }

    // ---- speech bands on the track --------------------------------------------------------------

    @Test
    fun bandsAlignWithSliderTrackOnDevice() = withSegments(rtl = false) { scenario, duration ->
        checkAlignment(scenario, duration, rtl = false)
    }

    @Test
    fun bandsAlignWithSliderTrackInRightToLeftOnDevice() = withSegments(rtl = true) { scenario, duration ->
        checkAlignment(scenario, duration, rtl = true)
    }

    @Test
    fun tapOnBandAndOnTrackSeeksOnDevice() = withSegments(rtl = false) { scenario, duration ->
        checkTaps(scenario, duration)
    }

    @Test
    fun tapOnBandAndOnTrackSeeksInRightToLeftOnDevice() = withSegments(rtl = true) { scenario, duration ->
        checkTaps(scenario, duration)
    }

    @Test
    fun dragOnSliderSeeksOnDevice() = withSegments(rtl = false) { scenario, duration ->
        val thumbs = measureThumbs(scenario, duration)
        val target = 3_000L
        val start = screenPoint(thumbs.startX)
        val end = screenPoint(thumbs.xOf(target, duration))
        onView(withId(R.id.progressSlider)).perform(
            GeneralSwipeAction(Swipe.SLOW, start, end, Press.FINGER),
        )
        instrumentation.waitForIdleSync()
        waitUntil(scenario) { abs(PlaybackSession.positionMs() - target) < DRAG_TOLERANCE_MS }
    }

    @Test
    fun recordingWithoutTranscriptHasNoBandsOnDevice() {
        val uri = insertWav(seconds = 6)
        try {
            ActivityScenario.launch<PlaybackActivity>(intentFor(uri)).use { scenario ->
                waitUntil(scenario) { PlaybackSession.prepared && PlaybackSession.uri == uri }
                instrumentation.waitForIdleSync()
                assertEquals(emptyList<SpeechRange>(), bands(scenario).first)
            }
        } finally {
            context.contentResolver.delete(uri, null, null)
        }
    }

    @Test
    fun bandsAreClearedWhenTheTranscriptIsReplacedByAnEmptyOne() {
        val uri = insertWav(seconds = 6)
        try {
            saveSegments(uri, sampleSegments())
            ActivityScenario.launch<PlaybackActivity>(intentFor(uri)).use { scenario ->
                waitUntil(scenario) { PlaybackSession.prepared && PlaybackSession.uri == uri }
                waitUntil(scenario) { bands(scenario).first.isNotEmpty() }

                // A new run starts from nothing after a completed one, then completes with no segments
                val repository = AsrRuntime.transcripts(context)
                val base = repository.start(uri.toString(), PROBE_MODEL, "v1", "auto")
                repository.complete(uri.toString(), base, emptyList())

                scenario.moveToState(Lifecycle.State.CREATED)
                scenario.moveToState(Lifecycle.State.RESUMED)
                waitUntil(scenario) { bands(scenario).first.isEmpty() }
                assertEquals(emptyList<SpeechRange>(), bands(scenario).first)
            }
        } finally {
            context.contentResolver.delete(uri, null, null)
        }
    }

    // ---- checks ---------------------------------------------------------------------------------

    /** Opens a 6 s recording with the sample transcript, optionally right-to-left, and runs [block]. */
    private fun withSegments(rtl: Boolean, block: (ActivityScenario<PlaybackActivity>, Long) -> Unit) {
        val uri = insertWav(seconds = 6)
        try {
            saveSegments(uri, sampleSegments())
            ActivityScenario.launch<PlaybackActivity>(intentFor(uri)).use { scenario ->
                waitUntil(scenario) { PlaybackSession.prepared && PlaybackSession.uri == uri }
                val duration = PlaybackSession.durationMs
                waitUntil(scenario) { bands(scenario).first == expectedRanges(duration) }
                if (rtl) setRightToLeft(scenario)
                block(scenario, duration)
            }
        } finally {
            context.contentResolver.delete(uri, null, null)
        }
    }

    private fun checkAlignment(scenario: ActivityScenario<PlaybackActivity>, duration: Long, rtl: Boolean) {
        val thumbs = measureThumbs(scenario, duration)
        val slider = sliderRect(scenario)
        val row = slider.top + slider.height() / 2

        // Same position, overlay hidden then shown: the difference is the bands only
        setSliderValue(scenario, 4_000f)
        setBandVisibility(scenario, View.INVISIBLE)
        val without = screenshot()
        setBandVisibility(scenario, View.VISIBLE)
        val with = screenshot()

        val runs = changedRuns(with, without, row, slider)
        val expected = expectedRanges(duration)
            .map { thumbs.xOf(it.startMs, duration) to thumbs.xOf(it.endMs, duration) }
            .map { (a, b) -> min(a, b) to max(a, b) }
            .sortedBy { it.first }
        val message = "rtl=$rtl runs=$runs expected=$expected thumbs=${thumbs.startX}..${thumbs.endX}"
        assertEquals("band count: $message", expected.size, runs.size)
        for ((i, pair) in expected.withIndex()) {
            val run = runs[i]
            val startPx = run.first.toDouble()
            val endPx = run.last + 1.0 // the end is exclusive
            assertTrue("band $i start: $message", abs(startPx - pair.first) <= TOLERANCE_PX)
            assertTrue("band $i end: $message", abs(endPx - pair.second) <= TOLERANCE_PX)
        }

        // Height: the band is the track height, centred on the slider's track
        val middle = (runs.first().first + runs.first().last) / 2
        val bandRows = (slider.top until slider.bottom).filter { y ->
            differs(with.getPixel(middle, y), without.getPixel(middle, y), CHANGED_THRESHOLD)
        }
        val expectedHeight = 6 * density
        assertTrue("band height ${bandRows.size}px, expected about $expectedHeight: $message",
            abs(bandRows.size - expectedHeight) <= TOLERANCE_PX)
        assertTrue("band not centred on the track: $message",
            abs((bandRows.minOrNull()!! + bandRows.maxOrNull()!!) / 2 - row) <= TOLERANCE_PX)
    }

    private fun checkTaps(scenario: ActivityScenario<PlaybackActivity>, duration: Long) {
        val thumbs = measureThumbs(scenario, duration)

        // Inside the band 1800..3000 ms: the band must not take the tap
        tapAt(thumbs.xOf(2_400L, duration))
        waitUntil(scenario) { abs(PlaybackSession.positionMs() - 2_400L) < TAP_TOLERANCE_MS }

        // Outside every band: a tap on the bare track
        tapAt(thumbs.xOf(4_800L, duration))
        waitUntil(scenario) { abs(PlaybackSession.positionMs() - 4_800L) < TAP_TOLERANCE_MS }
    }

    // ---- measurement ----------------------------------------------------------------------------

    /** The slider's thumb centre at value 0 and at the end, from screenshots with the overlay hidden. */
    private data class Thumbs(val startX: Double, val endX: Double) {
        /** Screen x of [ms] on the slider: a straight line between the two thumb centres. */
        fun xOf(ms: Long, duration: Long): Double = startX + (ms.toDouble() / duration) * (endX - startX)
    }

    private fun measureThumbs(scenario: ActivityScenario<PlaybackActivity>, duration: Long): Thumbs {
        setBandVisibility(scenario, View.INVISIBLE)
        try {
            val slider = sliderRect(scenario)
            setSliderValue(scenario, 0f)
            val atStart = thumbCentreX(screenshot(), slider)
            setSliderValue(scenario, duration.toFloat())
            val atEnd = thumbCentreX(screenshot(), slider)
            assertTrue("thumb did not move: $atStart..$atEnd", abs(atEnd - atStart) > slider.width() / 2)
            return Thumbs(atStart, atEnd)
        } finally {
            setBandVisibility(scenario, View.VISIBLE)
            setSliderValue(scenario, 0f)
            instrumentation.waitForIdleSync()
        }
    }

    /**
     * Centre of the thumb. The row used is above the track, so only the thumb is on it, and the thumb
     * is symmetric around its centre.
     */
    private fun thumbCentreX(bitmap: Bitmap, slider: Rect): Double {
        val row = slider.top + slider.height() / 2 - (5 * density).roundToInt()
        val background = bitmap.getPixel(slider.left + 1, row)
        val xs = (slider.left until slider.right).filter { differs(bitmap.getPixel(it, row), background, CHANGED_THRESHOLD) }
        assertTrue("no thumb found on row $row", xs.size >= 4)
        return (xs.minOrNull()!! + xs.maxOrNull()!!) / 2.0
    }

    /** Contiguous runs of pixels changed between [with] and [without] on [row] inside the slider. */
    private fun changedRuns(with: Bitmap, without: Bitmap, row: Int, slider: Rect): List<IntRange> {
        val changed = (slider.left until slider.right).filter { x ->
            differs(with.getPixel(x, row), without.getPixel(x, row), CHANGED_THRESHOLD)
        }
        val runs = mutableListOf<IntRange>()
        for (x in changed) {
            val last = runs.lastOrNull()
            if (last != null && x - last.last <= 1) {
                runs[runs.lastIndex] = last.first..x
            } else {
                runs += x..x
            }
        }
        return runs
    }

    private fun differs(a: Int, b: Int, threshold: Int): Boolean {
        val dr = abs(((a shr 16) and 0xff) - ((b shr 16) and 0xff))
        val dg = abs(((a shr 8) and 0xff) - ((b shr 8) and 0xff))
        val db = abs((a and 0xff) - (b and 0xff))
        return dr + dg + db > threshold
    }

    private fun screenshot(): Bitmap {
        instrumentation.waitForIdleSync()
        Thread.sleep(SETTLE_MS)
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        assertNotNull("screenshot", bitmap)
        return bitmap!!
    }

    // ---- actions --------------------------------------------------------------------------------

    private fun tapAt(screenX: Double) {
        onView(withId(R.id.progressSlider)).perform(
            GeneralClickAction(Tap.SINGLE, screenPoint(screenX), Press.FINGER),
        )
        instrumentation.waitForIdleSync()
    }

    /** A point on the slider's centre line at screen x [screenX]. */
    private fun screenPoint(screenX: Double): CoordinatesProvider = CoordinatesProvider { view ->
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        floatArrayOf((screenX - location[0]).toFloat(), view.height / 2f)
    }

    private fun setRightToLeft(scenario: ActivityScenario<PlaybackActivity>) {
        scenario.onActivity { it.findViewById<View>(R.id.rootLayout).layoutDirection = View.LAYOUT_DIRECTION_RTL }
        instrumentation.waitForIdleSync()
        scenario.onActivity {
            assertEquals(
                View.LAYOUT_DIRECTION_RTL,
                it.findViewById<Slider>(R.id.progressSlider).layoutDirection,
            )
        }
    }

    /** Moves the thumb without a seek: a programmatic value does not call the user seek path. */
    private fun setSliderValue(scenario: ActivityScenario<PlaybackActivity>, value: Float) {
        scenario.onActivity { it.findViewById<Slider>(R.id.progressSlider).value = value }
        instrumentation.waitForIdleSync()
    }

    private fun setBandVisibility(scenario: ActivityScenario<PlaybackActivity>, visibility: Int) {
        scenario.onActivity { it.findViewById<SpeechRangeView>(R.id.speechRangeView).visibility = visibility }
        instrumentation.waitForIdleSync()
    }

    private fun sliderRect(scenario: ActivityScenario<PlaybackActivity>): Rect {
        var rect: Rect? = null
        scenario.onActivity { rect = rectOf(it.findViewById<Slider>(R.id.progressSlider)) }
        return rect!!
    }

    // ---- fixtures and state ---------------------------------------------------------------------

    private fun bands(scenario: ActivityScenario<PlaybackActivity>): Pair<List<SpeechRange>, Long> {
        var result: Pair<List<SpeechRange>, Long>? = null
        scenario.onActivity { activity ->
            val view = activity.findViewById<SpeechRangeView>(R.id.speechRangeView)
            result = view.ranges to view.durationMs
        }
        return result!!
    }

    private fun sampleSegments() = listOf(
        TranscriptSegment(0L, 1_500L, "starts at zero"),
        TranscriptSegment(1_800L, 2_500L, "separate from the first"),
        TranscriptSegment(2_000L, 3_000L, "overlaps the previous, merged with it"),
        TranscriptSegment(3_000L, 3_000L, "zero length, dropped"),
        TranscriptSegment(4_000L, 4_500L, "   "),
        TranscriptSegment(5_000L, 9_000L, "runs past the end, clamped"),
    )

    /** Merged, clamped ranges for [sampleSegments]. Each end is exclusive. */
    private fun expectedRanges(duration: Long) = listOf(
        SpeechRange(0L, 1_500L),
        SpeechRange(1_800L, 3_000L),
        SpeechRange(5_000L, duration),
    )

    private fun saveSegments(uri: Uri, segments: List<TranscriptSegment>) {
        val repository = AsrRuntime.transcripts(context)
        val base = repository.start(uri.toString(), PROBE_MODEL, "v1", "auto")
        repository.complete(uri.toString(), base, segments)
    }

    private fun intentFor(uri: Uri): Intent =
        Intent(context, PlaybackActivity::class.java).putExtra(PlaybackActivity.EXTRA_URI, uri.toString())

    // ---- layout helpers -------------------------------------------------------------------------

    /** Fails when a control is off the root, overlaps another, or the wrong layout variant is used. */
    private fun checkControls(activity: PlaybackActivity, label: String) {
        val root = rectOf(activity.findViewById(R.id.rootLayout))
        val playButton = activity.findViewById<View>(R.id.playPauseImageView)
        val expectedVariant = if (activity.resources.configuration.screenWidthDp >= 600) {
            "controls-single-row"
        } else {
            "controls-two-row"
        }
        assertEquals("$label: layout variant", expectedVariant, playButton.tag)

        val controls = listOf(
            R.id.rewindButton,
            R.id.playPauseImageView,
            R.id.forwardButton,
            R.id.loopButton,
            R.id.speedButton,
            R.id.keepAwakeButton,
        ).map { id -> activity.resources.getResourceEntryName(id) to rectOf(activity.findViewById(id)) }
        for ((name, rect) in controls) {
            assertTrue("$label: $name is outside the screen: $rect in $root", root.contains(rect))
        }
        for (i in controls.indices) {
            for (j in i + 1 until controls.size) {
                assertFalse(
                    "$label: ${controls[i].first} overlaps ${controls[j].first}: ${controls[i].second} and ${controls[j].second}",
                    Rect.intersects(controls[i].second, controls[j].second),
                )
            }
        }
    }

    private fun rectOf(view: View): Rect {
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        return Rect(location[0], location[1], location[0] + view.width, location[1] + view.height)
    }

    private companion object {
        /** Band and thumb edges are compared within this many pixels */
        const val TOLERANCE_PX = 3.0
        /** Summed RGB difference that counts as a change, for a band over the track */
        const val CHANGED_THRESHOLD = 8
        const val SETTLE_MS = 250L
        const val TAP_TOLERANCE_MS = 300L
        const val DRAG_TOLERANCE_MS = 500L
        const val PROBE_MODEL = "probe-model"
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
