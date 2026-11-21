/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.recorder.asr

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.apache.commons.compress.archivers.tar.TarConstants
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.util.Collections
import kotlin.math.min

/** Creates a fresh temporary directory; the caller removes it. */
fun newTempDir(): File = Files.createTempDirectory("asr-test").toFile()

/** One tar entry. [linkTarget] makes it a symbolic link instead of a regular file. */
data class TarSpec(val name: String, val bytes: ByteArray = ByteArray(0), val linkTarget: String? = null)

/** Writes a .tar.bz2 archive containing [entries]. */
fun writeTarBz2(file: File, entries: List<TarSpec>) {
    file.outputStream().use { raw ->
        TarArchiveOutputStream(BZip2CompressorOutputStream(raw)).use { tar ->
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
            for (spec in entries) {
                val entry = if (spec.linkTarget != null) {
                    TarArchiveEntry(spec.name, TarConstants.LF_SYMLINK).apply { linkName = spec.linkTarget }
                } else {
                    TarArchiveEntry(spec.name).apply { size = spec.bytes.size.toLong() }
                }
                tar.putArchiveEntry(entry)
                if (spec.linkTarget == null) {
                    tar.write(spec.bytes)
                }
                tar.closeArchiveEntry()
            }
            tar.finish()
        }
    }
}

/** Deterministic pseudo-random bytes so tests are reproducible. */
fun pseudoRandomBytes(size: Int, seed: Long): ByteArray {
    val random = java.util.Random(seed)
    return ByteArray(size).also { random.nextBytes(it) }
}

/** A file served by [FakeRemote]. Behaviour knobs model the failure modes the downloader must handle. */
class FakeResource(
    var body: ByteArray,
    var etag: String? = "\"v1\"",
    var status: Int = 200,
    /** False makes the server ignore the Range header and answer 200 with the whole file. */
    var honorRange: Boolean = true,
    /** False makes the server ignore If-Range, so a changed ETag is only visible in the response. */
    var honorIfRange: Boolean = true,
    /** Content-Length header to send; defaults to the real length. */
    var declaredLength: Long? = null,
    /** Stop sending after this many payload bytes, closing the connection early. */
    var truncateAt: Int? = null,
)

/**
 * A local HTTP/1.1 server used in place of the upstream host. Serves [resources] by path and
 * records every Range and If-Range header it receives.
 *
 * Built on java.net.ServerSocket, not com.sun.net.httpserver: the Android unit-test classpath
 * does not contain the com.sun packages, so that class cannot be compiled here.
 * Each connection is answered once and closed (Connection: close).
 */
class FakeRemote : AutoCloseable {
    val resources = mutableMapOf<String, FakeResource>()
    val seenRanges: MutableList<String?> = Collections.synchronizedList(mutableListOf<String?>())
    val seenIfRanges: MutableList<String?> = Collections.synchronizedList(mutableListOf<String?>())
    private val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))

    /** Base URL of the local server, for example http://127.0.0.1:12345. */
    val baseUrl: String = "http://127.0.0.1:${socket.localPort}"

    @Volatile
    private var running = true

    private val acceptThread = Thread {
        while (running) {
            val client = try {
                socket.accept()
            } catch (e: IOException) {
                // Closed by close(), or a broken listener: stop serving
                break
            }
            runCatching { client.use { serve(it) } }
        }
    }.apply {
        isDaemon = true
        start()
    }

    private fun serve(client: Socket) {
        client.soTimeout = 10_000
        val input = BufferedInputStream(client.getInputStream())
        val requestLine = readLine(input) ?: return
        val headers = HashMap<String, String>()
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) {
                break
            }
            val colon = line.indexOf(':')
            if (colon > 0) {
                headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
            }
        }
        val path = requestLine.split(' ').getOrNull(1)?.substringBefore('?') ?: "/"
        val resource = resources[path]
        val range = headers["range"]
        val ifRange = headers["if-range"]
        seenRanges += range
        seenIfRanges += ifRange

        val out = client.getOutputStream()
        if (resource == null) {
            respond(out, 404, emptyList(), null, 0, 0)
            return
        }
        if (resource.status != 200) {
            respond(out, resource.status, emptyList(), null, 0, 0)
            return
        }
        val total = resource.body.size
        var start = 0
        var code = 200
        if (range != null && resource.honorRange) {
            val from = Regex("bytes=(\\d+)-").find(range)!!.groupValues[1].toInt()
            if (from >= total) {
                respond(out, 416, emptyList(), null, 0, 0)
                return
            }
            val etagChanged = ifRange != null && resource.etag != null && ifRange != resource.etag
            if (!(etagChanged && resource.honorIfRange)) {
                start = from
                code = 206
            }
        }
        val payload = resource.body.copyOfRange(start, total)
        val headerList = mutableListOf<Pair<String, String>>()
        resource.etag?.let { headerList += "ETag" to it }
        if (code == 206) {
            headerList += "Content-Range" to "bytes $start-${total - 1}/$total"
        }
        val declared = resource.declaredLength ?: payload.size.toLong()
        val sendCount = resource.truncateAt?.let { min(it, payload.size) } ?: payload.size
        respond(out, code, headerList, payload, declared, sendCount)
    }

    /**
     * Writes one response. [declaredLength] goes into Content-Length; only [sendCount] bytes of
     * [payload] are written, so a short body can be simulated. A null payload means no body.
     */
    private fun respond(
        out: OutputStream,
        code: Int,
        headers: List<Pair<String, String>>,
        payload: ByteArray?,
        declaredLength: Long,
        sendCount: Int,
    ) {
        val head = StringBuilder()
        head.append("HTTP/1.1 ").append(code).append(' ').append(reason(code)).append("\r\n")
        head.append("Connection: close\r\n")
        head.append("Content-Length: ").append(declaredLength).append("\r\n")
        for ((name, value) in headers) {
            head.append(name).append(": ").append(value).append("\r\n")
        }
        head.append("\r\n")
        out.write(head.toString().toByteArray(Charsets.ISO_8859_1))
        if (payload != null && sendCount > 0) {
            out.write(payload, 0, sendCount)
        }
        out.flush()
    }

    private fun reason(code: Int): String = when (code) {
        200 -> "OK"
        206 -> "Partial Content"
        404 -> "Not Found"
        416 -> "Range Not Satisfiable"
        else -> "Status"
    }

    /** Reads one CRLF-terminated line. Returns null at end of stream. */
    private fun readLine(input: InputStream): String? {
        val line = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) {
                return if (line.isEmpty()) null else line.toString()
            }
            if (b == '\n'.code) {
                return line.toString()
            }
            if (b != '\r'.code) {
                line.append(b.toChar())
            }
        }
    }

    override fun close() {
        running = false
        runCatching { socket.close() }
        runCatching { acceptThread.join(2_000) }
    }
}

/** Maps a fake https URL onto the local server; used as the downloader's connection factory. */
fun fakeHost(): String = "https://fake.example.test"

fun mapToLocal(remote: FakeRemote, url: String): java.net.HttpURLConnection =
    java.net.URI.create(url.replace(fakeHost(), remote.baseUrl)).toURL().openConnection() as java.net.HttpURLConnection
