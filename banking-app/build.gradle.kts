plugins {
    java
    id("org.springframework.boot") version "3.3.4" apply false
    id("io.spring.dependency-management") version "1.1.6" apply false
    id("com.appland.appmap") version "1.+" apply false
}

val appmapEnabled = (findProperty("appmap_enabled") as String?)?.toBoolean() ?: false

allprojects {
    group = "com.omnibank"
    version = "1.0.0-SNAPSHOT"

    repositories {
        mavenCentral()
    }
}

subprojects {
    apply(plugin = "java")
    apply(plugin = "io.spring.dependency-management")
    if (appmapEnabled) {
        apply(plugin = "com.appland.appmap")
    }

    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(17))
        }
    }

    extensions.configure<io.spring.gradle.dependencymanagement.dsl.DependencyManagementExtension> {
        imports {
            mavenBom("org.springframework.boot:spring-boot-dependencies:3.3.4")
            mavenBom("org.testcontainers:testcontainers-bom:1.20.1")
        }
    }

    dependencies {
        val impl = "implementation"
        val test = "testImplementation"
        add(impl, "org.slf4j:slf4j-api")
        add(impl, "jakarta.annotation:jakarta.annotation-api")
        add(test, "org.springframework.boot:spring-boot-starter-test")
        add(test, "org.assertj:assertj-core")
        add(test, "org.junit.jupiter:junit-jupiter")
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.compilerArgs.addAll(listOf("-Xlint:deprecation", "-parameters"))
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        testLogging {
            events("passed", "skipped", "failed")
        }
        // AppMap Gradle plugin wires the agent automatically when applied above.
    }
}
