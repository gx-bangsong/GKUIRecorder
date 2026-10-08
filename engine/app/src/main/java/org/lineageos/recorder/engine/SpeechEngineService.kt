/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.recorder.engine

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.ServiceSpecificException

/**
 * Offline speech-to-text service. Only the Recorder app (release and debug builds) may
 * call it.
 */
class SpeechEngineService : Service() {
    private val models by lazy { ModelStore(this) }

    private val binder = object : ISpeechEngine.Stub() {
        override fun getProtocolVersion(): Int {
            requireCaller()
            return PROTOCOL_VERSION
        }

        override fun prepareModel(url: String, sha256: String): String {
            requireCaller()
            return try {
                models.prepare(url, sha256)
                ""
            } catch (e: Exception) {
                e.message ?: e.javaClass.simpleName
            }
        }

        override fun transcribe(audio: ParcelFileDescriptor, language: String): String {
            requireCaller()
            try {
                val modelDir = models.readyDirectory()
                    ?: throw IllegalStateException("Model is not prepared")
                val samples = AudioDecoder.decodeTo16k(audio)
                return SpeechRecognizerHolder.transcribe(modelDir, samples, language)
            } catch (e: ServiceSpecificException) {
                throw e
            } catch (e: Exception) {
                throw ServiceSpecificException(ERROR_RECOGNITION, e.message ?: "Recognition failed")
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    private fun requireCaller() {
        val packages = packageManager.getPackagesForUid(Binder.getCallingUid()).orEmpty()
        if (packages.none { it in ALLOWED_CALLERS }) {
            throw SecurityException("Caller is not allowed to use the speech engine")
        }
    }

    companion object {
        const val PROTOCOL_VERSION = 1
        private const val ERROR_RECOGNITION = 1
        private val ALLOWED_CALLERS = setOf(
            "org.lineageos.recorder",
            "org.lineageos.recorder.dev",
        )
    }
}
