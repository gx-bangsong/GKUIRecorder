/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.recorder.engine

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.ParcelFileDescriptor
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Decodes a recording to mono 16 kHz float samples. 16-bit PCM WAV is read directly,
 * other formats (m4a/AAC) are decoded with MediaCodec.
 */
object AudioDecoder {
    private const val TARGET_RATE = 16_000
    private const val TIMEOUT_US = 10_000L

    fun decodeTo16k(pfd: ParcelFileDescriptor): FloatArray {
        val fd = pfd.fileDescriptor
        // Not closed on purpose: the descriptor belongs to the caller
        val stream = FileInputStream(fd)
        val header = ByteArray(12)
        val isWav = readFully(stream, header) && String(header, 0, 4, Charsets.US_ASCII) == "RIFF" &&
            String(header, 8, 4, Charsets.US_ASCII) == "WAVE"
        return if (isWav) {
            val wav = readWav(stream)
            resample(wav.mono, wav.sampleRate)
        } else {
            stream.channel.position(0)
            val size = pfd.statSize
            val (mono, rate) = decodeWithCodec(fd, size)
            resample(mono, rate)
        }
    }

    private class Wav(val mono: FloatArray, val sampleRate: Int)

    private fun readWav(input: InputStream): Wav {
        var format = 0
        var channels = 0
        var sampleRate = 0
        var bits = 0
        while (true) {
            val chunk = ByteArray(8)
            if (!readFully(input, chunk)) error("Invalid WAV file")
            val id = String(chunk, 0, 4, Charsets.US_ASCII)
            val size = le32(chunk, 4)
            when (id) {
                "fmt " -> {
                    val fmt = ByteArray(size)
                    if (!readFully(input, fmt)) error("Invalid WAV fmt chunk")
                    format = le16(fmt, 0)
                    channels = le16(fmt, 2)
                    sampleRate = le32(fmt, 4)
                    bits = le16(fmt, 14)
                    if (size % 2 == 1) input.skip(1)
                }
                "data" -> {
                    require(format == 1 && bits == 16) { "Only 16-bit PCM WAV is supported" }
                    // Streamed WAV files may carry a zero or bogus size: read to the end then
                    val bytes = if (size <= 0) input.readBytes() else ByteArray(size).also {
                        if (!readFully(input, it)) error("Truncated WAV data")
                    }
                    return Wav(downmix(bytes, channels), sampleRate)
                }
                else -> {
                    val skip = size + (size and 1)
                    if (input.skip(skip.toLong()) < skip) error("Invalid WAV chunk")
                }
            }
        }
    }

    private fun decodeWithCodec(fd: java.io.FileDescriptor, length: Long): Pair<FloatArray, Int> {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        val out = FloatBuilder()
        try {
            if (length > 0) extractor.setDataSource(fd, 0, length) else extractor.setDataSource(fd)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: error("No audio track")
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: error("Unknown audio type")
            val decoder = MediaCodec.createDecoderByType(mime)
            codec = decoder
            decoder.configure(format, null, null, 0)
            decoder.start()

            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var inputDone = false
            var outputDone = false
            val info = MediaCodec.BufferInfo()
            while (!outputDone) {
                if (!inputDone) {
                    val index = decoder.dequeueInputBuffer(TIMEOUT_US)
                    if (index >= 0) {
                        val buffer = decoder.getInputBuffer(index) ?: continue
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                when (val index = decoder.dequeueOutputBuffer(info, TIMEOUT_US)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        rate = decoder.outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = decoder.outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    }
                    MediaCodec.INFO_TRY_AGAIN_LATER, MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit
                    else -> if (index >= 0) {
                        val buffer = decoder.getOutputBuffer(index)
                        if (buffer != null && info.size > 0) {
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            out.add(downmix(buffer.slice().order(ByteOrder.LITTLE_ENDIAN), channels))
                        }
                        decoder.releaseOutputBuffer(index, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            outputDone = true
                        }
                    }
                }
            }
            return out.toArray() to rate
        } catch (e: IllegalStateException) {
            throw IOException("Cannot decode the recording", e)
        } finally {
            runCatching { codec?.stop() }
            codec?.release()
            extractor.release()
        }
    }

    private fun downmix(bytes: ByteArray, channels: Int): FloatArray =
        downmix(ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN), channels)

    private fun downmix(buffer: ByteBuffer, channels: Int): FloatArray {
        val shorts = buffer.asShortBuffer()
        val frames = shorts.remaining() / channels
        return FloatArray(frames) { frame ->
            var sum = 0f
            for (c in 0 until channels) sum += shorts.get(frame * channels + c)
            sum / channels / 32768f
        }
    }

    /** Linear resampling; good enough for speech recognition input. */
    private fun resample(input: FloatArray, rate: Int): FloatArray {
        if (rate == TARGET_RATE || input.isEmpty()) return input
        val outLength = (input.size.toLong() * TARGET_RATE / rate).toInt()
        val ratio = rate.toDouble() / TARGET_RATE
        return FloatArray(outLength) { i ->
            val pos = i * ratio
            val idx = pos.toInt()
            val frac = (pos - idx).toFloat()
            val a = input[idx.coerceAtMost(input.lastIndex)]
            val b = input[(idx + 1).coerceAtMost(input.lastIndex)]
            a + (b - a) * frac
        }
    }

    private fun readFully(input: InputStream, buffer: ByteArray): Boolean {
        var offset = 0
        while (offset < buffer.size) {
            val read = input.read(buffer, offset, buffer.size - offset)
            if (read < 0) return false
            offset += read
        }
        return true
    }

    private fun le16(b: ByteArray, o: Int) = (b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8)

    private fun le32(b: ByteArray, o: Int) = le16(b, o) or (le16(b, o + 2) shl 16)

    /** Growable float buffer for decoded samples. */
    private class FloatBuilder {
        private var data = FloatArray(TARGET_RATE)
        private var size = 0

        fun add(chunk: FloatArray) {
            if (size + chunk.size > data.size) {
                data = data.copyOf(maxOf(data.size * 2, size + chunk.size))
            }
            chunk.copyInto(data, size)
            size += chunk.size
        }

        fun toArray(): FloatArray = data.copyOf(size)
    }
}
