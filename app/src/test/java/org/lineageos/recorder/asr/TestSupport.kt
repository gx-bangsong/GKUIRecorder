/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.recorder.asr

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.apache.commons.compress.archivers.tar.TarConstants
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
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
 * A local HTTP server used in place of the upstream host. Serves [resources] by path and records
 * every Range header it receives.
 */
class FakeRemote : AutoCloseable {
    val resources = mutableMapOf<String, FakeResource>()
    val seenRanges = mutableListOf<String?>()
    val seenIfRanges = mutableListOf<String?>()
    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { exchange -> handle(exchange) }
        start()
    }

    /** Base URL of the local server, for example http://127.0.0.1:12345. */
    val baseUrl: String = "http://127.0.0.1:${server.address.port}"

    private fun handle(exchange: HttpExchange) {
        val resource = resources[exchange.requestURI.path]
        val range = exchange.requestHeaders.getFirst("Range")
        seenRanges += range
        seenIfRanges += exchange.requestHeaders.getFirst("If-Range")
        if (resource == null) {
            exchange.sendResponseHeaders(404, -1)
            exchange.close()
            return
        }
        if (resource.status != 200) {
            exchange.sendResponseHeaders(resource.status, -1)
            exchange.close()
            return
        }
        val total = resource.body.size
        var start = 0
        var code = 200
        if (range != null && resource.honorRange) {
            val from = Regex("bytes=(\\d+)-").find(range)!!.groupValues[1].toInt()
            if (from >= total) {
                exchange.sendResponseHeaders(416, -1)
                exchange.close()
                return
            }
            val ifRange = exchange.requestHeaders.getFirst("If-Range")
            val etagChanged = ifRange != null && resource.etag != null && ifRange != resource.etag
            if (!(etagChanged && resource.honorIfRange)) {
                start = from
                code = 206
            }
        }
        val payload = resource.body.copyOfRange(start, total)
        resource.etag?.let { exchange.responseHeaders.add("ETag", it) }
        if (code == 206) {
            exchange.responseHeaders.add("Content-Range", "bytes $start-${total - 1}/$total")
        }
        exchange.sendResponseHeaders(code, resource.declaredLength ?: payload.size.toLong())
        val sendCount = resource.truncateAt?.let { min(it, payload.size) } ?: payload.size
        // Flush before a short write: otherwise the JDK server drops buffered headers on close
        val out = exchange.responseBody
        out.write(payload, 0, sendCount)
        out.flush()
        runCatching { out.close() }
    }

    override fun close() {
        server.stop(0)
    }
}

/** Maps a fake https URL onto the local server; used as the downloader's connection factory. */
fun fakeHost(): String = "https://fake.example.test"

fun mapToLocal(remote: FakeRemote, url: String): java.net.HttpURLConnection =
    java.net.URI.create(url.replace(fakeHost(), remote.baseUrl)).toURL().openConnection() as java.net.HttpURLConnection
