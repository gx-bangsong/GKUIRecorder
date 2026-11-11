/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.recorder

import android.content.ClipData
import android.content.ClipboardManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.format.DateUtils
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.slider.Slider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.lineageos.recorder.asr.AsrRuntime
import org.lineageos.recorder.asr.RunPhase
import org.lineageos.recorder.asr.TranscriptionScheduler
import org.lineageos.recorder.asr.model.ManifestValidationResult
import org.lineageos.recorder.asr.model.ModelCatalog
import org.lineageos.recorder.asr.transcription.TranscriptPanel
import org.lineageos.recorder.asr.transcription.TranscriptPanelState
import org.lineageos.recorder.asr.transcription.SegmentLookup
import org.lineageos.recorder.asr.transcription.TranscriptPanels
import org.lineageos.recorder.asr.transcription.TranscriptSegment
import org.lineageos.recorder.asr.ui.TranscriptionStarter
import org.lineageos.recorder.models.Marker
import org.lineageos.recorder.models.MarkerType
import org.lineageos.recorder.playback.PlaybackAdapter
import org.lineageos.recorder.playback.PlaybackRow
import org.lineageos.recorder.playback.PlaybackRows
import org.lineageos.recorder.ui.PlaybackWaveformView
import org.lineageos.recorder.utils.ExportHelper
import org.lineageos.recorder.utils.PreferencesManager
import org.lineageos.recorder.utils.WaveformLoader
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

/**
 * Player laid out like the Pixel Recorder: title, waveform with markers and chapters,
 * an Audio / Transcript switch, and the progress bar with transport controls at the bottom.
 */
class PlaybackActivity : AppCompatActivity(R.layout.activity_playback) {
    // Views
    private val rootLayout by lazy { findViewById<View>(R.id.rootLayout) }
    private val toolbar by lazy { findViewById<Toolbar>(R.id.toolbar) }
    private val titleTextView by lazy { findViewById<TextView>(R.id.titleTextView) }
    private val dateTextView by lazy { findViewById<TextView>(R.id.dateTextView) }
    private val tabGroup by lazy { findViewById<MaterialButtonToggleGroup>(R.id.tabGroup) }
    private val audioGroup by lazy { findViewById<View>(R.id.audioGroup) }
    private val transcriptScroll by lazy { findViewById<ScrollView>(R.id.transcriptScroll) }
    private val transcriptStatusTextView by lazy { findViewById<TextView>(R.id.transcriptStatusTextView) }
    private val transcriptBannerTextView by lazy { findViewById<TextView>(R.id.transcriptBannerTextView) }
    private val transcriptTextView by lazy { findViewById<TextView>(R.id.transcriptTextView) }
    private val copyTranscriptButton by lazy { findViewById<MaterialButton>(R.id.copyTranscriptButton) }
    private val exportTranscriptButton by lazy { findViewById<MaterialButton>(R.id.exportTranscriptButton) }
    private val transcriptActionButton by lazy { findViewById<MaterialButton>(R.id.transcriptActionButton) }
    private val cancelTranscriptButton by lazy { findViewById<MaterialButton>(R.id.cancelTranscriptButton) }
    private val waveformView by lazy { findViewById<PlaybackWaveformView>(R.id.waveformView) }
    private val addImportantButton by lazy { findViewById<MaterialButton>(R.id.addImportantButton) }
    private val addSegmentButton by lazy { findViewById<MaterialButton>(R.id.addSegmentButton) }
    private val emptyTextView by lazy { findViewById<TextView>(R.id.emptyTextView) }
    private val rowsRecyclerView by lazy { findViewById<RecyclerView>(R.id.rowsRecyclerView) }
    private val progressSlider by lazy { findViewById<Slider>(R.id.progressSlider) }
    private val currentTimeTextView by lazy { findViewById<TextView>(R.id.currentTimeTextView) }
    private val remainingTimeTextView by lazy { findViewById<TextView>(R.id.remainingTimeTextView) }
    private val rewindButton by lazy { findViewById<FloatingActionButton>(R.id.rewindButton) }
    private val forwardButton by lazy { findViewById<FloatingActionButton>(R.id.forwardButton) }
    private val playPauseButton by lazy { findViewById<FloatingActionButton>(R.id.playPauseImageView) }
    private val speedButton by lazy { findViewById<MaterialButton>(R.id.speedButton) }
    private val keepAwakeButton by lazy { findViewById<MaterialButton>(R.id.keepAwakeButton) }
    private val transcriptHintTextView by lazy { findViewById<TextView>(R.id.transcriptHintTextView) }
    private val transcriptSegmentsContainer by lazy {
        findViewById<LinearLayout>(R.id.transcriptSegmentsContainer)
    }
    private val followButton by lazy { findViewById<MaterialButton>(R.id.followButton) }

    private val preferences by lazy { PreferencesManager(this) }
    private val adapter by lazy {
        PlaybackAdapter(onClick = ::onRowClick, onLongClick = ::onRowLongClick)
    }

    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            updateProgress()
            handler.postDelayed(this, TICK_MS)
        }
    }

    // The player belongs to PlaybackSession, so it outlives this screen
    private val prepared: Boolean get() = PlaybackSession.prepared
    private val durationMs: Long get() = PlaybackSession.durationMs
    private var uri: Uri? = null
    private var recordingTitle = ""
    private var seeking = false
    private var setupDone = false
    private var errorShown = false
    private val playbackListener: () -> Unit = { onSessionChanged() }
    private val markers = mutableListOf<Marker>()
    private var speed = 1f
    private var keepScreenOn = false
    private var transcriptLoad: Job? = null
    private var shownPanel: TranscriptPanel? = null

    // Timed transcript segments, shown as tappable blocks and highlighted from the playback position
    private var segments: List<TranscriptSegment> = emptyList()
    private val segmentViews = mutableListOf<TextView>()
    private var activeSegment = -1

    // Follows the playing segment until the user browses the transcript by hand
    private var followPlayback = true
    private var scrollRequest = 0L

    // A segment tapped while the recording is still loading. Applied when it is ready.
    private var pendingTapMs: Long? = null

    // The recording cannot be played (deleted or unreadable). The transcript stays readable.
    private var recordingUnavailable = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val parsed = (intent.getStringExtra(EXTRA_URI) ?: intent.dataString)
            ?.let { Uri.parse(it) }
        if (parsed == null) {
            finish()
            return
        }
        uri = parsed

        // targetSdk 36 draws edge-to-edge: keep everything below the system bars
        ViewCompat.setOnApplyWindowInsetsListener(rootLayout) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }

        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = ""

        recordingTitle = intent.getStringExtra(EXTRA_TITLE)
            ?: displayNameOf(parsed)
            ?: getString(R.string.sound_record_default_name)
        titleTextView.text = recordingTitle.substringBeforeLast('.')

        val dateAdded = dateAddedOf(parsed)
        dateTextView.isVisible = dateAdded != null
        if (dateAdded != null) {
            dateTextView.text = DateUtils.formatDateTime(
                this,
                dateAdded * 1000L,
                DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_ABBREV_WEEKDAY or
                    DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH or
                    DateUtils.FORMAT_SHOW_TIME,
            )
        }

        markers.addAll(preferences.getMarkers(parsed.toString()))

        rowsRecyclerView.layoutManager = LinearLayoutManager(this)
        rowsRecyclerView.adapter = adapter

        waveformView.onSeek = { fraction -> seekTo((fraction * durationMs).toLong()) }
        addImportantButton.setOnClickListener { addMarker(MarkerType.IMPORTANT) }
        addSegmentButton.setOnClickListener { addMarker(MarkerType.SEGMENT) }
        playPauseButton.setOnClickListener { togglePlay() }
        rewindButton.setOnClickListener { seekTo(currentPosition() - SKIP_MS) }
        forwardButton.setOnClickListener { seekTo(currentPosition() + SKIP_MS) }
        speed = savedInstanceState?.getFloat(KEY_SPEED, 1f) ?: 1f
        keepScreenOn = savedInstanceState?.getBoolean(KEY_KEEP_SCREEN_ON, false) ?: false
        speedButton.text = formatSpeed(speed)
        renderKeepAwake()
        speedButton.setOnClickListener { showSpeedDialog() }
        // Only a real drag stops auto-follow. A tap on a segment does not.
        val detector = ManualScrollDetector(ViewConfiguration.get(this).scaledTouchSlop.toFloat())
        transcriptScroll.setOnTouchListener { _, event ->
            val touch = when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> TouchPhase.DOWN
                MotionEvent.ACTION_MOVE -> TouchPhase.MOVE
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> TouchPhase.UP
                else -> null
            }
            if (touch != null && detector.onTouch(touch, event.y)) {
                onManualBrowse()
            }
            false
        }
        followButton.setOnClickListener {
            followPlayback = true
            followButton.isVisible = false
            scrollToSegment(activeSegment)
        }
        keepAwakeButton.setOnClickListener {
            keepScreenOn = !keepScreenOn
            renderKeepAwake()
        }

        progressSlider.addOnChangeListener(Slider.OnChangeListener { _, value, fromUser ->
            if (fromUser) {
                seekTo(value.toLong())
            }
        })
        progressSlider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {
                seeking = true
            }

            override fun onStopTrackingTouch(slider: Slider) {
                seeking = false
            }
        })

        tabGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                showTab(transcript = checkedId == R.id.tabTranscriptButton)
            }
        }

        copyTranscriptButton.setOnClickListener { copyTranscript() }
        exportTranscriptButton.setOnClickListener { exportTranscript() }
        transcriptActionButton.setOnClickListener { onTranscriptActionClicked() }
        cancelTranscriptButton.setOnClickListener {
            TranscriptionScheduler.cancel(this, parsed.toString())
        }

        // Live work changes (progress, completion, cancel) re-read the saved transcript. The saved file
        // is the source of the text, not the work record. Only delivered while the screen is started.
        TranscriptionScheduler.liveWork(this, parsed.toString()).observe(this) { _ -> loadTranscript() }

        loadPlayer(parsed)
        refreshRows()

        // A recording deleted since the list was shown: say so and keep the readable transcript.
        lifecycleScope.launch {
            val exists = withContext(Dispatchers.IO) {
                AsrRuntime.recordingExists(this@PlaybackActivity, parsed.toString())
            }
            if (!exists) {
                Toast.makeText(this@PlaybackActivity, R.string.recording_deleted, Toast.LENGTH_LONG).show()
                markRecordingUnavailable()
            }
        }

        if (intent.getBooleanExtra(EXTRA_SHOW_TRANSCRIPT, false)) {
            tabGroup.check(R.id.tabTranscriptButton)
        }
    }

    override fun onResume() {
        super.onResume()
        // Returning to the screen (foreground, after rotation or after the list) re-reads the saved state
        loadTranscript()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putFloat(KEY_SPEED, speed)
        outState.putBoolean(KEY_KEEP_SCREEN_ON, keepScreenOn)
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onStart() {
        super.onStart()
        // Null when onCreate already finished the screen
        val target = uri ?: return
        if (isFinishing) return
        PlaybackSession.addListener(playbackListener)
        // Playback is not stopped when the screen is left. Opening a different recording
        // replaces the one that is loaded, so this screen always shows its own recording.
        if (PlaybackSession.uri != target) {
            loadPlayer(target)
        }
        onSessionChanged()
    }

    override fun onStop() {
        PlaybackSession.removeListener(playbackListener)
        handler.removeCallbacks(ticker)
        super.onStop()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        // Close the recording only when nothing is playing; a playing recording continues in
        // the background until it is stopped from the notification.
        if (isFinishing && !PlaybackSession.isPlaying()) {
            PlaybackSession.stop()
        }
        super.onDestroy()
    }

    private fun loadPlayer(source: Uri) {
        setupDone = false
        if (!PlaybackSession.open(this, source, recordingTitle)) {
            Toast.makeText(this, R.string.playback_load_failed, Toast.LENGTH_SHORT).show()
            // Keep the screen so the transcript can still be read
            markRecordingUnavailable()
        }
    }

    private fun markRecordingUnavailable() {
        recordingUnavailable = true
        renderControlsEnabled()
        if (tabGroup.checkedButtonId != R.id.tabTranscriptButton) {
            tabGroup.check(R.id.tabTranscriptButton)
        }
    }

    /** Transport controls work only with a loaded, playable recording. */
    private fun renderControlsEnabled() {
        val ready = prepared && !recordingUnavailable
        playPauseButton.isEnabled = ready
        rewindButton.isEnabled = ready
        forwardButton.isEnabled = ready
        speedButton.isEnabled = ready
        progressSlider.isEnabled = ready && durationMs > 0L
    }

    /** Runs on every change of the session: loads, prepares, play, pause, stop. */
    private fun onSessionChanged() {
        if (PlaybackSession.loadFailed) {
            pendingTapMs = null
            if (!errorShown) {
                errorShown = true
                Toast.makeText(this, R.string.playback_load_failed, Toast.LENGTH_SHORT).show()
            }
            markRecordingUnavailable()
            renderPlayState()
            return
        }
        if (prepared && !setupDone) {
            setupDone = true
            // The slider range must be valid before its value is set
            progressSlider.valueFrom = 0f
            progressSlider.valueTo = max(durationMs.toFloat(), 1f)
            progressSlider.isEnabled = durationMs > 0L
            updateProgress()
            refreshRows()
            loadWaveform()
            applySpeed()
        }
        renderPlayState()
        // A segment tapped during loading starts now. Only the newest tap was kept.
        pendingTapMs?.let { tapped ->
            pendingTapMs = null
            playFrom(tapped)
        }
    }

    /** Shows the play or pause state and runs the progress ticker only while playing. */
    private fun renderPlayState() {
        renderControlsEnabled()
        if (PlaybackSession.isPlaying()) {
            playPauseButton.setImageResource(R.drawable.ic_pause)
            playPauseButton.contentDescription = getString(R.string.pause)
            handler.removeCallbacks(ticker)
            handler.post(ticker)
        } else {
            playPauseButton.setImageResource(R.drawable.ic_play_arrow)
            playPauseButton.contentDescription = getString(R.string.play)
            handler.removeCallbacks(ticker)
            updateProgress()
        }
    }

    private fun loadWaveform() {
        val source = uri ?: return
        lifecycleScope.launch {
            val peaks = WaveformLoader.load(
                this@PlaybackActivity,
                source,
                recordingTitle.endsWith(".wav", ignoreCase = true),
                durationMs,
                WAVEFORM_BINS,
            )
            waveformView.peaks = peaks
            refreshTicks()
        }
    }

    private fun togglePlay() {
        if (!prepared) {
            return
        }
        pendingTapMs = null
        if (PlaybackSession.isPlaying()) {
            pausePlayback()
        } else {
            startPlayback()
        }
    }

    private fun startPlayback() {
        // The foreground service keeps playback alive when this screen is left. Start it only
        // while the screen is visible, then let the session play; the listener updates the UI.
        PlaybackService.start(this)
        PlaybackSession.play()
    }

    private fun pausePlayback() {
        PlaybackSession.pause()
    }

    /** The position to show: the newest seek target while a seek is in flight, else the real position. */
    private fun currentPosition(): Long = PlaybackSession.positionMs()

    /** A seek from the controls. It keeps the current play or pause state. */
    private fun seekTo(positionMs: Long) {
        if (!prepared || recordingUnavailable) {
            return
        }
        PlaybackSession.seekTo(positionMs.coerceIn(0L, durationMs), play = false)
        updateProgress()
    }

    private fun showSpeedDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_playback_speed, null)
        val valueText = view.findViewById<TextView>(R.id.speedValueTextView)
        val slider = view.findViewById<Slider>(R.id.speedSlider)
        val chipGroup = view.findViewById<ChipGroup>(R.id.speedChipGroup)

        // Shortcut chips in 0.5 steps. Tapping one moves the slider, which applies the speed.
        val chips = SPEED_SHORTCUTS.map { value ->
            Chip(this).also { chip ->
                chip.text = formatSpeed(value)
                chip.isCheckable = true
                chip.setOnClickListener { slider.value = value }
                chipGroup.addView(chip)
            }
        }

        fun update(value: Float) {
            valueText.text = formatSpeed(value)
            SPEED_SHORTCUTS.forEachIndexed { index, shortcut ->
                chips[index].isChecked = abs(shortcut - value) < SPEED_EPSILON
            }
        }

        slider.value = speed.coerceIn(SPEED_MIN, SPEED_MAX)
        update(slider.value)
        slider.addOnChangeListener(Slider.OnChangeListener { _, value, _ ->
            setSpeed(value)
            update(value)
        })

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.playback_speed_title)
            .setView(view)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun setSpeed(value: Float) {
        speed = value.coerceIn(SPEED_MIN, SPEED_MAX)
        applySpeed()
    }

    /** Applies the chosen speed to the player when it is ready, and shows it on the button. */
    private fun applySpeed() {
        speedButton.text = formatSpeed(speed)
        if (prepared) {
            PlaybackSession.setSpeed(speed)
        }
    }

    private fun formatSpeed(value: Float): String {
        val number = if (value % 1f == 0f) {
            value.toInt().toString()
        } else {
            String.format(Locale.ROOT, "%.2f", value).trimEnd('0').trimEnd('.')
        }
        return "${number}x"
    }

    /** Keeps the screen on while this screen is visible. Off by default. */
    private fun renderKeepAwake() {
        if (keepScreenOn) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        val background = if (keepScreenOn) {
            MaterialColors.getColor(
                keepAwakeButton,
                com.google.android.material.R.attr.colorSecondaryContainer,
            )
        } else {
            Color.TRANSPARENT
        }
        val iconColor = if (keepScreenOn) {
            com.google.android.material.R.attr.colorOnSecondaryContainer
        } else {
            com.google.android.material.R.attr.colorOnSurfaceVariant
        }
        keepAwakeButton.backgroundTintList = ColorStateList.valueOf(background)
        keepAwakeButton.iconTint = ColorStateList.valueOf(MaterialColors.getColor(keepAwakeButton, iconColor))
        keepAwakeButton.isSelected = keepScreenOn
    }

    /** Called on every manual or automatic progress change. Reads the real player position. */
    private fun updateProgress() {
        val position = currentPosition().coerceIn(0L, max(durationMs, 0L))
        currentTimeTextView.text = PlaybackRows.formatTime(position)
        remainingTimeTextView.text = getString(
            R.string.playback_remaining,
            PlaybackRows.formatTime(durationMs - position),
        )
        if (durationMs > 0L) {
            if (!seeking) {
                progressSlider.value = position.toFloat()
            }
            waveformView.progress = position.toFloat() / durationMs
        } else {
            waveformView.progress = 0f
        }
        applyKaraoke(position)
    }

    /**
     * Shows the timed segments as separate tappable blocks. Records without timed segments keep the
     * single text view: their text is shown and copied, but nothing is tapped or highlighted.
     */
    private fun setSegments(found: List<TranscriptSegment>) {
        val usable = found.filter { it.text.isNotBlank() }
        transcriptSegmentsContainer.isVisible = usable.isNotEmpty()
        transcriptHintTextView.isVisible = usable.isNotEmpty()
        transcriptTextView.isVisible = usable.isEmpty() && shownPanel?.hasBody == true
        if (usable == segments && segmentViews.size == usable.size) {
            // Same segments as before (a reload with no change): keep the views and the highlight
            applyKaraoke(currentPosition())
            return
        }
        segments = usable
        activeSegment = -1
        segmentViews.clear()
        transcriptSegmentsContainer.removeAllViews()
        val density = resources.displayMetrics.density
        val padH = (12 * density).toInt()
        val padV = (8 * density).toInt()
        usable.forEach { segment ->
            val view = TextView(this).apply {
                text = segment.text.trim()
                textSize = 17f
                setTextColor(attrColor(com.google.android.material.R.attr.colorOnSurface))
                setPadding(padH, padV, padH, padV)
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    followPlayback = true
                    followButton.isVisible = false
                    playFrom(segment.startMs)
                }
            }
            transcriptSegmentsContainer.addView(
                view,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = (4 * density).toInt() },
            )
            segmentViews += view
        }
        applyKaraoke(currentPosition())
    }

    /** Highlights the segment at the real playback position. Only while the transcript tab is shown. */
    private fun applyKaraoke(positionMs: Long) {
        if (segments.isEmpty() || !transcriptScroll.isVisible) return
        val index = SegmentLookup.indexAt(segments, positionMs)
        if (index == activeSegment) return
        segmentViews.getOrNull(activeSegment)?.let { setSegmentActive(it, false) }
        activeSegment = index
        segmentViews.getOrNull(index)?.let { setSegmentActive(it, true) }
        if (followPlayback && index >= 0) {
            scrollToSegment(index)
        }
    }

    /** Active segment: a background block and bold text, so it is not only a colour change. */
    private fun setSegmentActive(view: TextView, active: Boolean) {
        view.typeface = if (active) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        view.setTextColor(
            attrColor(
                if (active) {
                    com.google.android.material.R.attr.colorOnSecondaryContainer
                } else {
                    com.google.android.material.R.attr.colorOnSurface
                },
            ),
        )
        view.background = if (active) {
            GradientDrawable().apply {
                cornerRadius = 12 * resources.displayMetrics.density
                setColor(attrColor(com.google.android.material.R.attr.colorSecondaryContainer))
            }
        } else {
            null
        }
    }

    private fun scrollToSegment(index: Int) {
        val view = segmentViews.getOrNull(index) ?: return
        val request = ++scrollRequest
        view.post {
            // Checked again when it runs: the user may have browsed, left the tab, or left the screen
            val allowed = FollowPolicy.mayAutoScroll(
                followEnabled = followPlayback,
                transcriptVisible = transcriptScroll.isVisible,
                requestId = request,
                latestRequestId = scrollRequest,
                targetIndex = index,
                activeIndex = activeSegment,
            ) && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
            if (allowed) {
                // Put the playing segment about a third of the way down the visible area
                val target = offsetInScroll(view) - transcriptScroll.height / 3
                transcriptScroll.smoothScrollTo(0, max(target, 0))
            }
        }
    }

    /** Vertical position of [view] inside the scroll view's content. */
    private fun offsetInScroll(view: View): Int {
        var y = 0
        var current: View? = view
        while (current != null && current !== transcriptScroll) {
            y += current.top
            current = current.parent as? View
        }
        return y
    }

    /** A manual scroll stops following the playing segment and offers a way back. */
    private fun onManualBrowse() {
        // Drop any auto-scroll that is already queued
        scrollRequest++
        if (followPlayback && segments.isNotEmpty()) {
            followPlayback = false
            followButton.isVisible = true
        }
    }

    /** Tapping a segment seeks to its start and plays. It does not start transcription. */
    private fun playFrom(positionMs: Long) {
        if (recordingUnavailable) {
            Toast.makeText(this, R.string.playback_recording_unavailable, Toast.LENGTH_SHORT).show()
            return
        }
        followPlayback = true
        followButton.isVisible = false
        if (!prepared) {
            // Still loading is not a failure. Keep this tap and apply it when the recording is ready.
            pendingTapMs = positionMs
            Toast.makeText(this, R.string.playback_loading_wait, Toast.LENGTH_SHORT).show()
            return
        }
        pendingTapMs = null
        // The service keeps playback alive when this screen is left. Start it before the seek ends.
        PlaybackService.start(this)
        PlaybackSession.seekTo(positionMs, play = true)
        updateProgress()
    }

    private fun attrColor(attr: Int): Int = MaterialColors.getColor(this, attr, Color.BLACK)

    private fun showTab(transcript: Boolean) {
        audioGroup.isVisible = !transcript
        transcriptScroll.isVisible = transcript
        if (transcript) {
            loadTranscript()
        }
    }

    /**
     * Reads the saved transcript and the active state on the IO dispatcher, then shows the panel. A
     * newer load cancels an older one, so a late read cannot overwrite a newer state.
     */
    private fun loadTranscript() {
        val target = uri?.toString() ?: return
        transcriptLoad?.cancel()
        transcriptLoad = lifecycleScope.launch {
            val (panel, found) = withContext(Dispatchers.IO) {
                val record = AsrRuntime.transcripts(this@PlaybackActivity).get(target)
                val phase = TranscriptionScheduler.activePhase(this@PlaybackActivity, target)
                TranscriptPanels.of(record, active = phase != null, phase = phase ?: RunPhase.FULL) to
                    record?.segments.orEmpty()
            }
            showPanel(panel)
            setSegments(found)
        }
    }

    /**
     * Shows the status line, the saved body and the actions. The status never hides the body: a
     * cancelled, failed or running transcript still shows the text saved so far.
     */
    private fun showPanel(panel: TranscriptPanel) {
        shownPanel = panel
        val status = statusText(panel)
        transcriptStatusTextView.isVisible = status != null
        transcriptStatusTextView.text = status.orEmpty()

        // With timed segments, the segment blocks replace the plain text. The copied text is unchanged.
        transcriptTextView.isVisible = panel.hasBody && segments.isEmpty()
        transcriptTextView.text = panel.body

        // Persistent reason when no model can be downloaded in this build. Only where a run was offered.
        val unavailable = panel.canStart &&
            ModelCatalog.default.validateManifest() is ManifestValidationResult.Incomplete
        transcriptBannerTextView.isVisible = unavailable
        transcriptBannerTextView.text = getString(R.string.asr_manifest_incomplete)

        transcriptActionButton.isVisible = panel.canStart
        transcriptActionButton.setText(
            if (panel.state == TranscriptPanelState.NONE || panel.state == TranscriptPanelState.CORRUPT) {
                R.string.transcribe
            } else {
                R.string.transcript_redo
            },
        )
        cancelTranscriptButton.isVisible = panel.canCancel
        copyTranscriptButton.isEnabled = panel.hasBody
        exportTranscriptButton.isEnabled = panel.hasBody
    }

    private fun statusText(panel: TranscriptPanel): String? = when (panel.state) {
        TranscriptPanelState.NONE -> getString(R.string.playback_no_transcript)
        TranscriptPanelState.RUNNING -> when (panel.phase) {
            RunPhase.FULL -> getString(R.string.playback_transcript_running, panel.progressPercent)
            RunPhase.REDUCED -> getString(R.string.playback_transcript_reduced)
            RunPhase.WAITING_COOL -> getString(R.string.playback_transcript_waiting_cool)
            RunPhase.WAITING_RECORDING -> getString(R.string.playback_transcript_waiting_recording)
        }
        TranscriptPanelState.CANCELLED -> getString(R.string.playback_transcript_cancelled)
        TranscriptPanelState.FAILED -> getString(R.string.playback_transcript_failed)
        TranscriptPanelState.INTERRUPTED -> getString(R.string.transcript_interrupted)
        TranscriptPanelState.COMPLETED -> null
        TranscriptPanelState.COMPLETED_EMPTY -> getString(R.string.transcript_no_speech)
        TranscriptPanelState.CORRUPT -> getString(R.string.transcript_corrupt)
    }

    /**
     * Starts a first run, or a redo. A redo replaces a finished transcript, so it asks first. Viewing
     * a result never starts a run; only this button does.
     */
    private fun onTranscriptActionClicked() {
        val target = uri?.toString() ?: return
        val panel = shownPanel
        val replacesResult = panel?.state == TranscriptPanelState.COMPLETED ||
            panel?.state == TranscriptPanelState.COMPLETED_EMPTY
        if (!replacesResult) {
            TranscriptionStarter.start(this, target, recordingTitle)
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.transcript_redo)
            .setMessage(R.string.transcript_redo_confirm)
            .setPositiveButton(R.string.transcript_redo) { _, _ ->
                TranscriptionStarter.start(this, target, recordingTitle)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun copyTranscript() {
        val text = shownPanel?.body?.takeIf { it.isNotBlank() } ?: return
        getSystemService(ClipboardManager::class.java)?.setPrimaryClip(
            ClipData.newPlainText(getString(R.string.transcript_title), text),
        )
        Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
    }

    private fun exportTranscript() {
        val text = shownPanel?.body?.takeIf { it.isNotBlank() } ?: return
        val name = recordingTitle.substringBeforeLast('.', recordingTitle) + ".txt"
        lifecycleScope.launch {
            val saved = withContext(Dispatchers.IO) {
                ExportHelper.saveTextToDownloads(this@PlaybackActivity, name, text)
            }
            Toast.makeText(
                this@PlaybackActivity,
                if (saved != null) R.string.export_done else R.string.export_failed,
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    private fun addMarker(type: MarkerType) {
        if (!prepared) {
            return
        }
        markers.add(Marker(currentPosition(), type))
        markers.sortBy { it.timeMs }
        persistMarkers()
        refreshRows()

        Toast.makeText(
            this,
            if (type == MarkerType.IMPORTANT) {
                R.string.marker_important_toast
            } else {
                R.string.marker_segment_toast
            },
            Toast.LENGTH_SHORT,
        ).show()
    }

    private fun persistMarkers() {
        val source = uri ?: return
        preferences.saveMarkers(source.toString(), markers.toList())
    }

    private fun refreshRows() {
        adapter.submitList(PlaybackRows.build(markers, durationMs))
        rowsRecyclerView.isVisible = markers.isNotEmpty()
        emptyTextView.isVisible = markers.isEmpty()
        refreshTicks()
    }

    private fun refreshTicks() {
        waveformView.ticks = if (durationMs > 0L) {
            markers.map {
                PlaybackWaveformView.Tick(
                    (it.timeMs.toFloat() / durationMs).coerceIn(0f, 1f),
                    it.type,
                )
            }
        } else {
            emptyList()
        }
    }

    private fun onRowClick(row: PlaybackRow) {
        when (row) {
            is PlaybackRow.Chapter -> seekTo(row.startMs)
            is PlaybackRow.Mark -> seekTo(row.marker.timeMs)
        }
    }

    private fun onRowLongClick(row: PlaybackRow) {
        val target = when (row) {
            is PlaybackRow.Chapter -> row.boundaryMs?.let { Marker(it, MarkerType.SEGMENT) }
            is PlaybackRow.Mark -> row.marker
        } ?: return

        val message = if (target.type == MarkerType.SEGMENT) {
            R.string.playback_delete_segment_message
        } else {
            R.string.playback_delete_marker_message
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.playback_delete_marker_title)
            .setMessage(message)
            .setPositiveButton(R.string.delete) { _, _ ->
                markers.remove(target)
                persistMarkers()
                refreshRows()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun displayNameOf(source: Uri): String? = runCatching {
        contentResolver.query(
            source, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull()

    /** Date the recording was added, in seconds since the epoch. */
    private fun dateAddedOf(source: Uri): Long? = runCatching {
        contentResolver.query(
            source, arrayOf(MediaStore.MediaColumns.DATE_ADDED), null, null, null
        )?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null
        }
    }.getOrNull()

    companion object {
        const val EXTRA_URI = "extra_uri"
        const val EXTRA_TITLE = "extra_title"

        /** Open on the transcript tab (used by result notifications). */
        const val EXTRA_SHOW_TRANSCRIPT = "extra_show_transcript"

        private const val SKIP_MS = 10_000L
        private const val TICK_MS = 200L
        private const val WAVEFORM_BINS = 240
        private const val KEY_SPEED = "playback_speed"
        private const val KEY_KEEP_SCREEN_ON = "keep_screen_on"
        private const val SPEED_MIN = 0.5f
        private const val SPEED_MAX = 3f
        private const val SPEED_EPSILON = 0.001f
        private val SPEED_SHORTCUTS = floatArrayOf(0.5f, 1f, 1.5f, 2f, 2.5f, 3f)
    }
}
