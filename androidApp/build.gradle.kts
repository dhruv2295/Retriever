plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

if (providers.gradleProperty("coverage").isPresent) apply(plugin = "org.jetbrains.kotlinx.kover")

android {
    namespace = "app.retriever"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "app.retriever"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.compileSdk.get().toInt()
        versionCode = 1
        versionName = "0.2"
    }

    // litertlm ships only arm64-v8a and x86_64, so a 32-bit device must not install the app. x86_64 is for emulators.
    buildTypes {
        debug {
            ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        }
        release {
            ndk { abiFilters += listOf("arm64-v8a") }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Placeholder: the debug key, so local release installs work. Use a real upload key before any store release.
            signingConfig = signingConfigs.getByName("debug")
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
        }
    }

    lint {
        // Also lint :core's androidMain, so an API above minSdk there (readNBytes was 33) fails the build.
        checkDependencies = true
        warningsAsErrors = true
        abortOnError = true
        checkReleaseBuilds = true
        // Versions are pinned on purpose (litertlm, AGP, the Compose BOM); an upgrade is a decision, not a lint nag.
        disable += setOf("NewerVersionAvailable", "GradleDependency", "AndroidGradlePluginVersion")
        // Only documented false positives and recorded decisions, each with its reason.
        baseline = file("lint-baseline.xml")
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

// Warnings are defects on the app's main compilations; test compilations may carry a dependency-forced deprecation.
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    if (!name.contains("Test")) compilerOptions.allWarningsAsErrors.set(true)
}

// Robolectric's native SQLite/ICU loads trip JDK 24+'s restricted-method warning.
tasks.withType<Test>().configureEach { jvmArgs("--enable-native-access=ALL-UNNAMED") }

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
    implementation(libs.core.splashscreen)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.sqldelight.android.driver)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.navigation.compose)
    implementation(libs.work.runtime)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    implementation(libs.lifecycle.process)
    implementation(libs.litert.npu.google.tensor)
    implementation(libs.litert.npu.mediatek)
    implementation(platform(libs.koin.bom))
    implementation(libs.koin.android)
    implementation(libs.koin.androidx.compose)
    lintChecks(libs.compose.lint.checks)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.sqldelight.sqlite.driver)
    testImplementation(libs.koin.test)
    testImplementation(libs.koin.test.junit4)
    testImplementation(libs.work.testing)
    testImplementation(libs.compose.ui.test.junit4.accessibility)
    debugImplementation(libs.compose.ui.test.manifest)
    debugImplementation(libs.leakcanary.android)
}
