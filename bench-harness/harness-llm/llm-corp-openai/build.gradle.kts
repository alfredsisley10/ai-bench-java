plugins {
    kotlin("plugin.serialization")
}

dependencies {
    implementation(project(":harness-llm:llm-api"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.charleskorn.kaml:kaml:0.61.0")
}
