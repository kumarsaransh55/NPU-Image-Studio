pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "NPUImageStudio"

// sdcore  = the Stable Diffusion engine (plain Kotlin, no Android code)
// pctest  = a tiny command-line runner to test the engine on a Windows/Linux PC
// app     = the Android app (UI, model download, NPU/GPU/CPU selection)
include(":sdcore", ":pctest", ":app")
