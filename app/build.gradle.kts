/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.lineageos.generatebp.GenerateBpPluginExtension
import org.lineageos.generatebp.models.Module
import java.io.File
import java.net.URI
import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("kotlin-android")
    id("org.lineageos.generatebp")
}

// sherpa-onnx Android AAR: Kotlin API plus libsherpa-onnx-jni.so and libonnxruntime.so, one version.
// Downloaded from the upstream GitHub release at build time and checked against a pinned SHA-256.
// The file is not committed to git. Update the version and the hash together.
val SHERPA_ONNX_VERSION = "1.13.8"
val SHERPA_ONNX_AAR_SHA256 = "633c24321e06b1fe79feafa03ea16cbc0f8a286641e2da3559bac91bdb13bd96"

fun sha256Of(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(1 shl 16)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

val downloadSherpaOnnxAar by tasks.registering {
    val target = rootProject.file("build/sherpa-onnx/sherpa-onnx-$SHERPA_ONNX_VERSION.aar")
    outputs.file(target)
    doLast {
        target.parentFile.mkdirs()
        if (target.isFile && sha256Of(target) == SHERPA_ONNX_AAR_SHA256) {
            return@doLast
        }
        val part = File(target.parentFile, target.name + ".part")
        val url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/v$SHERPA_ONNX_VERSION/" +
            "sherpa-onnx-$SHERPA_ONNX_VERSION.aar"
        URI(url).toURL().openStream().use { input ->
            part.outputStream().use { output -> input.copyTo(output) }
        }
        val actual = sha256Of(part)
        check(actual == SHERPA_ONNX_AAR_SHA256) { "sherpa-onnx AAR checksum mismatch: $actual" }
        check(part.renameTo(target)) { "Cannot move the sherpa-onnx AAR into place" }
    }
}

tasks.matching { it.name == "preBuild" }.configureEach {
    dependsOn(downloadSherpaOnnxAar)
}

android {
    compileSdk = 36
    namespace = "org.lineageos.recorder"

    defaultConfig {
        applicationId = "org.lineageos.recorder"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "1.1"

        // sherpa-onnx native libraries for arm64 phones only (keeps the APK size in check)
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildTypes {
        getByName("release") {
            // Enables code shrinking, obfuscation, and optimization.
            isMinifyEnabled = true

            // Includes the default ProGuard rules files.
            setProguardFiles(
                listOf(
                    getDefaultProguardFile("proguard-android-optimize.txt"),
                    "proguard-rules.pro"
                )
            )
        }
        getByName("debug") {
            // Append .dev to package name so we won't conflict with AOSP build.
            applicationIdSuffix = ".dev"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_1_8)
        }
    }
}

dependencies {
    // Align versions of all Kotlin components
    implementation(platform("org.jetbrains.kotlin:kotlin-bom:2.1.10"))

    // Offline speech recognition: sherpa-onnx Kotlin API and native libraries (see downloadSherpaOnnxAar)
    implementation(mapOf("name" to "sherpa-onnx-$SHERPA_ONNX_VERSION", "ext" to "aar"))
    // Model archive extraction (tar.bz2)
    implementation("org.apache.commons:commons-compress:1.28.0")
    // Background model downloads and transcription jobs
    implementation("androidx.work:work-runtime-ktx:2.10.0")

    testImplementation("junit:junit:4.13.2")

    implementation("androidx.activity:activity-ktx:1.7.2")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("com.google.android.material:material:1.9.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    // Lifecycle
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-service:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")

    // Recyclerview
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.recyclerview:recyclerview-selection:1.1.0")
}

configure<GenerateBpPluginExtension> {
    targetSdk.set(android.defaultConfig.targetSdk!!)
    minSdk.set(android.defaultConfig.minSdk!!)
    availableInAOSP.set { module: Module ->
        when {
            module.group.startsWith("androidx") -> true
            module.group.startsWith("org.jetbrains") -> true
            module.group == "com.google.android.material" -> true
            module.group == "com.google.errorprone" -> true
            module.group == "com.google.guava" -> true
            else -> false
        }
    }
}
