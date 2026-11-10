/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.recorder

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import java.io.IOException
import java.util.concurrent.CopyOnWriteArraySet

/**
 * The recording being played, kept for the whole process so playback continues when the
 * playback screen is left. [PlaybackActivity] and [PlaybackService] both use it. Everything here
 * runs on the main thread, where [MediaPlayer] callbacks are delivered.
 */
object PlaybackSession {
    private var mediaPlayer: MediaPlayer? = null
    private var appContext: Context? = null
    private val listeners = CopyOnWriteArraySet<() -> Unit>()

    /** The open recording, or null when nothing is loaded. */
    var uri: Uri? = null
        private set

    /** Shown in the notification. */
    var title: String = ""
        private set

    var prepared: Boolean = false
        private set

    var durationMs: Long = 0L
        private set

    /** True when the open recording could not be prepared or played. */
    var loadFailed: Boolean = false
        private set

    val player: MediaPlayer?
        get() = mediaPlayer

    val isOpen: Boolean
        get() = mediaPlayer != null

    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    fun isPlaying(): Boolean = prepared && mediaPlayer?.isPlaying == true

    /**
     * Opens [source] for playback. Returns false if it cannot be opened. Reopening the recording
     * that is already open keeps its state, so a rotation does not restart playback.
     */
    fun open(context: Context, source: Uri, title: String): Boolean {
        if (source == uri && mediaPlayer != null && !loadFailed) {
            return true
        }
        releasePlayer()
        val app = context.applicationContext
        appContext = app
        val player = MediaPlayer()
        try {
            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            player.setDataSource(app, source)
            player.setOnPreparedListener { mp ->
                if (mediaPlayer === mp) {
                    prepared = true
                    durationMs = mp.duration.toLong().coerceAtLeast(0L)
                    notifyListeners()
                }
            }
            player.setOnCompletionListener { mp ->
                if (mediaPlayer === mp) {
                    pause()
                    mp.seekTo(0)
                    notifyListeners()
                }
            }
            player.setOnErrorListener { mp, _, _ ->
                if (mediaPlayer === mp) {
                    loadFailed = true
                    notifyListeners()
                }
                true
            }
            player.prepareAsync()
        } catch (e: IOException) {
            player.release()
            return false
        } catch (e: IllegalArgumentException) {
            player.release()
            return false
        } catch (e: SecurityException) {
            player.release()
            return false
        }
        mediaPlayer = player
        uri = source
        this.title = title
        prepared = false
        durationMs = 0L
        loadFailed = false
        notifyListeners()
        return true
    }

    fun play() {
        val player = mediaPlayer ?: return
        if (!prepared || player.isPlaying) {
            return
        }
        player.start()
        notifyListeners()
    }

    fun pause() {
        val player = mediaPlayer ?: return
        if (prepared && player.isPlaying) {
            player.pause()
        }
        notifyListeners()
    }

    fun toggle() {
        if (isPlaying()) pause() else play()
    }

    /** Stops playback, releases the player and closes the recording. */
    fun stop() {
        releasePlayer()
        notifyListeners()
    }

    private fun releasePlayer() {
        val player = mediaPlayer ?: return
        mediaPlayer = null
        uri = null
        prepared = false
        durationMs = 0L
        loadFailed = false
        runCatching { player.stop() }
        player.release()
    }

    private fun notifyListeners() {
        listeners.forEach { it() }
    }
}
