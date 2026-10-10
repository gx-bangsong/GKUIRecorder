/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.recorder.asr.audio

import android.content.ContentResolver
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Streams a recording as 16 kHz mono float PCM in fixed-size chunks. Never holds the whole file.
 *
 * 16-bit PCM WAV (the app's own recordings) is read directly; anything else goes through
 * MediaExtractor + MediaCodec (m4a/AAC). Both run on the caller's background thread.
 */
class PcmSource private constructor(
    /** Duration of the source in milliseconds, or 0 when the container does not say. */
    val durationMs: Long,
    private val reader: (PcmSink) -> Unit,
) {
    /**
     * Decodes the whole source and calls [onChunk] with consecutive 0.5 s chunks of 16 kHz mono
     * samples. [onChunk] receives the chunk and the end time of its last sample in milliseconds.
     * Exceptions thrown by [onChunk] abort decoding and propagate to the caller.
     */
    fun decode(onChunk: (samples: FloatArray, endMs: Long) -> Unit) {
        val chunk = FloatArray(CHUNK_SAMPLES)
        var chunkCount = 0
        var emittedSamples = 0L
        var resampler: StreamingResampler? = null
        var resamplerRate = -1
        var mono = FloatArray(0)

        fun pushOne(value: Float) {
            chunk[chunkCount++] = value
            if (chunkCount == CHUNK_SAMPLES) {
                emittedSamples += chunkCount
                onChunk(chunk.copyOf(), emittedSamples * 1000L / TARGET_RATE)
                chunkCount = 0
            }
        }

        reader(PcmSink { pcm, frames, channels, sampleRate ->
            if (resampler == null || resamplerRate != sampleRate) {
                resampler = StreamingResampler(sampleRate, TARGET_RATE)
                resamplerRate = sampleRate
            }
            if (mono.size < frames) {
                mono = FloatArray(frames)
            }
            Downmix.pcm16ToMono(pcm, frames, channels, mono)
            resampler!!.process(mono, frames) { out, count ->
                for (i in 0 until count) {
                    pushOne(out[i])
                }
            }
        })
        resampler?.flush { out, count ->
            for (i in 0 until count) {
                pushOne(out[i])
            }
        }
        if (chunkCount > 0) {
            emittedSamples += chunkCount
            onChunk(chunk.copyOf(chunkCount), emittedSamples * 1000L / TARGET_RATE)
        }
    }

    fun interface PcmSink {
        fun onPcm(pcm: ShortArray, frames: Int, channels: Int, sampleRate: Int)
    }

    companion object {
        const val TARGET_RATE = 16_000
        const val CHUNK_SAMPLES = TARGET_RATE / 2
        private const val CODEC_TIMEOUT_US = 10_000L

        /** Opens [uri] for streaming. Throws [IOException] when it cannot be read. */
        fun open(resolver: ContentResolver, uri: Uri): PcmSource {
            val wav = resolver.openInputStream(uri)?.use { WavParser.parse(it) }
            if (wav != null && wav.isPcm16) {
                return PcmSource(wav.durationMs) { sink -> readWav(resolver, uri, sink) }
            }
            val duration = codecDurationMs(resolver, uri)
            return PcmSource(duration) { sink -> decodeWithCodec(resolver, uri, sink) }
        }

        private fun readWav(resolver: ContentResolver, uri: Uri, sink: PcmSink) {
            resolver.openInputStream(uri)!!.use { input ->
                val info = WavParser.parse(input) ?: throw IOException("Not a WAV file")
                val frameBytes = info.channels * 2
                val frames = 4096
                val bytes = ByteArray(frames * frameBytes)
                val pcm = ShortArray(frames * info.channels)
                var remaining = info.dataSize
                while (remaining >= frameBytes) {
                    val want = minOf(bytes.size.toLong(), remaining - remaining % frameBytes).toInt()
                    var filled = 0
                    while (filled < want) {
                        val n = input.read(bytes, filled, want - filled)
                        if (n < 0) {
                            break
                        }
                        filled += n
                    }
                    if (filled < frameBytes) {
                        break
                    }
                    val usableFrames = filled / frameBytes
                    ByteBuffer.wrap(bytes, 0, usableFrames * frameBytes)
                        .order(ByteOrder.LITTLE_ENDIAN)
                        .asShortBuffer()
                        .get(pcm, 0, usableFrames * info.channels)
                    sink.onPcm(pcm, usableFrames, info.channels, info.sampleRate)
                    remaining -= usableFrames * frameBytes
                    if (filled < want) {
                        break
                    }
                }
            }
        }

        private fun codecDurationMs(resolver: ContentResolver, uri: Uri): Long {
            val descriptor = resolver.openFileDescriptor(uri, "r") ?: return 0L
            descriptor.use { pfd ->
                val extractor = MediaExtractor()
                try {
                    extractor.setDataSource(pfd.fileDescriptor)
                    val index = audioTrack(extractor)
                    if (index < 0) {
                        return 0L
                    }
                    val format = extractor.getTrackFormat(index)
                    return if (format.containsKey(MediaFormat.KEY_DURATION)) {
                        format.getLong(MediaFormat.KEY_DURATION) / 1000L
                    } else {
                        0L
                    }
                } finally {
                    extractor.release()
                }
            }
        }

        private fun audioTrack(extractor: MediaExtractor): Int {
            for (i in 0 until extractor.trackCount) {
                val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    return i
                }
            }
            return -1
        }

        private fun decodeWithCodec(resolver: ContentResolver, uri: Uri, sink: PcmSink) {
            val descriptor = resolver.openFileDescriptor(uri, "r") ?: throw IOException("Cannot open recording")
            descriptor.use { pfd ->
                val extractor = MediaExtractor()
                var codec: MediaCodec? = null
                try {
                    extractor.setDataSource(pfd.fileDescriptor)
                    val track = audioTrack(extractor)
                    if (track < 0) {
                        throw IOException("No audio track")
                    }
                    extractor.selectTrack(track)
                    val format = extractor.getTrackFormat(track)
                    val mime = format.getString(MediaFormat.KEY_MIME) ?: throw IOException("No mime")
                    val decoder = MediaCodec.createDecoderByType(mime)
                    codec = decoder
                    decoder.configure(format, null, null, 0)
                    decoder.start()

                    var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    var inputDone = false
                    var outputDone = false
                    val info = MediaCodec.BufferInfo()
                    while (!outputDone) {
                        if (!inputDone) {
                            val inIndex = decoder.dequeueInputBuffer(CODEC_TIMEOUT_US)
                            if (inIndex >= 0) {
                                val buffer = decoder.getInputBuffer(inIndex)!!
                                val size = extractor.readSampleData(buffer, 0)
                                if (size < 0) {
                                    decoder.queueInputBuffer(
                                        inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                                    )
                                    inputDone = true
                                } else {
                                    decoder.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                                    extractor.advance()
                                }
                            }
                        }
                        when (val outIndex = decoder.dequeueOutputBuffer(info, CODEC_TIMEOUT_US)) {
                            MediaCodec.INFO_TRY_AGAIN_LATER, MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit
                            MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                                val out = decoder.outputFormat
                                channels = out.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                                rate = out.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            }
                            else -> if (outIndex >= 0) {
                                if (info.size > 0) {
                                    val out = decoder.getOutputBuffer(outIndex)!!
                                    out.position(info.offset)
                                    out.limit(info.offset + info.size)
                                    val shorts = out.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                                    val pcm = ShortArray(shorts.remaining())
                                    shorts.get(pcm)
                                    val frames = pcm.size / channels
                                    sink.onPcm(pcm, frames, channels, rate)
                                }
                                decoder.releaseOutputBuffer(outIndex, false)
                                if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                    outputDone = true
                                }
                            }
                        }
                    }
                } finally {
                    // Native codec and extractor are released on every path, including exceptions
                    runCatching { codec?.stop() }
                    runCatching { codec?.release() }
                    extractor.release()
                }
            }
        }
    }
}
