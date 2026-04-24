plugins {
    kotlin("plugin.serialization")
}

dependencies {
    implementation(project(":harness-llm:llm-api"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("com.github.jnr:jnr-unixsocket:0.38.22")
}
