pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

// JDK 17 toolchain auto-provisioning via Foojay Disco API.
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

rootProject.name = "bench-webui"

includeBuild("../bench-harness")
