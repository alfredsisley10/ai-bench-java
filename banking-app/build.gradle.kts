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
        val mirror = System.getProperty("enterprise.sim.mirror")
        if (mirror != null) {
            maven {
                url = uri("$mirror/maven-central/")
                isAllowInsecureProtocol = true
            }
        } else {
            mavenCentral()
        }
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
            // Bumped from 17 to 21 because shared-domain (Result.java,
            // CircuitBreakerRegistry.java) uses type patterns in switch
            // — JEP 441, finalized in Java 21. With toolchain 17 the
            // compiler treats them as a preview feature and the build
            // fails. The WebUI's "Verify Java" panel surfaces the
            // toolchain requirement so a future operator with only
            // JDK 17 installed sees the gap before clicking Start.
            languageVersion.set(JavaLanguageVersion.of(21))
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
