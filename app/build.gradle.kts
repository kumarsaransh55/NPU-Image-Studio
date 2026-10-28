import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val ortVersion: String by project

// Optional release signing: create keystore.properties next to this file (see README in the project root).
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.isFile) f.inputStream().use { load(it) }
}

android {
    namespace = "studio.npu.x200t"
    compileSdk = 36

    defaultConfig {
        applicationId = "studio.npu.x200t"
        minSdk = 31          // Android 12+ (needed for vendor NPU library access rules)
        targetSdk = 36
        versionCode = 5
        versionName = "1.4"
        ndk { abiFilters += "arm64-v8a" }
    }

    // Two builds of the same app (same id, same signature, so one can be installed over the other):
    //  system  - uses vivo's own NeuroPilot runtime that ships inside Origin OS (try this first)
    //  bundled - also packs MediaTek's public NeuroPilot runtime (8.0.10) inside the APK,
    //            the same approach Google's LiteRT samples use for Dimensity 9400 (MT6991)
    flavorDimensions += "neuron"
    productFlavors {
        create("system") {
            dimension = "neuron"
            buildConfigField("boolean", "BUNDLED_NEURON_RUNTIME", "false")
        }
        create("bundled") {
            dimension = "neuron"
            buildConfigField("boolean", "BUNDLED_NEURON_RUNTIME", "true")
        }
    }

    signingConfigs {
        if (keystoreProps.getProperty("storeFile") != null) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            // Debug builds also run on the PC emulator (x86_64). The NPU plugin is ARM-only,
            // so there the app simply reports "NPU not available" and uses GPU/CPU.
            ndk { abiFilters += "x86_64" }
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    packaging {
        // The NPU plugin is loaded by file path, so native libraries must be real files on disk.
        jniLibs {
            useLegacyPackaging = true
            // Ship the vendor/plugin binaries exactly as published (no stripping).
            keepDebugSymbols += listOf("**/libneuronusdk_adapter.mtk.so", "**/libonnxruntime_providers_neuropilot.so", "**/libneuron_op_capability.so")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    androidResources {
        // Keep the bundled ONNX file uncompressed.
        noCompress += "onnx"
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":sdcore"))
    implementation("com.microsoft.onnxruntime:onnxruntime-android:$ortVersion")

    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.1")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    implementation(platform("androidx.compose:compose-bom:2025.04.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
}
