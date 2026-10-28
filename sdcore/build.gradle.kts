import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.jvm")
}

val ortVersion: String by project

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    // Only the Java API is needed to compile. The phone uses onnxruntime-android,
    // the PC runner uses the desktop onnxruntime jar - both expose the same classes.
    compileOnly("com.microsoft.onnxruntime:onnxruntime:$ortVersion")
}
