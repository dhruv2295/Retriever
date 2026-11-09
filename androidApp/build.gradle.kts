plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

if (providers.gradleProperty("coverage").isPresent) apply(plugin = "org.jetbrains.kotlinx.kover")

// ./gradlew -PcomposeReports :androidApp:compileDebugKotlin writes the stability reports to androidApp/build/compose.
composeCompiler {
    stabilityConfigurationFiles.add(layout.projectDirectory.file("stability.conf"))
    if (providers.gradleProperty("composeReports").isPresent) {
        reportsDestination = layout.buildDirectory.dir("compose")
        metricsDestination = layout.buildDirectory.dir("compose")
    }
}

android {
    namespace = "app.retriever"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "app.retriever"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.compileSdk.get().toInt()
        versionCode = 4
        versionName = "0.2"
    }

    // Local release testing uses the debug key; choose an upload key in Android Studio for store builds.
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    // Every variant builds one APK per ABI, no universal APK: arm64-v8a for phones, x86_64 for ChromeOS and emulators
    // (installDebug picks the device's). AGP can't build an App Bundle with splits on (issuetracker 402800800), and Play
    // splits a bundle by ABI itself, so a bundle build turns them off: run bundle tasks apart from assemble ones. The
    // 32-bit excludes under packaging keep 32-bit devices out of both. Splits and ndk abiFilters conflict in any build
    // type, so there are no filters.
    splits {
        abi {
            isEnable = gradle.startParameter.taskNames.none { it.contains("bundle", ignoreCase = true) }
            reset()
            include("arm64-v8a", "x86_64")
            isUniversalApk = false
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        // LiteRT loads NPU dispatch libraries from applicationInfo.nativeLibraryDir, so they must be extracted.
        jniLibs {
            useLegacyPackaging = true
            // Nothing in the app loads these: their loaders are mediapipe vision tasks (JniConfig), SemanticRetriever's
            // SqliteVectorStore and DefaultTextChunker, and AppSearch. UniversalEmbedder runs on liblitertlm_jni.
            // Using any of those APIs means deleting this line.
            excludes += listOf("**/libmediapipe_tasks_jni.so", "**/libsqlite_vector_store_jni.so", "**/libicing.so")
            // ML Kit and androidx ship 32-bit libraries, litertlm doesn't: Play derives an App Bundle's ABIs from its lib/
            // folders, so leaving these in would offer the app to 32-bit phones that can't load the model.
            excludes += listOf("lib/armeabi-v7a/**", "lib/x86/**")
        }
    }

    lint {
        // Also lint :core's androidMain, so an API above minSdk there (readNBytes was 33) fails the build.
        checkDependencies = true
        warningsAsErrors = true
        abortOnError = true
        checkReleaseBuilds = true
        // Versions are pinned on purpose (litertlm, AGP, the Compose BOM); an upgrade is a decision, not a lint nag.
        // -Poutdated turns the checks on to list newer versions.
        val versionChecks = setOf("NewerVersionAvailable", "GradleDependency", "AndroidGradlePluginVersion")
        if (providers.gradleProperty("outdated").isPresent) enable += versionChecks else disable += versionChecks
        // No baseline: it is empty, so a new lint issue fails the build.
    }

    testOptions { unitTests { isIncludeAndroidResources = true } }

    // The mascot's animated WebPs live once in the repo, in the iOS app's synced folder; Android packages the same files.
    sourceSets["main"].assets.directories.add("../iosApp/Retriever/Motion/Frames")

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// litertlm-android (also pulled in by mediapipe tasks-retrieval) drags in kotlin-reflect (~2 MB), reached only from
// kotlin.reflect.full call sites Retriever never takes. A per-dependency exclude misses the mediapipe path, and
// excluding it everywhere would break the Kotlin compiler classpath.
configurations.matching { it.name == "releaseRuntimeClasspath" }.configureEach { exclude(group = "org.jetbrains.kotlin", module = "kotlin-reflect") }

// Warnings are defects, in tests too; a dependency-forced deprecation is suppressed at its call, with the reason.
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions.allWarningsAsErrors.set(true)
}

// Robolectric's native SQLite/ICU loads trip JDK 24+'s restricted-method warning. The suite runs in up to 4 JVMs
// (capped for Robolectric's per-fork memory); each fork has its own Koin and WorkManager singletons.
tasks.withType<Test>().configureEach {
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    maxParallelForks = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(1, 4)
}

// About shows the repo's notices file; it is copied into the assets at build time, so the repo holds one copy.
abstract class CopyNotices : DefaultTask() {
    @get:InputFile abstract val source: RegularFileProperty

    @get:OutputDirectory abstract val out: DirectoryProperty

    @TaskAction fun copy() {
        source.get().asFile.copyTo(out.get().file("THIRD_PARTY_NOTICES.md").asFile, overwrite = true)
    }
}

androidComponents {
    onVariants { variant ->
        val copyNotices = tasks.register<CopyNotices>("copyNotices${variant.name.replaceFirstChar(Char::uppercase)}") {
            source.set(rootProject.layout.projectDirectory.file("THIRD_PARTY_NOTICES.md"))
        }
        variant.sources.assets?.addGeneratedSourceDirectory(copyNotices, CopyNotices::out)
    }
}

dependencies {
    implementation(project(":core"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.compose.ui)
    implementation(libs.activity.compose)
    implementation(libs.core.ktx)
    implementation(libs.core.splashscreen)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.sqldelight.android.driver)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.navigation.compose)
    implementation(libs.work.runtime)
    implementation(libs.telephoto.zoomable)
    implementation(libs.telephoto.sub.sampling.image)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    implementation(libs.lifecycle.process)
    implementation(libs.litert.npu.google.tensor)
    implementation(libs.litert.npu.mediatek)
    implementation(platform(libs.koin.bom))
    implementation(libs.koin.android)
    implementation(libs.koin.androidx.compose)
    implementation(libs.koin.androidx.workmanager)
    lintChecks(libs.compose.lint.checks)
    constraints {
        // Every class and member ML Kit's vision-internal-vkp and MediaPipe's tasks-core reference resolves in these
        // versions (javap over their constant pools). OkHttp 4 keeps 3.x's binary API; protobuf supports older gencode
        // on a newer runtime of the same major version.
        implementation(libs.okhttp) { because("ML Kit vision-internal-vkp pins 3.0.0: GHSA-3cqm-mf7h-prrj, GHSA-4hc2-jh7r-wrc3") }
        implementation(libs.protobuf.javalite) { because("MediaPipe tasks-core pins 4.26.1: CVE-2024-7254") }
    }

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.sqldelight.sqlite.driver)
    testImplementation(libs.koin.test)
    testImplementation(libs.koin.test.junit4)
    testImplementation(libs.work.testing)
    debugImplementation(libs.compose.ui.test.manifest)
    debugImplementation(libs.leakcanary.android)
}
