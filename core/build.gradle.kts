import com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryTarget
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.sqldelight)
    id("com.android.lint")
}

kotlin {
    targets.withType<KotlinMultiplatformAndroidLibraryTarget>().configureEach {
        namespace = "app.retriever.core"
        compileSdk = libs.versions.compileSdk.get().toInt()
        minSdk = libs.versions.minSdk.get().toInt()
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }
    // Host target so commonTest runs on the JVM.
    jvm { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }
    // iOS targets build the static RetrieverCore framework that the SwiftUI app links.
    listOf(iosArm64(), iosSimulatorArm64()).forEach { t ->
        t.binaries.framework { baseName = "RetrieverCore"; isStatic = true }
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
        jvmTest.dependencies {
            implementation(libs.sqldelight.sqlite.driver)
        }
        androidMain.dependencies {
            implementation(libs.sqldelight.android.driver)
            implementation(libs.mediapipe.tasks.retrieval)
            implementation(libs.litertlm.android)
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
        }
    }
}
