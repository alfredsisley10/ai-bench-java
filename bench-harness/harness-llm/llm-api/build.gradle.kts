plugins {
    kotlin("plugin.serialization")
}

dependencies {
    implementation(project(":harness-core"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
}
