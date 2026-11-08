/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.recorder.utils

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.ByteOrder
import kotlin.math.abs

/**
 * Computes waveform peaks for playback. WAV is read directly, other formats (m4a) are
 * decoded with MediaCodec. Nothing is cached on disk.
 */
object WaveformLoader {
    private const val TIMEOUT_US = 10_000L

    suspend fun load(
        context: Context,
        uri: Uri,
        isWav: Boolean,
        durationMs: Long,
        bins: Int,
    ): FloatArray? = withContext(Dispatchers.IO) {
        runCatching {
            if (isWav) {
                AudioEditor.computePeaks({
                    context.contentResolver.openInputStream(uri)
                        ?: throw IOException("Cannot open recording")
                }, bins)
            } else {
                decodePeaks(context, uri, bins, durationMs)
            }
        }.getOrNull()
    }

    private fun decodePeaks(context: Context, uri: Uri, bins: Int, durationMs: Long): FloatArray? {
        val descriptor = context.contentResolver.openFileDescriptor(uri, "r") ?: return null
        descriptor.use { pfd ->
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(pfd.fileDescriptor)
                val trackIndex = (0 until extractor.trackCount).firstOrNull {
                    extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)
                        ?.startsWith("audio/") == true
                } ?: return null
                extractor.selectTrack(trackIndex)

                val format = extractor.getTrackFormat(trackIndex)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
                val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                val totalFrames = (durationMs * sampleRate / 1000L).coerceAtLeast(1L)

                val peaks = FloatArray(bins)
                val codec = MediaCodec.createDecoderByType(mime)
                try {
                    codec.configure(format, null, null, 0)
                    codec.start()

                    val info = MediaCodec.BufferInfo()
                    var inputDone = false
                    var outputDone = false
                    var frames = 0L

                    while (!outputDone) {
                        if (!inputDone) {
                            val inIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                            if (inIndex >= 0) {
                                val buffer = codec.getInputBuffer(inIndex) ?: continue
                                val size = extractor.readSampleData(buffer, 0)
                                if (size < 0) {
                                    codec.queueInputBuffer(
                                        inIndex, 0, 0, 0,
                                        MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                    )
                                    inputDone = true
                                } else {
                                    codec.queueInputBuffer(
                                        inIndex, 0, size, extractor.sampleTime, 0
                                    )
                                    extractor.advance()
                                }
                            }
                        }

                        val outIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                        if (outIndex >= 0) {
                            val out = codec.getOutputBuffer(outIndex)
                            if (out != null && info.size > 0) {
                                out.position(info.offset)
                                out.limit(info.offset + info.size)
                                val samples = out.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                                var k = 0L
                                while (samples.hasRemaining()) {
                                    val frameIndex = frames + k / channels
                                    val bin = (frameIndex * bins / totalFrames)
                                        .toInt().coerceIn(0, bins - 1)
                                    val value = abs(samples.get().toInt()) / 32768f
                                    if (value > peaks[bin]) {
                                        peaks[bin] = value
                                    }
                                    k++
                                }
                                frames += k / channels
                            }
                            codec.releaseOutputBuffer(outIndex, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                outputDone = true
                            }
                        }
                    }
                    return peaks
                } finally {
                    codec.stop()
                    codec.release()
                }
            } finally {
                extractor.release()
            }
        }
    }
}
