/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.recorder.asr.model

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI

/** Resume information kept next to a partial download. */
data class DownloadMeta(
    val url: String,
    val etag: String?,
    val lastModified: String?,
    val totalBytes: Long?,
) {
    fun toJson(): String = MiniJson.write(
        mapOf(
            "url" to url,
            "etag" to etag,
            "lastModified" to lastModified,
            "totalBytes" to totalBytes,
        ),
    )

    companion object {
        fun fromJson(text: String): DownloadMeta? = runCatching {
            val map = MiniJson.parseObject(text)
            DownloadMeta(
                url = map["url"] as String,
                etag = map["etag"] as String?,
                lastModified = map["lastModified"] as String?,
                totalBytes = (map["totalBytes"] as Number?)?.toLong(),
            )
        }.getOrNull()
    }
}

/** Failure with a user-meaningful reason. [recoverable] tells the worker whether a retry can help. */
class ModelFailure(
    val reason: FailureReason,
    val recoverable: Boolean,
    message: String,
) : Exception(message)

/** Progress callback: downloaded bytes of the whole file, total bytes (or -1), bytes per second. */
fun interface DownloadProgress {
    fun onProgress(downloadedBytes: Long, totalBytes: Long, bytesPerSecond: Long)
}

/**
 * HTTP(S) downloader with resume support.
 *
 * - Resumes with `Range` and `If-Range` (ETag or Last-Modified saved next to the partial file).
 * - A 200 answer to a Range request, a changed ETag, or a 416 answer restarts from zero.
 * - A Content-Length that disagrees with the expected size is rejected.
 * - Streams to disk; never holds the whole file in memory.
 *
 * [allowPlainHttp] exists for unit tests against a local server. Production code always uses HTTPS.
 */
class ResumableDownloader(
    private val allowPlainHttp: Boolean = false,
    private val connectTimeoutMs: Int = 20_000,
    private val readTimeoutMs: Int = 30_000,
    private val openConnection: (String) -> HttpURLConnection = { url ->
        URI.create(url).toURL().openConnection() as HttpURLConnection
    },
) {
    /**
     * Downloads [url] into [partFile], resuming when possible. Returns when the complete file is
     * on disk as [partFile]. Throws [ModelFailure] otherwise. The caller renames the file after
     * verifying its hash.
     */
    fun download(
        url: String,
        partFile: File,
        metaFile: File,
        expectedBytes: Long,
        progress: DownloadProgress,
        isCancelled: () -> Boolean,
    ) {
        checkScheme(url)
        partFile.parentFile?.mkdirs()
        val savedMeta = if (metaFile.isFile) DownloadMeta.fromJson(metaFile.readText()) else null
        if (savedMeta == null || savedMeta.url != url) {
            partFile.delete()
            metaFile.delete()
        }

        var restarts = 0
        while (true) {
            val existing = if (partFile.isFile) partFile.length() else 0L
            val conn = open(url)
            try {
                if (existing > 0) {
                    conn.setRequestProperty("Range", "bytes=$existing-")
                    val validator = savedMeta?.etag ?: savedMeta?.lastModified
                    if (validator != null) {
                        conn.setRequestProperty("If-Range", validator)
                    }
                }
                val code = conn.responseCode
                when {
                    code == HttpURLConnection.HTTP_PARTIAL && existing > 0 -> {
                        val range = parseContentRange(conn.getHeaderField("Content-Range"))
                        val savedEtag = savedMeta?.etag
                        val responseEtag = conn.getHeaderField("ETag")
                        if (range == null || range.first != existing ||
                            (savedEtag != null && responseEtag != null && savedEtag != responseEtag)
                        ) {
                            // The remote file changed or the range does not line up: start over
                            partFile.delete()
                            metaFile.delete()
                            restartOrFail(++restarts)
                            continue
                        }
                        val total = range.second ?: expectedBytes
                        checkTotal(total, expectedBytes)
                        writeMeta(metaFile, url, responseEtag ?: savedEtag, conn, total)
                        transfer(conn, partFile, append = true, existing, total, progress, isCancelled)
                    }
                    code == HttpURLConnection.HTTP_OK -> {
                        partFile.delete()
                        val total = declaredLength(conn) ?: expectedBytes
                        checkTotal(total, expectedBytes)
                        writeMeta(metaFile, url, conn.getHeaderField("ETag"), conn, total)
                        transfer(conn, partFile, append = false, 0L, total, progress, isCancelled)
                    }
                    code == HTTP_RANGE_NOT_SATISFIABLE -> {
                        // The partial file is longer than the remote one (or invalid). Start over once.
                        partFile.delete()
                        metaFile.delete()
                        restartOrFail(++restarts)
                        continue
                    }
                    code == 404 || code == 403 || code == 410 -> throw ModelFailure(
                        FailureReason.HTTP_STATUS,
                        recoverable = false,
                        message = "HTTP $code",
                    )
                    else -> throw ModelFailure(
                        FailureReason.HTTP_STATUS,
                        recoverable = true,
                        message = "HTTP $code",
                    )
                }
                return
            } finally {
                conn.disconnect()
            }
        }
    }

    private fun restartOrFail(restarts: Int) {
        if (restarts > 1) {
            throw ModelFailure(FailureReason.NETWORK, recoverable = true, message = "Range restart failed")
        }
    }

    private fun open(url: String): HttpURLConnection = openConnection(url).apply {
        connectTimeout = connectTimeoutMs
        readTimeout = readTimeoutMs
        instanceFollowRedirects = true
        requestMethod = "GET"
        setRequestProperty("Accept-Encoding", "identity")
        setRequestProperty("User-Agent", USER_AGENT)
    }

    private fun checkScheme(url: String) {
        if (!url.startsWith("https://") && !(allowPlainHttp && url.startsWith("http://"))) {
            throw ModelFailure(FailureReason.UNKNOWN, recoverable = false, message = "Model URLs must use HTTPS")
        }
    }

    private fun checkTotal(total: Long?, expectedBytes: Long) {
        if (total != null && total != expectedBytes) {
            throw ModelFailure(
                FailureReason.SIZE_MISMATCH,
                recoverable = false,
                message = "Remote size $total does not match expected $expectedBytes",
            )
        }
    }

    private fun transfer(
        conn: HttpURLConnection,
        partFile: File,
        append: Boolean,
        startOffset: Long,
        total: Long,
        progress: DownloadProgress,
        isCancelled: () -> Boolean,
    ) {
        var written = startOffset
        var windowStart = System.nanoTime()
        var windowBytes = 0L
        var lastReport = 0L
        try {
            conn.inputStream.use { input ->
                FileOutputStream(partFile, append).use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        if (isCancelled()) {
                            throw ModelFailure(FailureReason.CANCELLED, recoverable = true, message = "Cancelled")
                        }
                        val read = input.read(buffer)
                        if (read < 0) {
                            break
                        }
                        output.write(buffer, 0, read)
                        written += read
                        windowBytes += read
                        val now = System.nanoTime()
                        if (now - lastReport > REPORT_INTERVAL_NS) {
                            val elapsed = (now - windowStart).coerceAtLeast(1L)
                            val bps = windowBytes * 1_000_000_000L / elapsed
                            progress.onProgress(written, total, bps)
                            lastReport = now
                            if (elapsed > REPORT_INTERVAL_NS * 4) {
                                windowStart = now
                                windowBytes = 0L
                            }
                        }
                    }
                    output.fd.sync()
                }
            }
        } catch (e: IOException) {
            // Keep the partial file: the next attempt resumes from it
            throw ModelFailure(FailureReason.NETWORK, recoverable = true, message = e.javaClass.simpleName)
        }
        val onDisk = partFile.length()
        if (onDisk != total) {
            throw ModelFailure(
                FailureReason.NETWORK,
                recoverable = true,
                message = "Connection ended at $onDisk of $total bytes",
            )
        }
        progress.onProgress(onDisk, total, 0L)
    }

    private fun writeMeta(metaFile: File, url: String, etag: String?, conn: HttpURLConnection, total: Long?) {
        val meta = DownloadMeta(
            url = url,
            etag = etag,
            lastModified = conn.getHeaderField("Last-Modified"),
            totalBytes = total,
        )
        metaFile.writeText(meta.toJson())
    }

    private fun declaredLength(conn: HttpURLConnection): Long? =
        conn.contentLengthLong.takeIf { it >= 0 }

    companion object {
        const val USER_AGENT = "GKUIRecorder-model-downloader"
        private const val HTTP_RANGE_NOT_SATISFIABLE = 416
        private const val BUFFER_SIZE = 64 * 1024
        private const val REPORT_INTERVAL_NS = 300_000_000L

        /** Parses `bytes 100-199/200` into start offset and total (null when unknown). */
        internal fun parseContentRange(value: String?): Pair<Long, Long?>? {
            if (value == null) {
                return null
            }
            val match = Regex("""bytes (\d+)-(\d+)/(\d+|\*)""").find(value.trim()) ?: return null
            val start = match.groupValues[1].toLongOrNull() ?: return null
            val total = match.groupValues[3].toLongOrNull()
            return start to total
        }
    }
}
