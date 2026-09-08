// Root build file: declares which plugins exist and their versions, but applies
// none of them (`apply false`). Each module opts in. Think of it as pinning
// devDependencies once so every module resolves the same version.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
}
