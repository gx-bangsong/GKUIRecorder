/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.recorder

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.PlaybackParams
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
    private var control: PlaybackControl? = null
    private var appContext: Context? = null

    /** The user's loop choice for the open recording. Cleared when another recording opens or on stop. */
    private var loopRequested = false
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

    /** Position to show: the newest seek target while a seek is in flight, else the real position. */
    fun positionMs(): Long = control?.displayPositionMs() ?: 0L

    /**
     * Seeks the open recording. Returns false when it is not prepared yet, so the caller can keep
     * the request and apply it later. The last request wins; [play] starts playback when the seek
     * completes.
     */
    fun seekTo(positionMs: Long, play: Boolean): Boolean {
        val control = control ?: return false
        if (!prepared) return false
        control.seekTo(positionMs, play)
        return true
    }

    val looping: Boolean
        get() = loopRequested

    /**
     * Turns repeat-this-recording on or off. Allowed before the player is prepared: the choice is
     * kept and applied when it is. It never starts or stops playback.
     */
    fun setLooping(enabled: Boolean) {
        loopRequested = enabled
        control?.setLooping(enabled)
        notifyListeners()
    }

    fun setSpeed(speed: Float) {
        if (prepared) control?.setSpeed(speed)
    }

    /**
     * Opens [source] for playback. Returns false if it cannot be opened. Reopening the recording
     * that is already open keeps its state, so a rotation does not restart playback.
     */
    fun open(context: Context, source: Uri, title: String): Boolean {
        if (source == uri && mediaPlayer != null && !loadFailed) {
            return true
        }
        if (source != uri) {
            loopRequested = false
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
                    control?.onPrepared()
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
            player.setOnSeekCompleteListener { mp ->
                if (mediaPlayer === mp) {
                    control?.onSeekComplete()
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
        control = PlaybackControl(
            port = MediaPlayerPort(player),
            durationMs = { durationMs },
            onChanged = { notifyListeners() },
            initialLooping = loopRequested,
        )
        uri = source
        this.title = title
        prepared = false
        durationMs = 0L
        loadFailed = false
        notifyListeners()
        return true
    }

    fun play() {
        val control = control ?: return
        if (!prepared || isPlaying()) {
            return
        }
        control.play()
    }

    fun pause() {
        val control = control ?: return
        if (prepared) {
            control.pause()
        } else {
            notifyListeners()
        }
    }

    fun toggle() {
        if (isPlaying()) pause() else play()
    }

    /** Stops playback, releases the player and closes the recording. */
    fun stop() {
        releasePlayer()
        loopRequested = false
        notifyListeners()
    }

    private fun releasePlayer() {
        val player = mediaPlayer ?: return
        mediaPlayer = null
        control = null
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

    /** MediaPlayer behind [PlayerPort]. Calls that the player rejects in its current state are ignored. */
    private class MediaPlayerPort(private val player: MediaPlayer) : PlayerPort {
        override val isPlaying: Boolean
            get() = runCatching { player.isPlaying }.getOrDefault(false)

        override val positionMs: Long
            get() = runCatching { player.currentPosition.toLong() }.getOrDefault(0L)

        override fun seekTo(positionMs: Long) {
            // Closest frame, not the previous sync point: the highlight follows the real position
            runCatching { player.seekTo(positionMs, MediaPlayer.SEEK_CLOSEST) }
        }

        override fun start() {
            runCatching { player.start() }
        }

        override fun pause() {
            runCatching { player.pause() }
        }

        override fun setSpeed(speed: Float) {
            // Keep the paused or playing state as it was. See PlaybackControl.setSpeed.
            runCatching { player.playbackParams = PlaybackParams().setSpeed(speed) }
        }

        override var looping: Boolean
            get() = runCatching { player.isLooping }.getOrDefault(false)
            set(value) {
                runCatching { player.isLooping = value }
            }
    }
}
