// Gradle's "workspace" file. Web analogy: the root package.json's `workspaces`
// plus the registry list. Every module (only `:app` for now) is listed here.
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Regolith"
include(":app")
// Pure-Kotlin code shared by the phone and the Mac app (guardrail G11).
include(":core")
