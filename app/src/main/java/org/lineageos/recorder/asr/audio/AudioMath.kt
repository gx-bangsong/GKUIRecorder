/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.recorder.asr.audio

import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** PCM WAV header information needed to stream the samples. */
data class WavInfo(
    val sampleRate: Int,
    val channels: Int,
    val bitsPerSample: Int,
    val formatTag: Int,
    val dataSize: Long,
) {
    /** Duration in milliseconds, computed from the data chunk size. */
    val durationMs: Long
        get() {
            val bytesPerFrame = channels * (bitsPerSample / 8)
            if (bytesPerFrame <= 0 || sampleRate <= 0) {
                return 0L
            }
            return dataSize * 1000L / (sampleRate.toLong() * bytesPerFrame)
        }

    /** True for 16-bit integer PCM, which is what this app records. */
    val isPcm16: Boolean
        get() = formatTag == FORMAT_PCM && bitsPerSample == 16 && channels in 1..8 && sampleRate > 0

    companion object {
        const val FORMAT_PCM = 1
        const val FORMAT_EXTENSIBLE = 0xFFFE
    }
}

object WavParser {
    /**
     * Reads the RIFF header and returns the format and the data chunk size. The stream is left
     * positioned at the first sample. Returns null when the input is not a WAV file.
     */
    fun parse(input: InputStream): WavInfo? {
        val data = DataInputStream(input)
        val riff = ByteArray(4)
        data.readFully(riff)
        if (String(riff, Charsets.US_ASCII) != "RIFF") {
            return null
        }
        skipFully(data, 4)
        val wave = ByteArray(4)
        data.readFully(wave)
        if (String(wave, Charsets.US_ASCII) != "WAVE") {
            return null
        }
        var format: WavInfo? = null
        var fmtSeen = false
        while (true) {
            val id = ByteArray(4)
            try {
                data.readFully(id)
            } catch (e: EOFException) {
                return null
            }
            val size = readLittleEndianInt(data).toLong() and 0xFFFF_FFFFL
            val name = String(id, Charsets.US_ASCII)
            when (name) {
                "fmt " -> {
                    val body = ByteArray(size.toInt())
                    data.readFully(body)
                    val buf = java.nio.ByteBuffer.wrap(body).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                    var tag = buf.getShort(0).toInt() and 0xFFFF
                    val channels = buf.getShort(2).toInt() and 0xFFFF
                    val rate = buf.getInt(4)
                    val bits = buf.getShort(14).toInt() and 0xFFFF
                    if (tag == WavInfo.FORMAT_EXTENSIBLE && body.size >= 26) {
                        tag = buf.getShort(24).toInt() and 0xFFFF
                    }
                    format = WavInfo(rate, channels, bits, tag, dataSize = 0L)
                    fmtSeen = true
                    if (size % 2L == 1L) {
                        skipFully(data, 1)
                    }
                }
                "data" -> {
                    if (!fmtSeen) {
                        return null
                    }
                    return format!!.copy(dataSize = size)
                }
                else -> {
                    skipFully(data, size)
                    if (size % 2L == 1L) {
                        skipFully(data, 1)
                    }
                }
            }
        }
    }

    /** DataInputStream.skipBytes may skip less than asked; this reads until the count is reached. */
    private fun skipFully(input: DataInputStream, count: Long) {
        var remaining = count
        val scratch = ByteArray(4096)
        while (remaining > 0) {
            val n = min(remaining, scratch.size.toLong()).toInt()
            input.readFully(scratch, 0, n)
            remaining -= n
        }
    }

    private fun readLittleEndianInt(input: DataInputStream): Int {
        val b = ByteArray(4)
        input.readFully(b)
        return (b[0].toInt() and 0xFF) or ((b[1].toInt() and 0xFF) shl 8) or
            ((b[2].toInt() and 0xFF) shl 16) or ((b[3].toInt() and 0xFF) shl 24)
    }
}

/** Mixes interleaved 16-bit PCM frames down to mono floats in [-1, 1). */
object Downmix {
    fun pcm16ToMono(
        interleaved: ShortArray,
        frames: Int,
        channels: Int,
        out: FloatArray,
    ) {
        require(channels >= 1)
        for (frame in 0 until frames) {
            var sum = 0
            val base = frame * channels
            for (c in 0 until channels) {
                sum += interleaved[base + c]
            }
            out[frame] = sum.toFloat() / (channels * 32768f)
        }
    }
}

/**
 * Streaming sample-rate converter using a windowed-sinc kernel with a low-pass cutoff at the
 * output Nyquist frequency. Keeps only the input needed for the next outputs, so memory use does
 * not grow with the recording length.
 */
class StreamingResampler(
    private val inputRate: Int,
    private val outputRate: Int,
) {
    private val passthrough = inputRate == outputRate
    private val step = inputRate.toDouble() / outputRate
    private val cutoff = min(1.0, outputRate.toDouble() / inputRate) * 0.96
    private val halfTaps = 16

    // Input samples not yet consumed; buffer[0] is absolute input index bufferStart
    private var buffer = FloatArray(0)
    private var bufferLength = 0
    private var bufferStart = 0L
    private var nextOutput = 0L

    /** Feeds [count] mono input samples and emits resampled samples through [sink]. */
    fun process(input: FloatArray, count: Int, sink: (FloatArray, Int) -> Unit) {
        if (passthrough) {
            sink(input, count)
            return
        }
        append(input, count)
        emitReady(sink, final = false)
    }

    /** Emits the remaining outputs at the end of the input. */
    fun flush(sink: (FloatArray, Int) -> Unit) {
        if (passthrough) {
            return
        }
        emitReady(sink, final = true)
    }

    private fun append(input: FloatArray, count: Int) {
        // Drop consumed input before growing the buffer
        val keepFrom = (nextOutput * step).toLong() - halfTaps
        val drop = (keepFrom - bufferStart).coerceIn(0L, bufferLength.toLong()).toInt()
        if (drop > 0) {
            System.arraycopy(buffer, drop, buffer, 0, bufferLength - drop)
            bufferLength -= drop
            bufferStart += drop
        }
        if (bufferLength + count > buffer.size) {
            buffer = buffer.copyOf(maxOf(bufferLength + count, buffer.size * 2, 4096))
        }
        System.arraycopy(input, 0, buffer, bufferLength, count)
        bufferLength += count
    }

    private fun emitReady(sink: (FloatArray, Int) -> Unit, final: Boolean) {
        val totalInput = bufferStart + bufferLength
        val out = FloatArray(512)
        var outCount = 0
        while (true) {
            val center = nextOutput * step
            val lastNeeded = kotlin.math.floor(center).toLong() + halfTaps
            if (!final && lastNeeded >= totalInput) {
                break
            }
            if (final && kotlin.math.floor(center).toLong() >= totalInput) {
                break
            }
            out[outCount++] = sample(center)
            nextOutput++
            if (outCount == out.size) {
                sink(out, outCount)
                outCount = 0
            }
        }
        if (outCount > 0) {
            sink(out, outCount)
        }
    }

    private fun sample(center: Double): Float {
        val base = kotlin.math.floor(center).toLong()
        var acc = 0.0
        var norm = 0.0
        for (k in -halfTaps + 1..halfTaps) {
            val index = base + k
            val x = center - index
            val weight = kernel(x)
            norm += weight
            val local = index - bufferStart
            if (local >= 0 && local < bufferLength) {
                acc += weight * buffer[local.toInt()]
            }
        }
        return if (norm == 0.0) 0f else (acc / norm).toFloat()
    }

    private fun kernel(x: Double): Double {
        if (x == 0.0) {
            return cutoff
        }
        val arg = PI * x * cutoff
        val sinc = sin(arg) / (PI * x)
        val window = 0.5 + 0.5 * cos(PI * x / halfTaps)
        return sinc * window
    }
}
