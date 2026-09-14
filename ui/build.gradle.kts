// :ui — the design system both apps draw with: the theme tokens and the
// reusable components that today live in app/src/main/java/com/regolith/ui.
//
// Web analogy: a shared component library in a monorepo, published for two
// "browsers": Android (the phone app) and the JVM desktop (the Mac app).
// Compose Multiplatform compiles the same Kotlin for both.
//
// The move happens in steps (docs/ARCHITECTURE.md), each checked against
// screenshots of every phone screen. Moved so far: ui/theme and every
// component except ListRow, MediaTile, NavPill, PlayAll and Scrubber.
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
        // The two bundled fonts are Android resources here (R.font.*); a KMP
        // Android library builds no resources unless asked.
        androidResources {
            enable = true
        }
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
        // The Mac loads the same .ttf files from the classpath: one copy in the repo.
        named("desktopMain") {
            resources.srcDir("src/androidMain/res/font")
        }
    }
}
