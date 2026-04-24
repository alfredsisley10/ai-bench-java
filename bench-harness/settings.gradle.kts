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

rootProject.name = "bench-harness"

include("harness-config")
include("harness-core")
include("harness-github")
include("harness-jira")
include("harness-builder")
include("harness-appmap")
include("harness-llm:llm-api")
include("harness-llm:llm-copilot")
include("harness-llm:llm-corp-openai")
