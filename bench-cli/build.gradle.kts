plugins {
    kotlin("jvm") version "2.0.20"
    application
}

group = "com.aibench"
version = "0.1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation("com.aibench:harness-core")
    implementation("info.picocli:picocli:4.7.6")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("ch.qos.logback:logback-classic:1.5.8")
    testImplementation("org.jetbrains.kotlin:kotlin-test")
}

application {
    mainClass.set("com.aibench.cli.MainKt")
}
