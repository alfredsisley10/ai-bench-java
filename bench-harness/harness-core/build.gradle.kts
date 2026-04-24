plugins {
    kotlin("plugin.serialization")
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("com.charleskorn.kaml:kaml:0.61.0")
    implementation("org.eclipse.jgit:org.eclipse.jgit:6.10.0.202406032230-r")
}
