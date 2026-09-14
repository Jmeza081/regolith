// :desktop — Regolith Chapters, the macOS chapter editor (docs/DESKTOP.md).
//
// Web analogy: a second app in the monorepo, next to the phone app, that
// imports the shared framework-free package (:core) and brings its own
// platform pieces: Compose for Desktop instead of Android Compose, VLC instead
// of ExoPlayer, a Window instead of an Activity.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
    // servers.json (ServerStore).
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(21)
}

sourceSets {
    main {
        // The phone's bundled fonts (Michroma, Space Grotesk), read in place so
        // there is one copy of each file in the repo.
        resources.srcDir("../app/src/main/res/font")
    }
}

dependencies {
    implementation(project(":core"))

    // Compose for Desktop: the same androidx.compose.* APIs the phone uses,
    // published by JetBrains for the JVM (Skia draws them).
    implementation(compose.desktop.currentOs)
    implementation(libs.jetbrains.compose.material3)
    implementation(libs.kotlinx.coroutines.swing)

    // Video: vlcj binds libvlc through JNA.
    implementation(libs.vlcj)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.slf4j.api)
    runtimeOnly(libs.slf4j.simple)

    testImplementation(testFixtures(project(":core")))
    testImplementation(libs.jetbrains.compose.ui.test.junit4)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.withType<Test> { useJUnit() }

compose.desktop {
    application {
        mainClass = "com.regolith.desktop.MainKt"
        // The Android Studio JBR has no jpackage/jlink. Point
        // REGOLITH_JPACKAGE_JDK at a full JDK 17+ to build the .app/.dmg.
        System.getenv("REGOLITH_JPACKAGE_JDK")?.let { javaHome = it }
        nativeDistributions {
            targetFormats(org.jetbrains.compose.desktop.application.dsl.TargetFormat.Dmg)
            packageName = "Regolith Chapters"
            // jpackage refuses a version whose first number is 0.
            packageVersion = "1.0.0"
            macOS { bundleID = "com.regolith.chapters" }
        }
    }
}

// Headless check of the share → VLC → sidecar path, no window (see Smoke.kt).
// Args: host port share, default the local Samba fixture.
tasks.register<JavaExec>("smoke") {
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.regolith.desktop.SmokeKt")
    args = (project.findProperty("smokeArgs") as String?)?.split(" ") ?: emptyList()
}
