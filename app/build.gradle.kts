// Module build file for the one app module. Web analogy: this module's
// package.json scripts + bundler config. AGP 9 compiles Kotlin itself, so
// there is no separate Kotlin plugin here.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.regolith"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.regolith"
        // Android 14+. Frame extraction, foreground-service types and
        // user-initiated transfer jobs all exist without version branches.
        minSdk = 34
        targetSdk = 37
        versionCode = 1
        versionName = "0.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        // A debug keystore committed to the repo so every build on every machine
        // signs identically. Android refuses to update an installed app whose
        // signature changed, so without this a rebuild on another machine (or a
        // fresh checkout) would force an uninstall and wipe the Room database.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
            buildConfigField("boolean", "DEMO_LIBRARY", "true")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // No release key yet: sign with the debug key so a shrunk build is
            // still installable for side-loading. Swap for a real key before
            // any store upload.
            signingConfig = signingConfigs.getByName("debug")
            // The side-load build IS the test build today (a debug APK is too
            // big to send), so Settings' demo library ships in it. Set this
            // false — and drop res/raw/demo_*.mp4 — for a real store build.
            buildConfigField("boolean", "DEMO_LIBRARY", "true")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }


    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    // The exported Room schemas double as assets of the DEBUG build so the
    // migration test (which runs against the debug variant under Robolectric)
    // can open a real version-1 database and upgrade it. AGP does not merge
    // assets for the unit-test source set itself. Release stays clean.
    sourceSets.getByName("debug").assets.srcDir("$projectDir/schemas")

    lint {
        // The Kotlin compiler plugins (compose, serialization) must match the
        // Kotlin version AGP bundles (2.2.10), so "newer version available"
        // is noise here, not advice.
        disable += "NewerVersionAvailable"
    }
}

// Room writes a JSON snapshot of every schema version here; it is what
// makes migrations testable. Committed on purpose.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    // The shared, Android-free layer: domain/, the jcifs SMB client and the
    // chapter-sidecar writer (see core/build.gradle.kts). jcifs-ng arrives
    // through it.
    implementation(project(":core"))

    // Compose. The BOM pins every Compose artifact to one tested set.
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    // Adaptive layouts: window size class + fold posture for the foldable inner
    // display (ui/adaptive), and the list-detail scene strategy for Navigation 3.
    implementation(libs.androidx.compose.material3.adaptive)
    implementation(libs.androidx.compose.material3.adaptive.navigation3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)

    // Navigation 3
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.kotlinx.serialization.json)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)
    // Background work: the library scan is a WorkManager job that Hilt builds.
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    // Persistence
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // Media3 / ExoPlayer. inspector = MetadataRetriever, the container probe
    // behind Title Detail (codec, size, fps, audio).
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.datasource)
    implementation(libs.media3.ui.compose)
    implementation(libs.media3.inspector)
    implementation(libs.media3.inspector.frame)

    // jcifs-ng and :core log through slf4j; this binding routes it to logcat.
    implementation(libs.slf4j.android)

    // Async
    implementation(libs.kotlinx.coroutines.android)

    // UI extras. Coil draws artwork; it is only the view layer (see ArtworkModule).
    implementation(libs.haze)
    implementation(libs.lucide.icons)
    implementation(libs.coil.compose)

    // Tests
    testImplementation(libs.junit)
    // FakeSmbGateway, shared with :core's own tests.
    testImplementation(testFixtures(project(":core")))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.media3.test.utils)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    // Overrides the 3.5.0 that compose-ui-test-junit4 brings in; see the catalog.
    androidTestImplementation(libs.androidx.test.espresso.core)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
