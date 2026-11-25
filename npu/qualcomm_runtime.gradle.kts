// Build file of every npu/qualcomm_runtime_v* module: Qualcomm's QNN runtime for one Hexagon NPU version, which Play
// installs only on the Snapdragon its manifest names. Adapted from litert-samples kotlin_npu (THIRD_PARTY_NOTICES.md).
plugins {
    alias(libs.plugins.android.dynamic.feature)
}

val htp = name.removePrefix("qualcomm_runtime_v")

android {
    namespace = "app.retriever.qnn.v$htp"
    compileSdk = libs.versions.compileSdk.get().toInt()
    defaultConfig { minSdk = libs.versions.minSdk.get().toInt() }
    // As in androidApp: compressed in the APK and extracted to nativeLibraryDir, where LiteRT and the gate look.
    // Stored uncompressed, the phone would keep the 27 MB twice, since the base's extractNativeLibs extracts them anyway.
    packaging { jniLibs { useLegacyPackaging = true } }
}

// Not an implementation dependency: AGP rejects one library packaged by several feature modules, and each module needs
// a different part of it.
val qnn = configurations.create("qnn")

dependencies {
    implementation(project(":androidApp"))
    qnn(libs.qnn.runtime)
}

// The models are compiled ahead of time: no on-device compiler (libQnnHtpPrepare, 85 MB), no GPU or DSP backends, and
// only this module's HTP version.
abstract class QnnLibraries : DefaultTask() {
    @get:InputFiles abstract val aar: ConfigurableFileCollection

    @get:Input abstract val libraries: ListProperty<String>

    @get:OutputDirectory abstract val out: DirectoryProperty

    @get:Inject abstract val files: FileSystemOperations

    @get:Inject abstract val archives: ArchiveOperations

    @TaskAction fun extract() {
        files.sync {
            from(archives.zipTree(aar.singleFile)) {
                include(libraries.get().map { "jni/arm64-v8a/$it" })
                eachFile { path = "arm64-v8a/$name" }
            }
            includeEmptyDirs = false
            into(out)
        }
    }
}

val qnnLibraries = tasks.register<QnnLibraries>("qnnLibraries") {
    aar.from(qnn)
    libraries.set(listOf("libQnnHtp.so", "libQnnSystem.so", "libQnnHtpV${htp}Stub.so", "libQnnHtpV${htp}Skel.so"))
}

androidComponents {
    onVariants { it.sources.jniLibs?.addGeneratedSourceDirectory(qnnLibraries, QnnLibraries::out) }
}
