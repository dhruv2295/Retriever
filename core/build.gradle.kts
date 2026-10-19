import com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryTarget
import com.android.build.api.dsl.Lint
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.sqldelight)
    alias(libs.plugins.android.lint)
}

tasks.withType<Test>().configureEach { jvmArgs("--enable-native-access=ALL-UNNAMED") }

// The benches take minutes; they run only with -Pbench.
if (!providers.gradleProperty("bench").isPresent) {
    tasks.withType<Test>().configureEach { filter { excludeTestsMatching("*BenchTest") } }
}

if (providers.gradleProperty("coverage").isPresent) apply(plugin = "org.jetbrains.kotlinx.kover")

// One gate for both lint runs here: the KMP Android target's (lintAndroidMain) and the standalone one (lintJvm).
val lintGate: Lint.() -> Unit = {
    warningsAsErrors = true
    abortOnError = true
    checkReleaseBuilds = true
    // Versions are pinned on purpose (litertlm, AGP, the Compose BOM); an upgrade is a decision, not a lint nag.
    disable += setOf("NewerVersionAvailable", "GradleDependency", "AndroidGradlePluginVersion")
}
lint { lintGate() }

// Warnings are defects, in tests too; a dependency-forced deprecation is suppressed at its call, with the reason.
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask<*>>().configureEach {
    compilerOptions.allWarningsAsErrors.set(true)
}

kotlin {
    targets.withType<KotlinMultiplatformAndroidLibraryTarget>().configureEach {
        namespace = "app.retriever.core"
        compileSdk = libs.versions.compileSdk.get().toInt()
        minSdk = libs.versions.minSdk.get().toInt()
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
        // commonTest runs on the host JVM against androidMain; android.os stubs (Trace) return defaults.
        withHostTest { isReturnDefaultValues = true }
        lint { lintGate() }
    }
    // iOS targets build the static RetrieverCore framework that the SwiftUI app links.
    listOf(iosArm64(), iosSimulatorArm64()).forEach { t ->
        t.binaries.framework {
            baseName = "RetrieverCore"
            isStatic = true
        }
        t.binaries.all { linkerOpts("-lsqlite3") }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.sqldelight.coroutines)
        }
        iosMain.dependencies {
            implementation(libs.sqldelight.native.driver)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
        getByName("androidHostTest").dependencies {
            implementation(libs.sqldelight.sqlite.driver)
        }
        androidMain.dependencies {
            implementation(libs.sqldelight.android.driver)
            implementation(libs.mediapipe.tasks.retrieval)
            implementation(libs.litertlm.android)
            implementation(libs.mlkit.image.labeling)
            implementation(libs.mlkit.text.recognition)
            implementation(libs.mlkit.text.recognition.devanagari)
            implementation(libs.mlkit.text.recognition.chinese)
            implementation(libs.mlkit.text.recognition.japanese)
            implementation(libs.mlkit.text.recognition.korean)
        }
    }
}

sqldelight {
    databases {
        create("IndexDb") {
            packageName.set("app.retriever.core.db")
            // minSdk 30 ships SQLite 3.28: upsert (3.24) and FTS4 unicode61 remove_diacritics=2 (3.27) work; newer syntax does not.
            dialect("app.cash.sqldelight:sqlite-3-25-dialect:${libs.versions.sqldelight.get()}")
            // N.db is the schema at version N; verifyCommonMainIndexDbMigration replays every later N.sqm on it and diffs
            // against Index.sq. verifyMigrations stays off: it compiles the .sqm files from an empty schema, and 1.sqm
            // alters tables that no migration creates (version 1 was Index.sq itself).
            schemaOutputDirectory.set(file("src/commonMain/sqldelight/databases"))
        }
    }
}

// RelevanceReplayTest runs only when -Drelevance.dump=<dump.bin> is given; forward its properties to the test JVM.
tasks.withType<Test>().matching { it.name == "testAndroidHostTest" }.configureEach {
    listOf("relevance.dump", "relevance.data", "relevance.out", "relevance.sample", "relevance.minRows").forEach { key ->
        providers.systemProperty(key).orNull?.let { systemProperty(key, it) }
    }
}
