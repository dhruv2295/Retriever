pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Retriever"
include(":core", ":androidApp", ":facepack")
// Snapdragon NPU runtimes, one per Hexagon version, sharing one build file.
for (v in listOf("73", "75", "79", "81")) {
    include(":npu:qualcomm_runtime_v$v")
    project(":npu:qualcomm_runtime_v$v").buildFileName = "../qualcomm_runtime.gradle.kts"
}
