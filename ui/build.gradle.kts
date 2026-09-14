// :ui — the design system both apps draw with: the theme tokens and the
// reusable components that today live in app/src/main/java/com/regolith/ui.
//
// Web analogy: a shared component library in a monorepo, published for two
// "browsers": Android (the phone app) and the JVM desktop (the Mac app).
// Compose Multiplatform compiles the same Kotlin for both.
//
// Step 1 of the move (docs/ARCHITECTURE.md): the module exists and both apps
// depend on it, with no code in it yet, so the build setup is proven on its
// own before any screen could be affected.
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    // Under AGP 9 a Kotlin Multiplatform library uses Google's KMP library
    // plugin; com.android.library no longer works with Kotlin Multiplatform.
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
}

kotlin {
    android {
        namespace = "com.regolith.ui"
        compileSdk = 37
        minSdk = 34
        // The same bytecode level as :app, so nothing the phone inlines from here is newer.
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }
    jvm("desktop")
    jvmToolchain(21)

    sourceSets {
        commonMain.dependencies {
            implementation(libs.jetbrains.compose.runtime)
            implementation(libs.jetbrains.compose.foundation)
            implementation(libs.jetbrains.compose.material3)
        }
    }
}
