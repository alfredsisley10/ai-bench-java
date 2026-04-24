plugins {
    kotlin("jvm") version "2.0.20" apply false
    kotlin("plugin.serialization") version "2.0.20" apply false
    id("com.appland.appmap") version "1.2.0" apply false
}

allprojects {
    group = "com.aibench"
    version = "0.1.0-SNAPSHOT"

    repositories {
        mavenCentral()
    }
}

subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")

    extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
        jvmToolchain(17)
    }

    dependencies {
        add("implementation", "org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
        add("implementation", "org.slf4j:slf4j-api:2.0.16")
        add("testImplementation", "org.jetbrains.kotlin:kotlin-test")
        add("testImplementation", "io.kotest:kotest-assertions-core:5.9.1")
        add("testImplementation", "org.junit.jupiter:junit-jupiter:5.11.0")
        add("testRuntimeOnly", "org.junit.platform:junit-platform-launcher")
    }

    apply(plugin = "com.appland.appmap")

    extensions.configure<com.appland.appmap.gradle.AppMapPluginExtension> {
        configFile.set(rootProject.layout.projectDirectory.file("appmap.yml"))
        outputDirectory.set(layout.buildDirectory.dir("appmap"))
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
    }
}
