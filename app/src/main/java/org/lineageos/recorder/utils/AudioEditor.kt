/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.recorder.utils

import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Streaming editor for 16-bit PCM WAV files (what the high-quality recorder produces).
 *
 * Supported operations: trim/cut, noise reduction (high-pass + noise gate) and volume boost.
 * The file is processed block by block, so long recordings never need to fit in memory.
 */
object AudioEditor {
    data class Options(
        val trimStartMs: Long = 0L,
        val trimEndMs: Long = Long.MAX_VALUE,
        val gainDb: Float = 0f,
        val denoise: Boolean = false,
    )

    private const val BYTES_PER_SAMPLE = 2
    private const val BLOCK_FRAMES = 1024
    private const val HIGH_PASS_CUTOFF_HZ = 80.0
    private const val GATE_REDUCTION = 0.15f
    private const val GATE_SMOOTHING = 0.5f
    private const val MIN_GATE_THRESHOLD = 150f
    private const val MAX_MS = 1_000_000_000_000L

    private class Header(val sampleRate: Int, val channels: Int, val dataSize: Long) {
        val frameBytes: Int
            get() = channels * BYTES_PER_SAMPLE

        val totalFrames: Long
            get() = dataSize / frameBytes
    }

    /**
     * Edits the WAV read from [openInput] and writes the result to [output].
     * [openInput] may be called twice (once for noise analysis when denoising).
     *
     * @throws IllegalArgumentException if the input is not 16-bit PCM WAV.
     */
    fun edit(openInput: () -> InputStream, output: OutputStream, options: Options) {
        val gateThreshold = if (options.denoise) {
            openInput().use { measureGateThreshold(it) }
        } else {
            0f
        }

        openInput().use { input ->
            val header = readHeader(input)
            val rate = header.sampleRate

            val startFrame = min(msToFrames(options.trimStartMs, rate), header.totalFrames)
            val endFrame = max(
                startFrame,
                min(msToFrames(options.trimEndMs, rate), header.totalFrames),
            )
            skipFully(input, startFrame * header.frameBytes)

            val frames = endFrame - startFrame
            writeWavHeader(output, rate, header.channels, frames * header.frameBytes)

            val processor = BlockProcessor(
                header.channels, rate, options.gainDb, options.denoise, gateThreshold,
            )
            val buffer = ByteArray(BLOCK_FRAMES * header.frameBytes)
            val byteBuffer = ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN)

            var remaining = frames
            while (remaining > 0) {
                val n = min(BLOCK_FRAMES.toLong(), remaining).toInt()
                val bytes = n * header.frameBytes
                readFully(input, buffer, bytes)
                processor.process(byteBuffer, n)
                output.write(buffer, 0, bytes)
                remaining -= n
            }
        }
    }

    /**
     * Estimates the gate threshold from the loudness distribution of the file:
     * the quietest 15% of blocks are treated as background noise.
     */
    private fun measureGateThreshold(input: InputStream): Float {
        val header = readHeader(input)
        val buffer = ByteArray(BLOCK_FRAMES * header.frameBytes)
        val byteBuffer = ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN)
        val levels = ArrayList<Float>()

        var remaining = header.totalFrames
        while (remaining > 0) {
            val n = min(BLOCK_FRAMES.toLong(), remaining).toInt()
            readFully(input, buffer, n * header.frameBytes)

            val count = n * header.channels
            var sumSq = 0.0
            for (i in 0 until count) {
                val s = byteBuffer.getShort(i * BYTES_PER_SAMPLE).toDouble()
                sumSq += s * s
            }
            levels.add(sqrt(sumSq / count).toFloat())
            remaining -= n
        }

        if (levels.isEmpty()) {
            return 0f
        }
        levels.sort()

        fun at(percentile: Double): Float =
            levels[(levels.size * percentile).toInt().coerceIn(0, levels.size - 1)]

        val noiseFloor = at(0.15)
        val ceiling = at(0.7)
        return min(max(noiseFloor * 2.5f, MIN_GATE_THRESHOLD), ceiling)
    }

    private class BlockProcessor(
        private val channels: Int,
        sampleRate: Int,
        gainDb: Float,
        private val denoise: Boolean,
        private val gateThreshold: Float,
    ) {
        private val gain = 10.0.pow(gainDb / 20.0).toFloat()
        private val highPassCoefficient =
            exp(-2.0 * PI * HIGH_PASS_CUTOFF_HZ / sampleRate).toFloat()
        private val prevIn = FloatArray(channels)
        private val prevOut = FloatArray(channels)
        private var gateGain = 1f
        private val scratch = FloatArray(BLOCK_FRAMES * channels)

        fun process(buffer: ByteBuffer, frames: Int) {
            val count = frames * channels

            for (i in 0 until count) {
                scratch[i] = buffer.getShort(i * BYTES_PER_SAMPLE).toFloat()
            }

            if (denoise) {
                // High-pass filter to remove rumble / hum below ~80 Hz
                var sumSq = 0.0
                for (i in 0 until count) {
                    val c = i % channels
                    val x = scratch[i]
                    val y = highPassCoefficient * (prevOut[c] + x - prevIn[c])
                    prevIn[c] = x
                    prevOut[c] = y
                    scratch[i] = y
                    sumSq += y.toDouble() * y
                }

                // Noise gate: attenuate blocks that are quieter than the threshold
                val rms = sqrt(sumSq / count).toFloat()
                val target = if (rms >= gateThreshold) 1f else GATE_REDUCTION
                gateGain += (target - gateGain) * GATE_SMOOTHING
            }

            val totalGain = if (denoise) gateGain * gain else gain
            for (i in 0 until count) {
                val value = (scratch[i] * totalGain).coerceIn(-32768f, 32767f)
                buffer.putShort(i * BYTES_PER_SAMPLE, value.roundToInt().toShort())
            }
        }
    }

    /**
     * Computes a peak envelope with [bins] values in 0..1 for drawing a waveform.
     * Throws IllegalArgumentException for unsupported formats.
     */
    fun computePeaks(openInput: () -> InputStream, bins: Int): FloatArray {
        openInput().use { input ->
            val header = readHeader(input)
            val peaks = FloatArray(bins)
            val total = header.totalFrames
            if (total <= 0L || bins <= 0) {
                return peaks
            }

            val buffer = ByteArray(BLOCK_FRAMES * header.frameBytes)
            val byteBuffer = ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN)
            var frame = 0L
            var remaining = total
            while (remaining > 0) {
                val n = min(BLOCK_FRAMES.toLong(), remaining).toInt()
                readFully(input, buffer, n * header.frameBytes)
                for (f in 0 until n) {
                    var peak = 0
                    for (c in 0 until header.channels) {
                        peak = max(peak, abs(byteBuffer.getShort((f * header.channels + c) * BYTES_PER_SAMPLE).toInt()))
                    }
                    val bin = ((frame + f) * bins / total).toInt().coerceIn(0, bins - 1)
                    val value = peak / 32768f
                    if (value > peaks[bin]) {
                        peaks[bin] = value
                    }
                }
                frame += n
                remaining -= n
            }
            return peaks
        }
    }

    private fun msToFrames(ms: Long, sampleRate: Int): Long =
        ms.coerceIn(0L, MAX_MS) * sampleRate / 1000L

    private fun readHeader(input: InputStream): Header {
        val riff = readBytes(input, 12)
        require(
            String(riff, 0, 4, Charsets.US_ASCII) == "RIFF" &&
                String(riff, 8, 4, Charsets.US_ASCII) == "WAVE"
        ) { "Not a WAV file" }

        var format = -1
        var channels = 0
        var sampleRate = 0
        var bits = 0

        while (true) {
            val chunk = readBytes(input, 8)
            val id = String(chunk, 0, 4, Charsets.US_ASCII)
            val size = ByteBuffer.wrap(chunk, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
                .toLong() and 0xFFFFFFFFL

            when (id) {
                "fmt " -> {
                    require(size >= 16) { "Invalid fmt chunk" }
                    val fmt = ByteBuffer.wrap(readBytes(input, size.toInt()))
                        .order(ByteOrder.LITTLE_ENDIAN)
                    format = fmt.getShort(0).toInt()
                    channels = fmt.getShort(2).toInt()
                    sampleRate = fmt.getInt(4)
                    bits = fmt.getShort(14).toInt()
                    if (size % 2 == 1L) {
                        skipFully(input, 1)
                    }
                }

                "data" -> {
                    require(format == 1 && bits == 16) { "Only 16-bit PCM WAV is supported" }
                    require(channels == 1 || channels == 2) { "Only mono/stereo is supported" }
                    return Header(sampleRate, channels, size)
                }

                else -> skipFully(input, size + (size and 1L))
            }
        }
    }

    private fun writeWavHeader(out: OutputStream, sampleRate: Int, channels: Int, dataSize: Long) {
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt((36 + dataSize).toInt())
        header.put("WAVE".toByteArray(Charsets.US_ASCII))
        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16)
        header.putShort(1) // PCM
        header.putShort(channels.toShort())
        header.putInt(sampleRate)
        header.putInt(sampleRate * channels * BYTES_PER_SAMPLE)
        header.putShort((channels * BYTES_PER_SAMPLE).toShort())
        header.putShort(16)
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(dataSize.toInt())
        out.write(header.array())
    }

    private fun readBytes(input: InputStream, count: Int): ByteArray {
        val buffer = ByteArray(count)
        readFully(input, buffer, count)
        return buffer
    }

    private fun readFully(input: InputStream, buffer: ByteArray, count: Int) {
        var offset = 0
        while (offset < count) {
            val read = input.read(buffer, offset, count - offset)
            if (read < 0) {
                throw EOFException("Unexpected end of audio data")
            }
            offset += read
        }
    }

    private fun skipFully(input: InputStream, count: Long) {
        var remaining = count
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped > 0) {
                remaining -= skipped
            } else {
                if (input.read() < 0) {
                    throw EOFException("Unexpected end of audio data")
                }
                remaining--
            }
        }
    }
}
