// :desktop — Regolith Chapters, the macOS chapter editor (docs/DESKTOP.md).
//
// Web analogy: a second app in the monorepo, next to the phone app, that
// imports the shared framework-free package (:core) and brings its own
// platform pieces: Compose for Desktop instead of Android Compose, VLC instead
// of ExoPlayer, a Window instead of an Activity.
// Imported, not written fully qualified: inside a build script `java` is the
// Java plugin's extension, so `java.net.URI` does not resolve.
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import javax.inject.Inject
import org.gradle.process.ExecOperations

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

dependencies {
    implementation(project(":core"))
    implementation(project(":ui"))

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
            // libvlc and its plugins, fetched by `fetchVlc` into
            // vlc-bundle/macos-arm64/vlc and copied into the .app, so it plays
            // without VLC installed. NativeVlc finds them at runtime through
            // the `compose.application.resources.dir` system property.
            appResourcesRootDir.set(layout.projectDirectory.dir("vlc-bundle"))
            // The runtime image holds only the JDK modules listed. From
            // `suggestModules`: jcifs needs java.naming and java.security.jgss,
            // JNA needs jdk.unsupported. A missing one compiles fine and then
            // fails when the app connects or plays.
            modules("java.instrument", "java.naming", "java.security.jgss", "java.sql", "jdk.unsupported")
            targetFormats(org.jetbrains.compose.desktop.application.dsl.TargetFormat.Dmg)
            packageName = "Regolith Chapters"
            // jpackage refuses a version whose first number is 0.
            packageVersion = "1.0.0"
            macOS { bundleID = "com.regolith.chapters" }
        }
    }
}

/**
 * Downloads VideoLAN's own VLC for Apple Silicon, checks it against the
 * pinned SHA-256, and copies libvlc (`lib/`) and its plugins (`plugins/`) out
 * of the disk image. Nothing else from VLC.app is taken: `share/` holds Lua
 * playlist scripts and translations that a callback-media player never uses.
 *
 * Web analogy: a postinstall step that fetches a pinned native binary and
 * verifies its hash, like the ones esbuild or Playwright run.
 */
abstract class FetchVlc @Inject constructor(private val exec: ExecOperations) : DefaultTask() {
    @get:Input abstract val url: Property<String>
    @get:Input abstract val sha256: Property<String>
    @get:Internal abstract val downloadDir: DirectoryProperty
    @get:OutputDirectory abstract val destination: DirectoryProperty

    @TaskAction
    fun fetch() {
        val dmg = downloadDir.get().file(url.get().substringAfterLast('/')).asFile
        dmg.parentFile.mkdirs()
        if (!dmg.exists() || sha(dmg) != sha256.get()) download(dmg)
        val actual = sha(dmg)
        if (actual != sha256.get()) {
            dmg.delete()
            error("VLC download does not match its pinned checksum: expected ${sha256.get()}, got $actual")
        }

        val mount = Files.createTempDirectory("regolith-vlc").toFile()
        exec.exec { commandLine("hdiutil", "attach", "-nobrowse", "-readonly", "-noautoopen", "-mountpoint", mount.absolutePath, dmg.absolutePath) }
        try {
            val dest = destination.get().asFile
            dest.deleteRecursively()
            dest.mkdirs()
            val macos = File(mount, "VLC.app/Contents/MacOS")
            // cp -R, not a Gradle copy: it keeps libvlc.dylib -> libvlc.5.dylib
            // as symlinks instead of doubling the libraries.
            exec.exec { commandLine("cp", "-R", File(macos, "lib").absolutePath, File(macos, "plugins").absolutePath, dest.absolutePath) }
        } finally {
            exec.exec {
                commandLine("hdiutil", "detach", mount.absolutePath, "-quiet")
                isIgnoreExitValue = true
            }
            mount.delete()
        }
    }

    /**
     * get.videolan.org answers with a 302 from https to a plain-http mirror
     * (a different one each time). Java's URL connection will not follow a
     * redirect that drops to http and silently saves the 128-byte redirect
     * page instead, so this uses HttpClient with redirects always followed.
     * Plain http is acceptable here because the pinned SHA-256, taken from
     * VideoLAN's published checksum, is what vouches for the bytes.
     */
    private fun download(dmg: File) {
        logger.lifecycle("Downloading ${url.get()}")
        val part = File(dmg.parentFile, dmg.name + ".part")
        val client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.ALWAYS).build()
        val response = client.send(HttpRequest.newBuilder(URI(url.get())).GET().build(), HttpResponse.BodyHandlers.ofFile(part.toPath()))
        if (response.statusCode() != 200) {
            part.delete()
            error("VLC download failed: HTTP ${response.statusCode()} from ${response.uri()}")
        }
        logger.lifecycle("Downloaded ${part.length()} bytes from ${response.uri()}")
        Files.move(part.toPath(), dmg.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }

    private fun sha(f: File): String =
        MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }
}

val fetchVlc = tasks.register<FetchVlc>("fetchVlc") {
    group = "distribution"
    description = "Fetches libvlc + plugins (VLC 3.0.23, arm64) into vlc-bundle/ for the packaged app."
    url.set("https://get.videolan.org/vlc/3.0.23/macosx/vlc-3.0.23-arm64.dmg")
    // VideoLAN's published checksum for that file (vlc-3.0.23-arm64.dmg.sha256).
    sha256.set("fc6fac08d87f538517d44aca0c5e7a244b67c8c4cb589bf478363a7315fd5e0d")
    downloadDir.set(layout.buildDirectory.dir("vlc"))
    destination.set(layout.projectDirectory.dir("vlc-bundle/macos-arm64/vlc"))
    onlyIf("VLC is bundled for macOS on Apple Silicon only") {
        System.getProperty("os.name").startsWith("Mac") && System.getProperty("os.arch") == "aarch64"
    }
}

// Every task that copies app resources into a build needs the bundle first.
tasks.matching { it.name == "prepareAppResources" }.configureEach { dependsOn(fetchVlc) }


// Headless check of the share → libvlc → sidecar path, no window (see Smoke.kt).
// Uses the fetched bundle, so it also proves the bundled libvlc loads.
// Args: host port share, default the local Samba fixture.
tasks.register<JavaExec>("smoke") {
    dependsOn(fetchVlc)
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.regolith.desktop.SmokeKt")
    args = (project.findProperty("smokeArgs") as String?)?.split(" ") ?: emptyList()
}
