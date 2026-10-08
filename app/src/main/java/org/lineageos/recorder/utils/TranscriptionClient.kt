/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.recorder.utils

import android.content.Context
import android.net.Uri
import org.json.JSONException
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Speech-to-text through an OpenAI-compatible `/audio/transcriptions` endpoint.
 *
 * The endpoint is user supplied. A LAN server (e.g. faster-whisper-server, LocalAI,
 * whisper.cpp server in OpenAI mode) keeps everything offline; a cloud provider works
 * as well. When the endpoint is empty the feature is disabled and no audio is sent.
 */
object TranscriptionClient {
    data class Config(
        val endpoint: String,
        val apiKey: String,
        val model: String,
    ) {
        val isConfigured: Boolean
            get() = endpoint.isNotBlank()
    }

    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 10 * 60_000
    private const val CHUNK_SIZE = 64 * 1024

    fun endpointUrl(base: String): String {
        val trimmed = base.trim().trimEnd('/')
        return if (trimmed.endsWith("/audio/transcriptions")) {
            trimmed
        } else {
            "$trimmed/audio/transcriptions"
        }
    }

    /**
     * Uploads the audio at [uri] and returns the recognized text. Blocking; call from IO.
     */
    @Throws(IOException::class)
    fun transcribe(
        context: Context,
        uri: Uri,
        fileName: String,
        config: Config,
        language: String = "zh",
    ): String {
        require(config.isConfigured) { "Transcription endpoint is not configured" }

        val boundary = "----RecorderBoundary${System.currentTimeMillis()}"
        val connection = (URL(endpointUrl(config.endpoint)).openConnection() as HttpURLConnection)
            .apply {
                requestMethod = "POST"
                doOutput = true
                useCaches = false
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setChunkedStreamingMode(CHUNK_SIZE)
                setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                if (config.apiKey.isNotBlank()) {
                    setRequestProperty("Authorization", "Bearer ${config.apiKey}")
                }
            }

        try {
            BufferedOutputStream(connection.outputStream, CHUNK_SIZE).use { out ->
                fun writeText(text: String) {
                    out.write(text.toByteArray(Charsets.UTF_8))
                }

                fun writeField(name: String, value: String) {
                    writeText(
                        "--$boundary\r\n" +
                            "Content-Disposition: form-data; name=\"$name\"\r\n\r\n" +
                            "$value\r\n"
                    )
                }

                if (config.model.isNotBlank()) {
                    writeField("model", config.model)
                }
                writeField("language", language)
                writeField("response_format", "json")

                val safeName = fileName.replace("\"", "_")
                writeText(
                    "--$boundary\r\n" +
                        "Content-Disposition: form-data; name=\"file\"; filename=\"$safeName\"\r\n" +
                        "Content-Type: application/octet-stream\r\n\r\n"
                )
                val input = context.contentResolver.openInputStream(uri)
                    ?: throw IOException("Cannot open audio file")
                input.use { it.copyTo(out) }

                writeText("\r\n--$boundary--\r\n")
                out.flush()
            }

            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                throw IOException("HTTP $code ${body.take(200)}")
            }
            return parseText(body)
        } finally {
            connection.disconnect()
        }
    }

    private fun parseText(body: String): String = try {
        JSONObject(body).optString("text", "").trim()
    } catch (e: JSONException) {
        // Servers that ignore response_format=json reply with plain text
        body.trim()
    }
}
