/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.lineageos.generatebp.GenerateBpPluginExtension
import org.gradle.api.GradleException
import org.lineageos.generatebp.models.Module
import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.util.zip.ZipFile

plugins {
    id("com.android.application")
    id("kotlin-android")
    id("org.lineageos.generatebp")
}

// sherpa-onnx Android AAR (Kotlin API, libsherpa-onnx-jni.so, libonnxruntime.so), version pinned.
// It is fetched from the upstream GitHub release during configuration, checked against a pinned
// SHA-256, and unpacked into build/ (never committed). It is added as plain files, not as a Maven
// or flatDir module: generateBp only inspects module dependencies, so it neither needs a POM nor
// writes anything into app/libs. Update the version and the hash together.
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

// The verified AAR is cached in the Gradle user home, outside build/, so `clean` keeps it.
// Only the unpacked outputs under build/sherpa-onnx are generated and removed by `clean`.
val sherpaOnnxCacheDir: File = File(gradle.gradleUserHomeDir, "caches/gkui-recorder/sherpa-onnx/$SHERPA_ONNX_VERSION")
val sherpaOnnxAar = File(sherpaOnnxCacheDir, "sherpa-onnx-$SHERPA_ONNX_VERSION.aar")
val sherpaOnnxDir: File = rootProject.file("build/sherpa-onnx")
val sherpaOnnxClassesJar = File(sherpaOnnxDir, "sherpa-onnx-classes.jar")
val sherpaOnnxJniLibs = File(sherpaOnnxDir, "jniLibs")
val sherpaOnnxOffline: Boolean = gradle.startParameter.isOffline

val prepareSherpaOnnx = tasks.register("prepareSherpaOnnx") {
    group = "build setup"
    description = "Verifies the pinned sherpa-onnx AAR (downloads it if needed) and unpacks classes.jar and arm64 JNI libraries."
    inputs.property("sherpaOnnxVersion", SHERPA_ONNX_VERSION)
    inputs.property("sherpaOnnxAarSha256", SHERPA_ONNX_AAR_SHA256)
    outputs.file(sherpaOnnxClassesJar)
    outputs.dir(sherpaOnnxJniLibs)
    doLast {
        if (!(sherpaOnnxAar.isFile && sha256Of(sherpaOnnxAar) == SHERPA_ONNX_AAR_SHA256)) {
            sherpaOnnxAar.delete()
            if (sherpaOnnxOffline) {
                throw GradleException(
                    "sherpa-onnx $SHERPA_ONNX_VERSION AAR is not in the cache at $sherpaOnnxAar. " +
                        "Run once without --offline to download it."
                )
            }
            sherpaOnnxCacheDir.mkdirs()
            val part = File(sherpaOnnxCacheDir, sherpaOnnxAar.name + ".part")
            part.delete()
            val url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/v$SHERPA_ONNX_VERSION/" +
                "sherpa-onnx-$SHERPA_ONNX_VERSION.aar"
            var lastError: Exception? = null
            for (attempt in 1..3) {
                try {
                    val connection = URI(url).toURL().openConnection()
                    connection.connectTimeout = 30_000
                    connection.readTimeout = 120_000
                    connection.getInputStream().use { input ->
                        part.outputStream().use { output -> input.copyTo(output) }
                    }
                    lastError = null
                    break
                } catch (e: java.io.IOException) {
                    lastError = e
                    part.delete()
                }
            }
            lastError?.let { throw GradleException("Cannot download sherpa-onnx AAR: ${it.message}", it) }
            val actual = sha256Of(part)
            if (actual != SHERPA_ONNX_AAR_SHA256) {
                part.delete()
                throw GradleException("sherpa-onnx AAR checksum mismatch: $actual")
            }
            check(part.renameTo(sherpaOnnxAar)) { "Cannot move the sherpa-onnx AAR into place" }
        }

        sherpaOnnxClassesJar.delete()
        sherpaOnnxJniLibs.deleteRecursively()
        ZipFile(sherpaOnnxAar).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                val name = entry.name
                val target: File? = when {
                    name == "classes.jar" -> sherpaOnnxClassesJar
                    name.startsWith("jni/arm64-v8a/") && !entry.isDirectory ->
                        File(sherpaOnnxJniLibs, "arm64-v8a/" + name.substringAfterLast('/'))
                    else -> null
                }
                if (target != null) {
                    target.parentFile.mkdirs()
                    zip.getInputStream(entry).use { input ->
                        target.outputStream().use { output -> input.copyTo(output) }
                    }
                }
            }
        }
        check(sherpaOnnxClassesJar.isFile) { "sherpa-onnx classes.jar missing from the AAR" }
    }
}

// Everything that compiles or packages the app runs after the sherpa files are in place.
tasks.named("preBuild") { dependsOn(prepareSherpaOnnx) }
tasks.configureEach {
    if (name.startsWith("merge") && name.endsWith("JniLibFolders")) dependsOn(prepareSherpaOnnx)
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

    // sherpa-onnx native libraries from the pinned AAR (arm64-v8a only, see ndk.abiFilters)
    sourceSets["main"].jniLibs.srcDir(sherpaOnnxJniLibs)
}

dependencies {
    // Align versions of all Kotlin components
    implementation(platform("org.jetbrains.kotlin:kotlin-bom:2.1.10"))

    // Offline speech recognition: sherpa-onnx Kotlin API (classes from the pinned AAR, see above)
    implementation(files(sherpaOnnxClassesJar).builtBy(prepareSherpaOnnx))
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
            // commons-compress and its runtime dependencies (archive extraction of the model)
            module.group == "org.apache.commons" -> true
            module.group == "commons-codec" -> true
            module.group == "commons-io" -> true
            else -> false
        }
    }
}
