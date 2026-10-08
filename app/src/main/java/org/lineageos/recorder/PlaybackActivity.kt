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
import android.provider.OpenableColumns
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import org.lineageos.recorder.models.Marker
import org.lineageos.recorder.models.MarkerType
import org.lineageos.recorder.playback.PlaybackAdapter
import org.lineageos.recorder.playback.PlaybackRow
import org.lineageos.recorder.playback.PlaybackRows
import org.lineageos.recorder.ui.PlaybackWaveformView
import org.lineageos.recorder.utils.PreferencesManager
import org.lineageos.recorder.utils.WaveformLoader
import java.io.IOException

/**
 * Player with waveform, markers and chapters (split by segment markers).
 */
class PlaybackActivity : AppCompatActivity(R.layout.activity_playback) {
    // Views
    private val toolbar by lazy { findViewById<Toolbar>(R.id.toolbar) }
    private val timeTextView by lazy { findViewById<TextView>(R.id.timeTextView) }
    private val waveformView by lazy { findViewById<PlaybackWaveformView>(R.id.waveformView) }
    private val addImportantButton by lazy { findViewById<MaterialButton>(R.id.addImportantButton) }
    private val addSegmentButton by lazy { findViewById<MaterialButton>(R.id.addSegmentButton) }
    private val rewindButton by lazy { findViewById<MaterialButton>(R.id.rewindButton) }
    private val forwardButton by lazy { findViewById<MaterialButton>(R.id.forwardButton) }
    private val speedButton by lazy { findViewById<MaterialButton>(R.id.speedButton) }
    private val playPauseImageView by lazy { findViewById<ImageView>(R.id.playPauseImageView) }
    private val emptyTextView by lazy { findViewById<TextView>(R.id.emptyTextView) }
    private val rowsRecyclerView by lazy { findViewById<RecyclerView>(R.id.rowsRecyclerView) }

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

        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        recordingTitle = intent.getStringExtra(EXTRA_TITLE)
            ?: displayNameOf(parsed)
            ?: getString(R.string.sound_record_default_name)
        title = recordingTitle

        markers.addAll(preferences.getMarkers(parsed.toString()))

        rowsRecyclerView.layoutManager = LinearLayoutManager(this)
        rowsRecyclerView.adapter = adapter

        waveformView.onSeek = { fraction -> seekTo((fraction * durationMs).toLong()) }
        addImportantButton.setOnClickListener { addMarker(MarkerType.IMPORTANT) }
        addSegmentButton.setOnClickListener { addMarker(MarkerType.SEGMENT) }
        playPauseImageView.setOnClickListener { togglePlay() }
        rewindButton.setOnClickListener { seekTo(currentPosition() - SKIP_MS) }
        forwardButton.setOnClickListener { seekTo(currentPosition() + SKIP_MS) }
        speedButton.setOnClickListener { cycleSpeed() }

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
        durationMs = mediaPlayer.duration.toLong()
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
        playPauseImageView.setImageResource(R.drawable.ic_pause)
        playPauseImageView.contentDescription = getString(R.string.pause)
        handler.removeCallbacks(ticker)
        handler.post(ticker)
    }

    private fun pausePlayback() {
        val mediaPlayer = player
        if (mediaPlayer != null && prepared && mediaPlayer.isPlaying) {
            mediaPlayer.pause()
        }
        playPauseImageView.setImageResource(R.drawable.ic_play_arrow)
        playPauseImageView.contentDescription = getString(R.string.play)
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
        val position = currentPosition()
        timeTextView.text = getString(
            R.string.playback_time,
            PlaybackRows.formatTime(position),
            PlaybackRows.formatTime(durationMs),
        )
        waveformView.progress = if (durationMs > 0L) {
            position.toFloat() / durationMs
        } else {
            0f
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

    companion object {
        const val EXTRA_URI = "extra_uri"
        const val EXTRA_TITLE = "extra_title"

        private const val SKIP_MS = 10_000L
        private const val TICK_MS = 200L
        private const val WAVEFORM_BINS = 240
        private val SPEEDS = floatArrayOf(1f, 1.5f, 2f, 0.75f)
    }
}
