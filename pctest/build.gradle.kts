import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.jvm")
    application
}

val pcOrtVersion: String by project

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":sdcore"))
    implementation("com.microsoft.onnxruntime:onnxruntime:$pcOrtVersion")
}

application {
    mainClass.set("studio.npu.pctest.MainKt")
    applicationDefaultJvmArgs = listOf("-Xmx2g")
}
