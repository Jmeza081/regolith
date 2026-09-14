// :core — the code both apps share (guardrail G9 in docs/ARCHITECTURE.md).
//
// Web analogy: a framework-free package in a monorepo that the React app and
// the Electron app both import. Pure Kotlin on the JVM: the domain models and
// rules, the jcifs SMB client and the chapter-sidecar writer. It may NOT
// depend on Android, Compose, Room or Hilt; anything that needs one of those
// belongs in :app or :desktop.
plugins {
    // A plain-JVM Kotlin module. Its version MUST equal the Kotlin that AGP 9
    // bundles (2.2.10, see the catalog), or Gradle refuses to load both.
    alias(libs.plugins.kotlin.jvm)
    // `testFixtures` is a third source set, published alongside the jar, for
    // test helpers other modules' tests use (FakeSmbGateway). Like a
    // `test-utils` package that only devDependencies import.
    `java-test-fixtures`
}

kotlin {
    // Same bytecode level as :app (compileOptions 17), so the phone's R8 and
    // desugaring see nothing new. The JBR 21 compiles it.
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    // `api` because the gateway's interfaces return coroutine types and
    // callers need them on their own classpath.
    api(libs.kotlinx.coroutines.core)
    // @Inject / @Singleton on JcifsGateway and SidecarWriter. On the phone,
    // Hilt reads these from this jar; on the Mac nothing reads them.
    api(libs.javax.inject)

    // SMB. jcifs-ng drags in the servlet API for an HTTP filter we never use.
    implementation(libs.jcifs.ng) {
        exclude(group = "javax.servlet")
    }
    // Logging facade only. Each app picks the binding: slf4j-android routes
    // to logcat on the phone, slf4j-simple to stderr on the Mac.
    implementation(libs.slf4j.api)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    testFixturesApi(libs.kotlinx.coroutines.core)
}
