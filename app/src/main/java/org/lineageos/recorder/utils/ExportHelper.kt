/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.recorder.utils

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.io.IOException
import java.io.OutputStream

/**
 * Quick export and sharing helpers, including the popular Chinese social/work apps.
 * Packages listed here are declared in AndroidManifest <queries> so they can be detected.
 */
object ExportHelper {
    data class ShareTarget(
        val label: String,
        val packageName: String,
    )

    val DOMESTIC_SHARE_TARGETS = listOf(
        ShareTarget("微信", "com.tencent.mm"),
        ShareTarget("QQ", "com.tencent.mobileqq"),
        ShareTarget("企业微信", "com.tencent.wework"),
        ShareTarget("钉钉", "com.alibaba.android.rimet"),
        ShareTarget("飞书", "com.ss.android.lark"),
        ShareTarget("抖音", "com.ss.android.ugc.aweme"),
        ShareTarget("小红书", "com.xingin.xhs"),
        ShareTarget("百度网盘", "com.baidu.netdisk"),
    )

    private const val EXPORT_FOLDER = "录音导出"

    fun installedTargets(context: Context): List<ShareTarget> =
        DOMESTIC_SHARE_TARGETS.filter { isInstalled(context, it.packageName) }

    @Suppress("DEPRECATION")
    private fun isInstalled(context: Context, packageName: String): Boolean = try {
        context.packageManager.getPackageInfo(packageName, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    fun mimeTypeFor(title: String): String =
        if (title.endsWith(".wav", ignoreCase = true)) "audio/wav" else "audio/mp4"

    fun directShareIntent(uri: Uri, mimeType: String, target: ShareTarget): Intent =
        Intent(Intent.ACTION_SEND)
            .setType(mimeType)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .setPackage(target.packageName)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

    /** Copies a recording to Download/录音导出 so it can be picked from any file manager. */
    fun exportToDownloads(
        context: Context,
        source: Uri,
        displayName: String,
        mimeType: String,
    ): Uri? = writeToDownloads(context, displayName, mimeType) { out ->
        val input = context.contentResolver.openInputStream(source)
            ?: throw IOException("Cannot open source")
        input.use { it.copyTo(out) }
    }

    fun saveTextToDownloads(context: Context, displayName: String, text: String): Uri? =
        writeToDownloads(context, displayName, "text/plain") { out ->
            out.write(text.toByteArray(Charsets.UTF_8))
        }

    private fun writeToDownloads(
        context: Context,
        displayName: String,
        mimeType: String,
        writer: (OutputStream) -> Unit,
    ): Uri? {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, displayName)
            put(MediaStore.Downloads.MIME_TYPE, mimeType)
            put(
                MediaStore.Downloads.RELATIVE_PATH,
                "${Environment.DIRECTORY_DOWNLOADS}/$EXPORT_FOLDER"
            )
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val destination = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: return null

        return try {
            val out = resolver.openOutputStream(destination)
                ?: throw IOException("Cannot open destination")
            out.use { writer(it) }

            resolver.update(
                destination,
                ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) },
                null,
                null,
            )
            destination
        } catch (e: IOException) {
            resolver.delete(destination, null, null)
            null
        }
    }
}
