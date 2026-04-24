pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

// Auto-provision the JDK the toolchain asks for (17) when the user's
// machine only has a newer or older JDK installed. Hits the Foojay
// Disco API to download the matching Temurin build into Gradle's
// caches.
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

rootProject.name = "bench-cli"

includeBuild("../bench-harness") {
    dependencySubstitution {
        substitute(module("com.aibench:harness-core")).using(project(":harness-core"))
        substitute(module("com.aibench:llm-api")).using(project(":harness-llm:llm-api"))
    }
}
