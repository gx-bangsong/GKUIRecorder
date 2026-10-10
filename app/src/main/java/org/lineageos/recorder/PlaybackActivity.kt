/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.recorder

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.text.format.DateUtils
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.slider.Slider
import kotlinx.coroutines.launch
import org.lineageos.recorder.asr.AsrRuntime
import org.lineageos.recorder.asr.transcription.TranscriptRecord
import org.lineageos.recorder.asr.transcription.TranscriptStatus
import org.lineageos.recorder.models.Marker
import org.lineageos.recorder.models.MarkerType
import org.lineageos.recorder.playback.PlaybackAdapter
import org.lineageos.recorder.playback.PlaybackRow
import org.lineageos.recorder.playback.PlaybackRows
import org.lineageos.recorder.ui.PlaybackWaveformView
import org.lineageos.recorder.utils.PreferencesManager
import org.lineageos.recorder.utils.WaveformLoader
import java.io.IOException
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
    private val transcriptScroll by lazy { findViewById<View>(R.id.transcriptScroll) }
    private val transcriptTextView by lazy { findViewById<TextView>(R.id.transcriptTextView) }
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

    private var player: MediaPlayer? = null
    private var uri: Uri? = null
    private var recordingTitle = ""
    private var prepared = false
    private var seeking = false
    private var durationMs = 0L
    private val markers = mutableListOf<Marker>()
    private var speedIndex = 0

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
        speedButton.setOnClickListener { cycleSpeed() }

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

        loadPlayer(parsed)
        refreshRows()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onPause() {
        pausePlayback()
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        player?.release()
        player = null
        super.onDestroy()
    }

    private fun loadPlayer(source: Uri) {
        val mediaPlayer = MediaPlayer()
        try {
            mediaPlayer.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            mediaPlayer.setDataSource(this, source)
            mediaPlayer.setOnPreparedListener { onPrepared(it) }
            mediaPlayer.setOnCompletionListener {
                pausePlayback()
                seekTo(0L)
            }
            mediaPlayer.setOnErrorListener { _, _, _ ->
                Toast.makeText(this, R.string.playback_load_failed, Toast.LENGTH_SHORT).show()
                true
            }
            mediaPlayer.prepareAsync()
            player = mediaPlayer
        } catch (e: IOException) {
            mediaPlayer.release()
            Toast.makeText(this, R.string.playback_load_failed, Toast.LENGTH_SHORT).show()
            finish()
        } catch (e: IllegalArgumentException) {
            mediaPlayer.release()
            Toast.makeText(this, R.string.playback_load_failed, Toast.LENGTH_SHORT).show()
            finish()
        } catch (e: SecurityException) {
            mediaPlayer.release()
            Toast.makeText(this, R.string.playback_load_failed, Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun onPrepared(mediaPlayer: MediaPlayer) {
        prepared = true
        durationMs = mediaPlayer.duration.toLong().coerceAtLeast(0L)
        // The slider range must be valid before its value is set
        progressSlider.valueFrom = 0f
        progressSlider.valueTo = max(durationMs.toFloat(), 1f)
        progressSlider.isEnabled = durationMs > 0L
        updateProgress()
        refreshRows()
        loadWaveform()
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
        val mediaPlayer = player ?: return
        if (!prepared) {
            return
        }
        if (mediaPlayer.isPlaying) {
            pausePlayback()
        } else {
            startPlayback()
        }
    }

    private fun startPlayback() {
        val mediaPlayer = player ?: return
        mediaPlayer.start()
        playPauseButton.setImageResource(R.drawable.ic_pause)
        playPauseButton.contentDescription = getString(R.string.pause)
        handler.removeCallbacks(ticker)
        handler.post(ticker)
    }

    private fun pausePlayback() {
        val mediaPlayer = player
        if (mediaPlayer != null && prepared && mediaPlayer.isPlaying) {
            mediaPlayer.pause()
        }
        playPauseButton.setImageResource(R.drawable.ic_play_arrow)
        playPauseButton.contentDescription = getString(R.string.play)
        handler.removeCallbacks(ticker)
        updateProgress()
    }

    private fun currentPosition(): Long {
        val mediaPlayer = player ?: return 0L
        return if (prepared) mediaPlayer.currentPosition.toLong() else 0L
    }

    private fun seekTo(positionMs: Long) {
        val mediaPlayer = player ?: return
        if (!prepared) {
            return
        }
        mediaPlayer.seekTo(positionMs.coerceIn(0L, durationMs).toInt())
        updateProgress()
    }

    private fun cycleSpeed() {
        speedIndex = (speedIndex + 1) % SPEEDS.size
        val speed = SPEEDS[speedIndex]
        speedButton.text = if (speed % 1f == 0f) "${speed.toInt()}x" else "${speed}x"

        val mediaPlayer = player ?: return
        if (prepared) {
            try {
                mediaPlayer.playbackParams = PlaybackParams().setSpeed(speed)
            } catch (e: IllegalStateException) {
                // Speed is not supported in the current state; keep the previous one
            }
        }
    }

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
    }

    private fun showTab(transcript: Boolean) {
        audioGroup.isVisible = !transcript
        transcriptScroll.isVisible = transcript
        if (transcript) {
            val record = uri?.let { AsrRuntime.transcripts(this).get(it.toString()) }
            transcriptTextView.text = transcriptText(record)
        }
    }

    /** What the transcript tab shows for the stored record. Only the record's own fields are used. */
    private fun transcriptText(record: TranscriptRecord?): String {
        if (record == null) {
            return getString(R.string.playback_no_transcript)
        }
        return when (record.status) {
            TranscriptStatus.COMPLETED -> record.text.ifBlank { getString(R.string.transcript_empty) }
            TranscriptStatus.QUEUED, TranscriptStatus.RUNNING ->
                getString(R.string.playback_transcript_running, (record.progress * 100).toInt())
            TranscriptStatus.FAILED -> getString(R.string.playback_transcript_failed)
            TranscriptStatus.CANCELLED -> getString(R.string.playback_transcript_cancelled)
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

        private const val SKIP_MS = 10_000L
        private const val TICK_MS = 200L
        private const val WAVEFORM_BINS = 240
        private val SPEEDS = floatArrayOf(1f, 1.5f, 2f, 0.75f)
    }
}
