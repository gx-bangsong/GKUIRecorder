/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "org.lineageos.recorder.engine"
    compileSdk = 36

    defaultConfig {
        applicationId = "org.lineageos.recorder.engine"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    buildFeatures {
        aidl = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

// The sherpa-onnx AAR is fetched from its GitHub release and checked before use.
val sherpaAar = layout.buildDirectory.file("sherpa/sherpa-onnx.aar")

val fetchSherpaAar by tasks.registering {
    val url = providers.gradleProperty("sherpaOnnxAarUrl")
    val sha256 = providers.gradleProperty("sherpaOnnxAarSha256")
    outputs.file(sherpaAar)
    doLast {
        val target = sherpaAar.get().asFile
        target.parentFile.mkdirs()
        val source = url.orNull?.takeIf { it.isNotBlank() }
            ?: error("Set sherpaOnnxAarUrl in engine/gradle.properties")
        val expected = sha256.orNull?.takeIf { it.isNotBlank() }
            ?: error("Set sherpaOnnxAarSha256 in engine/gradle.properties")
        if (!target.isFile) {
            uri(source).toURL().openStream().use { input ->
                target.outputStream().use { input.copyTo(it) }
            }
        }
        val actual = MessageDigest.getInstance("SHA-256")
            .digest(target.readBytes())
            .joinToString("") { "%02x".format(it) }
        if (!actual.equals(expected, ignoreCase = true)) {
            target.delete()
            error("sherpa-onnx AAR checksum mismatch")
        }
    }
}

repositories {
    flatDir {
        dirs(layout.buildDirectory.dir("sherpa").get().asFile)
    }
}

dependencies {
    implementation(name = "sherpa-onnx", ext = "aar")
}

tasks.named("preBuild") {
    dependsOn(fetchSherpaAar)
}
