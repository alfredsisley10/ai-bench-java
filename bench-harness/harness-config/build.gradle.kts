plugins {
    kotlin("plugin.serialization")
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("com.charleskorn.kaml:kaml:0.61.0")

    testImplementation("io.kotest:kotest-assertions-core:5.9.1")
}
