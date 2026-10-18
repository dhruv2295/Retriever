plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.kmp.library) apply false
    alias(libs.plugins.android.lint) apply false
    alias(libs.plugins.sqldelight) apply false
    alias(libs.plugins.kover) apply false
    alias(libs.plugins.ktlint) apply false
}

// ktlintCheck / ktlintFormat read .editorconfig. -PktlintFiles=a.kt,b.kts limits a run to those files (the pre-commit hook).
val onlyFiles = providers.gradleProperty("ktlintFiles").map { it.split(',').map(::file).toSet() }
allprojects {
    apply(plugin = "org.jlleitschuh.gradle.ktlint")
    configure<org.jlleitschuh.gradle.ktlint.KtlintExtension> {
        version.set(rootProject.libs.versions.ktlint)
        filter {
            onlyFiles.orNull?.let { files -> include { it.file in files } }
        }
    }
}
