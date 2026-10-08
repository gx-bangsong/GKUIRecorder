/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.lineageos.recorder.utils

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.IBinder
import org.lineageos.recorder.engine.ISpeechEngine
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Talks to the on-demand speech engine APK over AIDL. Blocking; call from IO.
 */
object EngineClient {
    const val PACKAGE = "org.lineageos.recorder.engine"
    const val ACTION = "org.lineageos.recorder.engine.SPEECH"
    const val PROTOCOL_VERSION = 1

    private const val BIND_TIMEOUT_SECONDS = 15L

    fun isInstalled(context: Context): Boolean = try {
        context.packageManager.getPackageInfo(PACKAGE, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    /**
     * Binds to the engine, loads the model from [modelUrl] and transcribes [uri].
     * Throws IllegalStateException with a readable message on failure.
     */
    fun transcribe(
        context: Context,
        uri: Uri,
        language: String,
        modelUrl: String,
        modelSha256: String,
    ): String {
        check(isInstalled(context)) { "Speech engine is not installed" }
        val connected = CountDownLatch(1)
        var engine: ISpeechEngine? = null
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                engine = ISpeechEngine.Stub.asInterface(service)
                connected.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                engine = null
            }
        }
        val intent = Intent(ACTION).setPackage(PACKAGE)
        check(context.bindService(intent, connection, Context.BIND_AUTO_CREATE)) {
            "Cannot connect to the speech engine"
        }
        try {
            check(connected.await(BIND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                "The speech engine did not respond"
            }
            val service = checkNotNull(engine) { "The speech engine disconnected" }
            check(service.protocolVersion == PROTOCOL_VERSION) {
                "The speech engine version is not compatible, update it"
            }
            val prepareError = service.prepareModel(modelUrl, modelSha256)
            check(prepareError.isEmpty()) { prepareError }
            val pfd = context.contentResolver.openFileDescriptor(uri, "r")
                ?: error("Cannot open the recording")
            return pfd.use { service.transcribe(it, language) }
        } finally {
            context.unbindService(connection)
        }
    }
}
