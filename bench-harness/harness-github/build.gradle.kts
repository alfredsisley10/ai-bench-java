plugins {
    kotlin("plugin.serialization")
}

dependencies {
    implementation(project(":harness-core"))
    implementation("org.kohsuke:github-api:1.326")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
}
